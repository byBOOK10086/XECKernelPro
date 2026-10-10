package org.matrix.TEESimulator.interception.keystore

import android.annotation.SuppressLint
import android.hardware.security.keymint.SecurityLevel
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.Domain
import android.system.keystore2.IKeystoreService
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import org.matrix.TEESimulator.attestation.AttestationPatcher
import org.matrix.TEESimulator.attestation.KeyMintAttestation
import org.matrix.TEESimulator.config.ConfigurationManager
import org.matrix.TEESimulator.interception.keystore.shim.GeneratedKeyPersistence
import org.matrix.TEESimulator.interception.keystore.shim.KeyMintSecurityLevelInterceptor
import org.matrix.TEESimulator.logging.KeyMintParameterLogger
import org.matrix.TEESimulator.logging.SystemLogger
import org.matrix.TEESimulator.pki.CertificateGenerator
import org.matrix.TEESimulator.pki.CertificateHelper

/**
 * Interceptor for the `IKeystoreService` on Android S (API 31) and newer.
 *
 * This version of Keystore delegates most cryptographic operations to `IKeystoreSecurityLevel`
 * sub-services (for TEE, StrongBox, etc.). This interceptor's main role is to set up interceptors
 * for those sub-services and to patch certificate chains on their way out.
 */
@SuppressLint("BlockedPrivateApi")
object Keystore2Interceptor : AbstractKeystoreInterceptor() {
    private val stubBinderClass = IKeystoreService.Stub::class.java

    // Transaction codes for the IKeystoreService interface methods we are interested in.
    private val GET_KEY_ENTRY_TRANSACTION =
        InterceptorUtils.getTransactCode(stubBinderClass, "getKeyEntry")
    private val DELETE_KEY_TRANSACTION =
        InterceptorUtils.getTransactCode(stubBinderClass, "deleteKey")
    private val GRANT_TRANSACTION = InterceptorUtils.getTransactCode(stubBinderClass, "grant")
    private val UNGRANT_TRANSACTION = InterceptorUtils.getTransactCode(stubBinderClass, "ungrant")
    private val UPDATE_SUBCOMPONENT_TRANSACTION =
        InterceptorUtils.getTransactCode(stubBinderClass, "updateSubcomponent")
    private val LIST_ENTRIES_TRANSACTION =
        InterceptorUtils.getTransactCode(stubBinderClass, "listEntries")
    private val LIST_ENTRIES_BATCHED_TRANSACTION =
        if (Build.VERSION.SDK_INT >= 34)
            InterceptorUtils.getTransactCode(stubBinderClass, "listEntriesBatched")
        else null
    private val GET_NUMBER_OF_ENTRIES_TRANSACTION =
        InterceptorUtils.getTransactCode(stubBinderClass, "getNumberOfEntries")

    private val transactionNames: Map<Int, String> by lazy {
        stubBinderClass.declaredFields
            .filter {
                it.isAccessible = true
                it.type == Int::class.java && it.name.startsWith("TRANSACTION_")
            }
            .associate { field -> (field.get(null) as Int) to field.name.split("_")[1] }
    }

    private const val RESPONSE_KEY_NOT_FOUND = 7
    private val deletedSoftwareKeys: MutableSet<KeyIdentifier> = ConcurrentHashMap.newKeySet()
    private val userUpdatedKeys = ConcurrentHashMap.newKeySet<KeyIdentifier>()

    // KeyStore2 stores a single certificate chain per key, so a grantee is supposed to observe
    // exactly the chain the owner observes. The real service can only hand back the raw hardware
    // chain, which no longer matches the APP path once the owner's chain has been patched, so every
    // grant issued through this process is remembered and its Domain.GRANT reads are answered from
    // the owner's cached response.
    private const val KEY_PERMISSION_GET_INFO = 4

    private data class GrantedKeyAccess(
        val keyId: KeyIdentifier,
        val granteeUid: Int,
        val accessVector: Int,
    )

    private val grantedKeys = ConcurrentHashMap<Long, GrantedKeyAccess>()

    /**
     * A `KeyDescriptor` decoded from a request, together with the layout it arrived in. Requests
     * that carry the descriptor without the structured-parcelable size word have to be re-serialised
     * before they are forwarded; see [readKeyDescriptor].
     */
    private data class ParsedKeyDescriptor(
        val descriptor: KeyDescriptor,
        val legacyLayout: Boolean,
    )

    override val serviceName = "android.system.keystore2.IKeystoreService/default"
    override val processName = "keystore2"
    override val injectionCommand = "exec ./inject `pidof keystore2` libTEESimulator.so entry"

    override val interceptedCodes: IntArray by lazy {
        listOfNotNull(
                GET_KEY_ENTRY_TRANSACTION,
                DELETE_KEY_TRANSACTION,
                GRANT_TRANSACTION,
                UNGRANT_TRANSACTION,
                UPDATE_SUBCOMPONENT_TRANSACTION,
                LIST_ENTRIES_TRANSACTION,
                LIST_ENTRIES_BATCHED_TRANSACTION,
                GET_NUMBER_OF_ENTRIES_TRANSACTION,
            )
            .toIntArray()
    }

    /**
     * This method is called once the main service is hooked. It proceeds to find and hook the
     * security level sub-services (e.g., TEE, StrongBox).
     */
    override fun onInterceptorReady(service: IBinder, backdoor: IBinder) {
        val keystoreInterface = IKeystoreService.Stub.asInterface(service)
        setupSecurityLevelInterceptors(keystoreInterface, backdoor)
    }

    private fun setupSecurityLevelInterceptors(service: IKeystoreService, backdoor: IBinder) {
        // Attempt to get and intercept the TEE security level service.
        runCatching {
                service.getSecurityLevel(SecurityLevel.TRUSTED_ENVIRONMENT)?.let { tee ->
                    SystemLogger.info("Found TEE SecurityLevel. Registering interceptor...")
                    val interceptor =
                        KeyMintSecurityLevelInterceptor(tee, SecurityLevel.TRUSTED_ENVIRONMENT)
                    register(
                        backdoor,
                        tee.asBinder(),
                        interceptor,
                        KeyMintSecurityLevelInterceptor.INTERCEPTED_CODES,
                    )
                    interceptor.loadPersistedKeys()
                }
            }
            .onFailure { SystemLogger.error("Failed to intercept TEE SecurityLevel.", it) }

        // Attempt to get and intercept the StrongBox security level service.
        runCatching {
                service.getSecurityLevel(SecurityLevel.STRONGBOX)?.let { strongbox ->
                    SystemLogger.info("Found StrongBox SecurityLevel. Registering interceptor...")
                    val interceptor =
                        KeyMintSecurityLevelInterceptor(strongbox, SecurityLevel.STRONGBOX)
                    register(
                        backdoor,
                        strongbox.asBinder(),
                        interceptor,
                        KeyMintSecurityLevelInterceptor.INTERCEPTED_CODES,
                    )
                    interceptor.loadPersistedKeys()
                }
            }
            .onFailure { SystemLogger.error("Failed to intercept StrongBox SecurityLevel.", it) }
    }

    override fun onPreTransact(
        txId: Long,
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): TransactionResult {
        if (code == GET_NUMBER_OF_ENTRIES_TRANSACTION) {
            logTransaction(txId, transactionNames[code]!!, callingUid, callingPid, true)
            return if (ConfigurationManager.shouldSkipUid(callingUid))
                TransactionResult.ContinueAndSkipPost
            else TransactionResult.Continue
        } else if (code == LIST_ENTRIES_TRANSACTION || code == LIST_ENTRIES_BATCHED_TRANSACTION) {
            logTransaction(txId, transactionNames[code]!!, callingUid, callingPid, true)

            val packages = ConfigurationManager.getPackagesForUid(callingUid).joinToString()
            val isGMS = packages.contains("com.google.android.gms")

            if (isGMS || ConfigurationManager.shouldSkipUid(callingUid)) {
                return TransactionResult.ContinueAndSkipPost
            }

            return runCatching {
                    val isBatchMode = code == LIST_ENTRIES_BATCHED_TRANSACTION
                    if (ListEntriesHandler.cacheParameters(txId, data, isBatchMode)) {
                        TransactionResult.Continue
                    } else {
                        TransactionResult.ContinueAndSkipPost
                    }
                }
                .getOrElse {
                    SystemLogger.error(
                        "[TX_ID: $txId] Failed to parse parameters for ${transactionNames[code]!!}",
                        it,
                    )
                    TransactionResult.ContinueAndSkipPost
                }
        } else if (
            code == GET_KEY_ENTRY_TRANSACTION ||
                code == DELETE_KEY_TRANSACTION ||
                code == UPDATE_SUBCOMPONENT_TRANSACTION
        ) {
            logTransaction(txId, transactionNames[code]!!, callingUid, callingPid)

            // A Domain.GRANT read is resolved before the skip gate: an isolated grantee is not a
            // package uid, so shouldSkipUid() would otherwise drop it to the real service.
            val skipUid = ConfigurationManager.shouldSkipUid(callingUid)
            if (skipUid && code != GET_KEY_ENTRY_TRANSACTION)
                return TransactionResult.ContinueAndSkipPost

            if (code == UPDATE_SUBCOMPONENT_TRANSACTION)
                return handleUpdateSubcomponent(callingUid, data)

            data.enforceInterface(IKeystoreService.DESCRIPTOR)
            val parsedDescriptor =
                readKeyDescriptor(data) ?: return TransactionResult.ContinueAndSkipPost
            val descriptor = parsedDescriptor.descriptor

            if (
                code == GET_KEY_ENTRY_TRANSACTION &&
                    descriptor.alias == null &&
                    descriptor.domain == Domain.GRANT
            ) {
                resolveGrantedKeyEntry(txId, callingUid, descriptor)?.let { return it }
            }

            if (skipUid) return TransactionResult.ContinueAndSkipPost

            if (code == DELETE_KEY_TRANSACTION) {
                val keyId =
                    if (descriptor.alias != null) {
                        KeyIdentifier(callingUid, descriptor.alias)
                    } else if (descriptor.domain == Domain.KEY_ID) {
                        KeyMintSecurityLevelInterceptor.findGeneratedKeyByKeyId(
                            callingUid, descriptor.nspace
                        )?.let { info ->
                            KeyMintSecurityLevelInterceptor.generatedKeys.entries
                                .find { it.value.nspace == info.nspace && it.key.uid == callingUid }
                                ?.key
                        }
                    } else null

                if (keyId != null) {
                    val isSoftwareKey =
                        KeyMintSecurityLevelInterceptor.generatedKeys.containsKey(keyId)
                    KeyMintSecurityLevelInterceptor.cleanupKeyData(keyId)
                    if (isSoftwareKey) {
                        deletedSoftwareKeys.add(keyId)
                        SystemLogger.info(
                            "[TX_ID: $txId] Deleted cached keypair ${keyId.alias}, replying with empty response."
                        )
                        return InterceptorUtils.createSuccessReply(writeResultCode = false)
                    }
                }
                return TransactionResult.ContinueAndSkipPost
            }

            if (descriptor.alias == null) {
                return TransactionResult.ContinueAndSkipPost
            }
            val keyId = KeyIdentifier(callingUid, descriptor.alias)

            val response = KeyMintSecurityLevelInterceptor.getGeneratedKeyResponse(keyId)
            if (response == null) {
                if (deletedSoftwareKeys.remove(keyId)) {
                    SystemLogger.info("[TX_ID: $txId] Returning KEY_NOT_FOUND for deleted key ${descriptor.alias}")
                    return InterceptorUtils.createErrorReply(RESPONSE_KEY_NOT_FOUND)
                }
                if (parsedDescriptor.legacyLayout) {
                    // The descriptor arrived without the parcelable size word keystore2 needs to
                    // decode it, so forwarding the request as-is would make the real service fail
                    // the whole transaction with a binder-level error: the caller then sees an empty
                    // reply and no service-specific code at all, which is a far louder fingerprint
                    // than the ordinary KEY_NOT_FOUND it asked for. Re-serialise the descriptor with
                    // the platform's own writer and let the real service answer normally.
                    SystemLogger.debug(
                        "[TX_ID: $txId] Forwarding a hand-built getKeyEntry request for ${descriptor.alias} in the platform layout."
                    )
                    return InterceptorUtils.createKeyDescriptorRequestData(descriptor)
                }
                return TransactionResult.Continue
            }

            if (KeyMintSecurityLevelInterceptor.isAttestationKey(keyId))
                SystemLogger.info("${descriptor.alias} was an attestation key")

            SystemLogger.info("[TX_ID: $txId] Found generated response for ${descriptor.alias}:")
            response.metadata?.authorizations?.forEach {
                KeyMintParameterLogger.logParameter(it.keyParameter)
            }
            return InterceptorUtils.createTypedObjectReply(response)
        } else if (code == GRANT_TRANSACTION || code == UNGRANT_TRANSACTION) {
            logTransaction(
                txId,
                transactionNames[code] ?: "unknown code=$code",
                callingUid,
                callingPid,
            )

            // grant is left to the real service; its reply carries the grant id this interceptor
            // needs, and that id is only known once the real call has been made.
            if (code == UNGRANT_TRANSACTION) {
                data.enforceInterface(IKeystoreService.DESCRIPTOR)
                val descriptor = data.readTypedObject(KeyDescriptor.CREATOR)
                val granteeUid = data.readInt()
                if (descriptor != null) forgetGrant(callingUid, descriptor, granteeUid)
            }
            return TransactionResult.Continue
        } else {
            logTransaction(
                txId,
                transactionNames[code] ?: "unknown code=$code",
                callingUid,
                callingPid,
                true,
            )
        }

        // Let most calls go through to the real service.
        return TransactionResult.ContinueAndSkipPost
    }

    override fun onPostTransact(
        txId: Long,
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): TransactionResult {
        if (target != keystoreService || reply == null || InterceptorUtils.hasException(reply))
            return TransactionResult.SkipTransaction

        if (code == GRANT_TRANSACTION) {
            logTransaction(
                txId,
                "post-${transactionNames[code] ?: "unknown code=$code"}",
                callingUid,
                callingPid,
            )
            return runCatching {
                    data.enforceInterface(IKeystoreService.DESCRIPTOR)
                    val keyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR)
                    val granteeUid = data.readInt()
                    val accessVector = data.readInt()
                    val grantDescriptor = reply.readTypedObject(KeyDescriptor.CREATOR)

                    val keyId = resolveGrantedKeyIdentifier(callingUid, keyDescriptor)
                    val grantId = grantDescriptor?.nspace
                    if (keyId != null && grantId != null && grantId != 0L) {
                        grantedKeys[grantId] = GrantedKeyAccess(keyId, granteeUid, accessVector)
                        SystemLogger.debug(
                            "[TX_ID: $txId] Remembered grant $grantId for $keyId (grantee=$granteeUid, accessVector=$accessVector)."
                        )
                    }
                    TransactionResult.SkipTransaction
                }
                .getOrElse {
                    SystemLogger.error("[TX_ID: $txId] Failed to track the granted key.", it)
                    TransactionResult.SkipTransaction
                }
        }

        if (code == GET_NUMBER_OF_ENTRIES_TRANSACTION) {
            logTransaction(txId, "post-${transactionNames[code]!!}", callingUid, callingPid)
            return runCatching {
                    val hardwareCount = reply.readInt()
                    val softwareCount =
                        KeyMintSecurityLevelInterceptor.generatedKeys.keys.count {
                            it.uid == callingUid
                        }
                    val totalCount = hardwareCount + softwareCount
                    val parcel = Parcel.obtain().apply {
                        writeNoException()
                        writeInt(totalCount)
                    }
                    TransactionResult.OverrideReply(parcel)
                }
                .getOrElse {
                    SystemLogger.error("[TX_ID: $txId] Failed to modify getNumberOfEntries.", it)
                    TransactionResult.SkipTransaction
                }
        } else if (code == LIST_ENTRIES_TRANSACTION || code == LIST_ENTRIES_BATCHED_TRANSACTION) {
            logTransaction(txId, "post-${transactionNames[code]!!}", callingUid, callingPid)

            return runCatching {
                    val updatedKeyDescriptors =
                        ListEntriesHandler.injectGeneratedKeys(txId, callingUid, reply)
                    InterceptorUtils.createTypedArrayReply(updatedKeyDescriptors)
                }
                .getOrElse {
                    SystemLogger.error(
                        "[TX_ID: $txId] Failed to update the result of ${transactionNames[code]!!}.",
                        it,
                    )
                    TransactionResult.SkipTransaction
                }
        } else if (code == GET_KEY_ENTRY_TRANSACTION) {
            data.enforceInterface(IKeystoreService.DESCRIPTOR)
            val keyDescriptor =
                data.readTypedObject(KeyDescriptor.CREATOR)
                    ?: return TransactionResult.SkipTransaction

            logTransaction(
                txId,
                "post-${transactionNames[code]!!} ${keyDescriptor.alias}",
                callingUid,
                callingPid,
            )

            if (!ConfigurationManager.shouldPatch(callingUid))
                return TransactionResult.SkipTransaction

            runCatching {
                    val response = reply.readTypedObject(KeyEntryResponse.CREATOR)!!
                    val keyId = KeyIdentifier(callingUid, keyDescriptor.alias)

                    if (userUpdatedKeys.remove(keyId)) {
                        SystemLogger.trace { "[TRACE-$txId] getKeyEntry $keyId: userUpdated=true, skipping patch" }
                        SystemLogger.debug("[TX_ID: $txId] Skipping cert patch for user-updated key $keyId.")
                        return TransactionResult.SkipTransaction
                    }

                    val authorizations = response.metadata.authorizations
                    val parsedParameters =
                        KeyMintAttestation(
                            authorizations?.map { it.keyParameter }?.toTypedArray() ?: emptyArray()
                        )

                    SystemLogger.trace { "[TRACE-$txId] getKeyEntry $keyId: isImport=${parsedParameters.isImportKey()} origin=${parsedParameters.origin} inImportedKeys=${KeyMintSecurityLevelInterceptor.importedKeys.contains(keyId)} hasPatchedChain=${KeyMintSecurityLevelInterceptor.getPatchedChain(keyId) != null} isAttestKey=${parsedParameters.isAttestKey()}" }

                    if (parsedParameters.isImportKey()) {
                        // The reply describes an imported key, so the chain the real service returned
                        // *is* the imported one. A chain cached under this alias belongs to whatever
                        // key was stored there before the import: splicing it into this reply would
                        // describe a key that no longer exists, and "leaf does not match the imported
                        // certificate while the old chain is still served" is exactly the stale
                        // retained narrative an overwrite probe looks for. Forget it and serve the
                        // real reply.
                        KeyMintSecurityLevelInterceptor.forgetCachedChain(keyId)
                        SystemLogger.debug(
                            "[TX_ID: $txId] Imported key $keyId: serving the imported chain, cached chain forgotten."
                        )
                        return TransactionResult.SkipTransaction
                    }

                    if (KeyMintSecurityLevelInterceptor.importedKeys.contains(keyId)) {
                        SystemLogger.trace { "[TRACE-$txId] getKeyEntry $keyId: in importedKeys set, skip" }
                        SystemLogger.debug("[TX_ID: $txId] Skipping attest-key override for imported key $keyId")
                        return TransactionResult.SkipTransaction
                    }

                    if (parsedParameters.isAttestKey()) {
                        SystemLogger.warning(
                            "[TX_ID: $txId] Found hardware attest key ${keyId.alias} in the reply."
                        )
                        val keyData =
                            CertificateGenerator.generateAttestedKeyPair(
                                callingUid,
                                keyId.alias,
                                null,
                                parsedParameters,
                                response.metadata.keySecurityLevel,
                            ) ?: throw Exception("Failed to create overriding attest key pair.")

                        CertificateHelper.updateCertificateChain(
                                response.metadata,
                                keyData.second.toTypedArray(),
                            )
                            .getOrThrow()
                        response.metadata.authorizations =
                            InterceptorUtils.patchAuthorizations(
                                response.metadata.authorizations,
                                callingUid,
                            )

                        val newNspace = SecureRandom().nextLong()
                        response.metadata.key?.let { it.nspace = newNspace }
                        KeyMintSecurityLevelInterceptor.generatedKeys[keyId] =
                            KeyMintSecurityLevelInterceptor.GeneratedKeyInfo(
                                keyData.first,
                                null,
                                newNspace,
                                response,
                                parsedParameters,
                            )
                        KeyMintSecurityLevelInterceptor.attestationKeys.add(keyId)

                        GeneratedKeyPersistence.save(
                            keyId = keyId,
                            keyPair = keyData.first,
                            nspace = newNspace,
                            securityLevel = response.metadata.keySecurityLevel,
                            certChain = keyData.second,
                            algorithm = parsedParameters.algorithm,
                            keySize = parsedParameters.keySize,
                            ecCurve = parsedParameters.ecCurve ?: 0,
                            purposes = parsedParameters.purpose,
                            digests = parsedParameters.digest,
                            isAttestationKey = true,
                        )

                        return InterceptorUtils.createTypedObjectReply(response)
                    }

                    val originalChain = CertificateHelper.getCertificateChain(response)

                    if (originalChain == null || originalChain.size < 2) {
                        SystemLogger.info(
                            "[TX_ID: $txId] Skip patching short certificate chain of length ${originalChain?.size}."
                        )
                        return TransactionResult.SkipTransaction
                    }

                    val cachedChain = KeyMintSecurityLevelInterceptor.getPatchedChain(keyId)

                    val finalChain: Array<Certificate>
                    if (cachedChain != null) {
                        SystemLogger.debug(
                            "[TX_ID: $txId] Using cached patched certificate chain for $keyId."
                        )
                        finalChain = cachedChain
                    } else {
                        SystemLogger.info(
                            "[TX_ID: $txId] No cached chain for $keyId. Performing live patch as a fallback."
                        )
                        finalChain =
                            AttestationPatcher.patchCertificateChain(originalChain, callingUid)
                        KeyMintSecurityLevelInterceptor.patchedChains[keyId] = finalChain
                    }

                    CertificateHelper.updateCertificateChain(response.metadata, finalChain)
                        .getOrThrow()
                    response.metadata.authorizations =
                        InterceptorUtils.patchAuthorizations(
                            response.metadata.authorizations,
                            callingUid,
                        )

                    return InterceptorUtils.createTypedObjectReply(response)
                }
                .onFailure {
                    SystemLogger.error(
                        "[TX_ID: $txId] Failed to modify hardware KeyEntryResponse.",
                        it,
                    )
                    return TransactionResult.SkipTransaction
                }
        }
        return TransactionResult.SkipTransaction
    }

    /**
     * Decodes the `KeyDescriptor` argument of a `getKeyEntry`, `deleteKey` or `updateSubcomponent`
     * request.
     *
     * AIDL serialises a structured parcelable as `[presence marker][size][domain][nspace][alias]
     * [blob]`. The size word is what lets a newer reader skip fields it does not know, and keystore2
     * refuses the whole transaction when it is absent: the caller then gets a binder-level failure -
     * an *empty* reply and `transact() == false` - instead of the service-specific error the method
     * would normally return. Requests assembled by hand (a detector probing the binder surface, or
     * an older Java-level hook that writes the parcel itself) omit that word, so they are recognised
     * here by looking at what sits where the size belongs: a value that cannot cover the descriptor
     * means the fields start there. Such a request is then re-serialised with
     * [InterceptorUtils.createKeyDescriptorRequestData] before it is forwarded, which is what turns
     * the caller's empty reply back into the ordinary native `KEY_NOT_FOUND`.
     *
     * The decision is made on the header alone, so it does not depend on how a particular platform
     * version reacts to the missing word.
     */
    private fun readKeyDescriptor(data: Parcel): ParsedKeyDescriptor? {
        val start = data.dataPosition()
        val marker = runCatching { data.readInt() }.getOrDefault(0)
        if (marker == 0) return null

        val sizeOrDomain = runCatching { data.readInt() }.getOrDefault(-1)
        val coversDescriptor = sizeOrDomain >= 4 && sizeOrDomain <= data.dataSize() - (start + 4)
        if (coversDescriptor) {
            data.setDataPosition(start)
            runCatching { data.readTypedObject(KeyDescriptor.CREATOR) }
                .getOrNull()
                ?.let { return ParsedKeyDescriptor(it, false) }
        }

        // Hand-built layout: the same fields in the same order, without the size word. The domain is
        // checked against the known values so that arbitrary bytes are not mistaken for a descriptor.
        data.setDataPosition(start + 4)
        return runCatching {
                val domain = data.readInt()
                if (domain < Domain.APP || domain > Domain.KEY_ID) return null
                ParsedKeyDescriptor(
                    KeyDescriptor().apply {
                        this.domain = domain
                        nspace = data.readLong()
                        alias = data.readString()
                        blob = data.createByteArray()
                    },
                    true,
                )
            }
            .getOrNull()
    }

    /**
     * Answers a `Domain.GRANT` getKeyEntry (`alias == null`, `nspace == grantId`) from the owner's
     * cached response.
     *
     * The real service only knows the raw hardware chain, so a grantee that reads the grant handle
     * observes a different ordered chain than the owner once the owner's chain has been patched or
     * generated. KeyStore2 itself keeps one chain per key and exposes it to both domains, so serving
     * the cached owner response is what keeps the two domains byte-identical.
     *
     * Deliberately conservative: the handle must be a grant this process issued, the caller must be
     * the recorded grantee, the recorded access vector must include GET_INFO (KeyStore2 answers
     * getKeyEntry with PERMISSION_DENIED otherwise), and the owner's response must still be cached.
     * Everything else returns null and falls through to the real service, which keeps grant caller
     * binding, revocation and access-vector enforcement exactly as the platform implements them.
     */
    private fun resolveGrantedKeyEntry(
        txId: Long,
        callingUid: Int,
        descriptor: KeyDescriptor,
    ): TransactionResult.OverrideReply? {
        val grant = grantedKeys[descriptor.nspace] ?: return null
        if (grant.granteeUid != callingUid) return null
        if ((grant.accessVector and KEY_PERMISSION_GET_INFO) == 0) return null

        val response =
            KeyMintSecurityLevelInterceptor.getGeneratedKeyResponse(grant.keyId) ?: return null
        SystemLogger.debug(
            "[TX_ID: $txId] Serving the cached chain of ${grant.keyId} for grant ${descriptor.nspace} to uid=$callingUid."
        )
        return InterceptorUtils.createTypedObjectReply(response)
    }

    /**
     * Maps a grant request descriptor back to the key it names. `Domain.APP` carries the alias;
     * `Domain.KEY_ID` carries a namespace the framework received from an earlier response.
     */
    private fun resolveGrantedKeyIdentifier(
        callingUid: Int,
        descriptor: KeyDescriptor?,
    ): KeyIdentifier? {
        descriptor ?: return null
        return when (descriptor.domain) {
            Domain.APP -> descriptor.alias?.let { KeyIdentifier(callingUid, it) }
            Domain.KEY_ID ->
                KeyMintSecurityLevelInterceptor.findKeyIdentifierByNspace(descriptor.nspace)
            else -> null
        }
    }

    /**
     * Drops remembered grants when the owner revokes them, so a revoked handle is never answered
     * from the cache. Revocation names the key (an APP alias, or the grant handle itself) plus the
     * grantee it applied to.
     */
    private fun forgetGrant(callingUid: Int, descriptor: KeyDescriptor, granteeUid: Int) {
        if (descriptor.domain == Domain.GRANT) {
            grantedKeys.remove(descriptor.nspace)
            return
        }
        val alias = descriptor.alias ?: return
        val keyId = KeyIdentifier(callingUid, alias)
        for (entry in grantedKeys.entries) {
            if (entry.value.keyId == keyId && entry.value.granteeUid == granteeUid) {
                grantedKeys.remove(entry.key)
            }
        }
    }

    /**
     * Maps an `updateSubcomponent` descriptor back to the key it names. `Domain.APP` carries the
     * alias; `Domain.KEY_ID` carries the key id the framework received from an earlier
     * `getKeyEntry`, which is how the Keystore SPI rewrites `setKeyEntry()` on an existing key;
     * `Domain.GRANT` carries a grant handle, and an update through it rewrites the granting owner's
     * certificate as well.
     *
     * Only the caller's own key is ever resolved: a foreign namespace must not let one caller evict
     * another app's cached chain, because falling back to the real service would hand that app the
     * unpatched hardware chain.
     */
    private fun resolveUpdatedKeyIdentifier(
        callingUid: Int,
        descriptor: KeyDescriptor,
    ): KeyIdentifier? {
        return when (descriptor.domain) {
            Domain.APP -> descriptor.alias?.let { KeyIdentifier(callingUid, it) }
            Domain.KEY_ID ->
                KeyMintSecurityLevelInterceptor.findKeyIdentifierByNspace(descriptor.nspace)
                    ?.takeIf { it.uid == callingUid }
            Domain.GRANT -> grantedKeys[descriptor.nspace]?.keyId?.takeIf { it.uid == callingUid }
            else -> null
        }
    }

    private fun handleUpdateSubcomponent(callingUid: Int, data: Parcel): TransactionResult {
        data.enforceInterface(IKeystoreService.DESCRIPTOR)
        val descriptor = data.readTypedObject(KeyDescriptor.CREATOR)
            ?: return TransactionResult.ContinueAndSkipPost

        val generatedKeyInfo =
            when (descriptor.domain) {
                Domain.KEY_ID ->
                    KeyMintSecurityLevelInterceptor.findGeneratedKeyByKeyId(
                        callingUid, descriptor.nspace
                    )
                Domain.APP ->
                    descriptor.alias?.let {
                        KeyMintSecurityLevelInterceptor.generatedKeys[KeyIdentifier(callingUid, it)]
                    }
                else -> null
            }

        if (generatedKeyInfo == null) {
            // The engine does not own this key's certificate: the real service is about to store the
            // new certificate and chain, so every response and chain cached for the key describes a
            // certificate that is disappearing. Drop them, otherwise the next getKeyEntry is answered
            // from the cache and still reports the pre-update chain - the "stale TEE response after a
            // key id update" narrative an update probe looks for. The key is remembered as
            // user-updated too, so a certificate the user installed is never patched afterwards.
            val updatedKeyId =
                resolveUpdatedKeyIdentifier(callingUid, descriptor)
                    ?: descriptor.alias?.let { KeyIdentifier(callingUid, it) }
            if (updatedKeyId != null) {
                KeyMintSecurityLevelInterceptor.forgetCachedChain(updatedKeyId)
                userUpdatedKeys.add(updatedKeyId)
                SystemLogger.trace {
                    "[TRACE] updateSubcomponent $updatedKeyId: dropped the cached certificate narrative"
                }
            }
            return TransactionResult.ContinueAndSkipPost
        }

        SystemLogger.info("Updating sub-component with key[${generatedKeyInfo.nspace}]")
        val metadata = generatedKeyInfo.response.metadata
        val publicCert = data.createByteArray()
        val certificateChain = data.createByteArray()

        metadata.certificate = publicCert
        metadata.certificateChain = certificateChain

        GeneratedKeyPersistence.rePersistIfNeeded(callingUid, generatedKeyInfo)

        SystemLogger.verbose(
            "Key updated with sizes: [publicCert, certificateChain] = [${publicCert?.size}, ${certificateChain?.size}]"
        )

        return InterceptorUtils.createSuccessReply(writeResultCode = false)
    }
}

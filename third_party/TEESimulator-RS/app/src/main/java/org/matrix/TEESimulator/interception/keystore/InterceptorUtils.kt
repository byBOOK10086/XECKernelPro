package org.matrix.TEESimulator.interception.keystore

import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.Tag
import android.os.Parcel
import android.os.Parcelable
import android.security.KeyStore
import android.security.keystore.KeystoreResponse
import android.system.keystore2.Authorization
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.matrix.TEESimulator.interception.core.BinderInterceptor
import org.matrix.TEESimulator.logging.SystemLogger
import org.matrix.TEESimulator.util.AndroidDeviceUtils

data class KeyIdentifier(val uid: Int, val alias: String)

/** A collection of utility functions to support binder interception. */
object InterceptorUtils {

    private const val EX_SERVICE_SPECIFIC = -8

    private const val FLAT_STRIDE_HEADER = 12
    private const val MAX_AUTH_COUNT = 256
    private const val SENTINEL_MODTIME = 4_294_967_297L
    private const val HIGH_MODTIME = 4_999_999_999L

    /**
     * The message keystore2 itself would have attached to this code.
     *
     * Positive values are `ResponseCode`, negative ones KeyMint `ErrorCode`, both as of
     * Android 14. keystore2 formats them with `Error::Rc({0:?})` / `Error::Km({0:?})` over the
     * AIDL-generated Rust enum, whose Debug prints the bare name - no `r#` raw-identifier
     * prefix. The ` (code N)` suffix seen in a caught exception is appended by Java's
     * ServiceSpecificException.toString(), not written here.
     *
     * Values AIDL reserves (1, 5, 9-16) or omits from `ErrorCode` have no name; they stay
     * numeric, which is what the daemon prints for them as well.
     */
    private fun synthesizeSseMessage(errorCode: Int): String =
        when (errorCode) {
            2 -> "Error::Rc(LOCKED)"
            3 -> "Error::Rc(UNINITIALIZED)"
            4 -> "Error::Rc(SYSTEM_ERROR)"
            6 -> "Error::Rc(PERMISSION_DENIED)"
            7 -> "Error::Rc(KEY_NOT_FOUND)"
            8 -> "Error::Rc(VALUE_CORRUPTED)"
            17 -> "Error::Rc(KEY_PERMANENTLY_INVALIDATED)"
            18 -> "Error::Rc(BACKEND_BUSY)"
            19 -> "Error::Rc(OPERATION_BUSY)"
            20 -> "Error::Rc(INVALID_ARGUMENT)"
            21 -> "Error::Rc(TOO_MUCH_DATA)"
            22 -> "Error::Rc(OUT_OF_KEYS)"
            23 -> "Error::Rc(OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE)"
            24 -> "Error::Rc(OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY)"
            25 -> "Error::Rc(OUT_OF_KEYS_TRANSIENT_ERROR)"
            26 -> "Error::Rc(OUT_OF_KEYS_PERMANENT_ERROR)"
            -1 -> "Error::Km(ROOT_OF_TRUST_ALREADY_SET)"
            -2 -> "Error::Km(UNSUPPORTED_PURPOSE)"
            -3 -> "Error::Km(INCOMPATIBLE_PURPOSE)"
            -4 -> "Error::Km(UNSUPPORTED_ALGORITHM)"
            -5 -> "Error::Km(INCOMPATIBLE_ALGORITHM)"
            -6 -> "Error::Km(UNSUPPORTED_KEY_SIZE)"
            -7 -> "Error::Km(UNSUPPORTED_BLOCK_MODE)"
            -8 -> "Error::Km(INCOMPATIBLE_BLOCK_MODE)"
            -9 -> "Error::Km(UNSUPPORTED_MAC_LENGTH)"
            -10 -> "Error::Km(UNSUPPORTED_PADDING_MODE)"
            -11 -> "Error::Km(INCOMPATIBLE_PADDING_MODE)"
            -12 -> "Error::Km(UNSUPPORTED_DIGEST)"
            -13 -> "Error::Km(INCOMPATIBLE_DIGEST)"
            -14 -> "Error::Km(INVALID_EXPIRATION_TIME)"
            -15 -> "Error::Km(INVALID_USER_ID)"
            -16 -> "Error::Km(INVALID_AUTHORIZATION_TIMEOUT)"
            -17 -> "Error::Km(UNSUPPORTED_KEY_FORMAT)"
            -18 -> "Error::Km(INCOMPATIBLE_KEY_FORMAT)"
            -19 -> "Error::Km(UNSUPPORTED_KEY_ENCRYPTION_ALGORITHM)"
            -20 -> "Error::Km(UNSUPPORTED_KEY_VERIFICATION_ALGORITHM)"
            -21 -> "Error::Km(INVALID_INPUT_LENGTH)"
            -22 -> "Error::Km(KEY_EXPORT_OPTIONS_INVALID)"
            -23 -> "Error::Km(DELEGATION_NOT_ALLOWED)"
            -24 -> "Error::Km(KEY_NOT_YET_VALID)"
            -25 -> "Error::Km(KEY_EXPIRED)"
            -26 -> "Error::Km(KEY_USER_NOT_AUTHENTICATED)"
            -27 -> "Error::Km(OUTPUT_PARAMETER_NULL)"
            -28 -> "Error::Km(INVALID_OPERATION_HANDLE)"
            -29 -> "Error::Km(INSUFFICIENT_BUFFER_SPACE)"
            -30 -> "Error::Km(VERIFICATION_FAILED)"
            -31 -> "Error::Km(TOO_MANY_OPERATIONS)"
            -32 -> "Error::Km(UNEXPECTED_NULL_POINTER)"
            -33 -> "Error::Km(INVALID_KEY_BLOB)"
            -34 -> "Error::Km(IMPORTED_KEY_NOT_ENCRYPTED)"
            -35 -> "Error::Km(IMPORTED_KEY_DECRYPTION_FAILED)"
            -36 -> "Error::Km(IMPORTED_KEY_NOT_SIGNED)"
            -37 -> "Error::Km(IMPORTED_KEY_VERIFICATION_FAILED)"
            -38 -> "Error::Km(INVALID_ARGUMENT)"
            -39 -> "Error::Km(UNSUPPORTED_TAG)"
            -40 -> "Error::Km(INVALID_TAG)"
            -41 -> "Error::Km(MEMORY_ALLOCATION_FAILED)"
            -44 -> "Error::Km(IMPORT_PARAMETER_MISMATCH)"
            -45 -> "Error::Km(SECURE_HW_ACCESS_DENIED)"
            -46 -> "Error::Km(OPERATION_CANCELLED)"
            -47 -> "Error::Km(CONCURRENT_ACCESS_CONFLICT)"
            -48 -> "Error::Km(SECURE_HW_BUSY)"
            -49 -> "Error::Km(SECURE_HW_COMMUNICATION_FAILED)"
            -50 -> "Error::Km(UNSUPPORTED_EC_FIELD)"
            -51 -> "Error::Km(MISSING_NONCE)"
            -52 -> "Error::Km(INVALID_NONCE)"
            -53 -> "Error::Km(MISSING_MAC_LENGTH)"
            -54 -> "Error::Km(KEY_RATE_LIMIT_EXCEEDED)"
            -55 -> "Error::Km(CALLER_NONCE_PROHIBITED)"
            -56 -> "Error::Km(KEY_MAX_OPS_EXCEEDED)"
            -57 -> "Error::Km(INVALID_MAC_LENGTH)"
            -58 -> "Error::Km(MISSING_MIN_MAC_LENGTH)"
            -59 -> "Error::Km(UNSUPPORTED_MIN_MAC_LENGTH)"
            -60 -> "Error::Km(UNSUPPORTED_KDF)"
            -61 -> "Error::Km(UNSUPPORTED_EC_CURVE)"
            -62 -> "Error::Km(KEY_REQUIRES_UPGRADE)"
            -63 -> "Error::Km(ATTESTATION_CHALLENGE_MISSING)"
            -64 -> "Error::Km(KEYMINT_NOT_CONFIGURED)"
            -65 -> "Error::Km(ATTESTATION_APPLICATION_ID_MISSING)"
            -66 -> "Error::Km(CANNOT_ATTEST_IDS)"
            -67 -> "Error::Km(ROLLBACK_RESISTANCE_UNAVAILABLE)"
            -68 -> "Error::Km(HARDWARE_TYPE_UNAVAILABLE)"
            -69 -> "Error::Km(PROOF_OF_PRESENCE_REQUIRED)"
            -70 -> "Error::Km(CONCURRENT_PROOF_OF_PRESENCE_REQUESTED)"
            -71 -> "Error::Km(NO_USER_CONFIRMATION)"
            -72 -> "Error::Km(DEVICE_LOCKED)"
            -73 -> "Error::Km(EARLY_BOOT_ENDED)"
            -74 -> "Error::Km(ATTESTATION_KEYS_NOT_PROVISIONED)"
            -75 -> "Error::Km(ATTESTATION_IDS_NOT_PROVISIONED)"
            -76 -> "Error::Km(INVALID_OPERATION)"
            -77 -> "Error::Km(STORAGE_KEY_UNSUPPORTED)"
            -78 -> "Error::Km(INCOMPATIBLE_MGF_DIGEST)"
            -79 -> "Error::Km(UNSUPPORTED_MGF_DIGEST)"
            -80 -> "Error::Km(MISSING_NOT_BEFORE)"
            -81 -> "Error::Km(MISSING_NOT_AFTER)"
            -82 -> "Error::Km(MISSING_ISSUER_SUBJECT)"
            -83 -> "Error::Km(INVALID_ISSUER_SUBJECT)"
            -84 -> "Error::Km(BOOT_LEVEL_EXCEEDED)"
            -85 -> "Error::Km(HARDWARE_NOT_YET_AVAILABLE)"
            -100 -> "Error::Km(UNIMPLEMENTED)"
            -101 -> "Error::Km(VERSION_MISMATCH)"
            -1000 -> "Error::Km(UNKNOWN_ERROR)"
            else -> if (errorCode > 0) "Error::Rc($errorCode)" else "Error::Km($errorCode)"
        }

    fun createErrorReply(errorCode: Int): BinderInterceptor.TransactionResult.OverrideReply {
        val parcel =
            Parcel.obtain().apply {
                writeInt(EX_SERVICE_SPECIFIC)
                writeString(synthesizeSseMessage(errorCode))
                writeInt(0) // empty remote stack trace header
                writeInt(errorCode)
            }
        return BinderInterceptor.TransactionResult.OverrideReply(parcel)
    }

    /**
     * Uses reflection to get the integer transaction code for a given method name from a Stub
     * class. This is necessary for older Android versions where codes are not public constants.
     */
    fun getTransactCode(clazz: Class<*>, method: String): Int {
        return try {
            clazz.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)
        } catch (e: Exception) {
            SystemLogger.error(
                "Failed to get transaction code for method '$method' in class '${clazz.simpleName}'.",
                e,
            )
            -1 // Return an invalid code
        }
    }

    /** Creates an `KeystoreResponse` parcel that indicates success with no data. */
    fun createSuccessKeystoreResponse(): KeystoreResponse {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(KeyStore.NO_ERROR)
            parcel.writeString("")
            parcel.setDataPosition(0)
            return KeystoreResponse.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /** Creates an `OverrideReply` parcel that indicates success with no data. */
    fun createSuccessReply(
        writeResultCode: Boolean = true
    ): BinderInterceptor.TransactionResult.OverrideReply {
        val parcel =
            Parcel.obtain().apply {
                writeNoException()
                if (writeResultCode) {
                    writeInt(KeyStore.NO_ERROR)
                }
            }
        return BinderInterceptor.TransactionResult.OverrideReply(parcel)
    }

    /** Creates an `OverrideReply` parcel containing a raw byte array. */
    fun createByteArrayReply(data: ByteArray): BinderInterceptor.TransactionResult.OverrideReply {
        val parcel =
            Parcel.obtain().apply {
                writeNoException()
                writeByteArray(data)
            }
        return BinderInterceptor.TransactionResult.OverrideReply(parcel)
    }

    /** Creates an `OverrideReply` parcel containing a typed array. */
    fun <T : Parcelable> createTypedArrayReply(
        array: Array<T>,
        flags: Int = 0,
    ): BinderInterceptor.TransactionResult.OverrideReply {
        val parcel =
            Parcel.obtain().apply {
                writeNoException()
                writeTypedArray(array, flags)
            }
        return BinderInterceptor.TransactionResult.OverrideReply(parcel)
    }

    /** Correlates a captured reply parcel to the app that triggered it, for [createTypedObjectReply]. */
    data class ReplyDiagnostic(val uid: Int, val txId: Long?, val event: String)

    /** Creates an `OverrideReply` parcel containing a Parcelable object. */
    fun <T : Parcelable?> createTypedObjectReply(
        obj: T,
        flags: Int = 0,
        diagnostic: ReplyDiagnostic? = null,
    ): BinderInterceptor.TransactionResult.OverrideReply {
        val parcel =
            Parcel.obtain().apply {
                writeNoException()
                writeTypedObject(obj, flags)
            }
        if (diagnostic != null && SystemLogger.isUidLogged(diagnostic.uid)) {
            val savedPos = parcel.dataPosition()
            val wire = parcel.marshall()
            parcel.setDataPosition(savedPos)
            SystemLogger.uidLogRaw(diagnostic.uid, diagnostic.txId, diagnostic.event, "len=${wire.size}", wire)
        }
        return BinderInterceptor.TransactionResult.OverrideReply(parcel)
    }

    /**
     * Extracts the base alias from a potentially prefixed alias string. For example, it converts
     * "USRCERT_my_key" to "my_key".
     */
    fun extractAlias(prefixedAlias: String): String {
        val underscoreIndex = prefixedAlias.indexOf('_')
        return if (underscoreIndex != -1) {
            // Return the part of the string after the first underscore.
            prefixedAlias.substring(underscoreIndex + 1)
        } else {
            // If there's no underscore, return the original string.
            prefixedAlias
        }
    }

    /** Checks if a reply parcel contains an exception without consuming it. */
    fun hasException(reply: Parcel): Boolean {
        val exception = runCatching { reply.readException() }.exceptionOrNull()
        if (exception != null) reply.setDataPosition(0)
        return exception != null
    }

    fun createServiceSpecificErrorReply(
        errorCode: Int
    ): BinderInterceptor.TransactionResult.OverrideReply = createErrorReply(errorCode)

    /**
     * The service-specific code carried by [reply], or [Int.MIN_VALUE] when the reply is not a
     * service-specific exception.
     *
     * Mirrors the field order [normalizeServiceSpecificReply] relies on: exception marker,
     * message, remote-stack size, then the code.
     */
    fun readServiceSpecificCode(reply: Parcel): Int {
        val position = reply.dataPosition()
        return try {
            reply.setDataPosition(0)
            if (reply.readInt() != EX_SERVICE_SPECIFIC) {
                Int.MIN_VALUE
            } else {
                reply.readString()
                reply.readInt()
                reply.readInt()
            }
        } catch (e: Exception) {
            Int.MIN_VALUE
        } finally {
            runCatching { reply.setDataPosition(position) }
        }
    }

    fun normalizeServiceSpecificReply(reply: Parcel): Parcel? {
        reply.setDataPosition(0)
        if (reply.readInt() != EX_SERVICE_SPECIFIC) {
            reply.setDataPosition(0)
            return null
        }
        // Advance position past message and stack header to reach errorCode.
        reply.readString()
        reply.readInt()
        val errorCode = reply.readInt()
        reply.setDataPosition(0)
        return Parcel.obtain().apply {
            writeInt(EX_SERVICE_SPECIFIC)
            writeString(synthesizeSseMessage(errorCode))
            writeInt(0)
            writeInt(errorCode)
        }
    }

    fun patchAuthorizations(
        authorizations: Array<Authorization>?,
        callingUid: Int,
    ): Array<Authorization>? {
        if (authorizations == null) return null

        val osPatch = AndroidDeviceUtils.getPatchLevel(callingUid)
        val vendorPatch = AndroidDeviceUtils.getVendorPatchLevelLong(callingUid)
        val bootPatch = AndroidDeviceUtils.getBootPatchLevelLong(callingUid)

        val patched =
            authorizations.map { auth ->
                val replacement =
                    when (auth.keyParameter.tag) {
                        Tag.OS_PATCHLEVEL ->
                            if (osPatch != AndroidDeviceUtils.DO_NOT_REPORT) osPatch else null
                        Tag.VENDOR_PATCHLEVEL ->
                            if (vendorPatch != AndroidDeviceUtils.DO_NOT_REPORT) vendorPatch
                            else null
                        Tag.BOOT_PATCHLEVEL ->
                            if (bootPatch != AndroidDeviceUtils.DO_NOT_REPORT) bootPatch else null
                        else -> null
                    }
                if (replacement != null) {
                    Authorization().apply {
                        keyParameter =
                            KeyParameter().apply {
                                tag = auth.keyParameter.tag
                                value = KeyParameterValue.integer(replacement)
                            }
                        securityLevel = auth.securityLevel
                    }
                } else {
                    auth
                }
            }
            .toTypedArray()
        return normalizeAuthorizationLayout(patched)
    }

    /**
     * Reorders a generateKey reply's authorizations only when the marshalled reply would read, to a
     * flat 12-byte-stride parcel fingerprint, as the TEE-simulator sentinel: a last slot of
     * securityLevel 4 or 256, tag 1, union 32 with the 0x1_0000_0001 pseudo-timestamp, or any
     * timestamp past [HIGH_MODTIME]. Real Android 16 hardware emits a 13-authorization layout for
     * single-purpose EC keys that lands on that sentinel, so mirroring hardware byte-for-byte is
     * itself flagged. Clients resolve authorizations by tag and the certificate chain is a separate
     * field, so reordering is the minimal capability-preserving way to clear the false positive for
     * any caller. Already-clean replies are returned unchanged.
     */
    fun normalizeAuthorizationLayout(authorizations: Array<Authorization>): Array<Authorization> {
        if (authorizations.size < 2) return authorizations
        if (!flatStrideFingerprintMatches(marshalTypedArray(authorizations))) return authorizations
        val n = authorizations.size
        for (src in 1 until n) {
            val candidate = moveAuthorization(authorizations, src, 0)
            if (!flatStrideFingerprintMatches(marshalTypedArray(candidate))) return candidate
        }
        for (src in 0 until n) {
            for (dst in 0 until n) {
                if (src == dst) continue
                val candidate = moveAuthorization(authorizations, src, dst)
                if (!flatStrideFingerprintMatches(marshalTypedArray(candidate))) return candidate
            }
        }
        return authorizations
    }

    private fun moveAuthorization(
        authorizations: Array<Authorization>,
        src: Int,
        dst: Int,
    ): Array<Authorization> {
        val reordered = authorizations.toMutableList()
        reordered.add(dst, reordered.removeAt(src))
        return reordered.toTypedArray()
    }

    private fun marshalTypedArray(authorizations: Array<Authorization>): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            // keystore2 AIDL compile stubs omit the Parcelable supertype these types carry at runtime.
            parcel.writeTypedArray(authorizations.map { it as Parcelable }.toTypedArray(), 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun flatStrideFingerprintMatches(marshalled: ByteArray): Boolean =
        runCatching {
            val parcel = ByteBuffer.wrap(marshalled).order(ByteOrder.LITTLE_ENDIAN)
            val count = parcel.getInt(0)
            if (count !in 1..MAX_AUTH_COUNT) return@runCatching false
            var off = 4
            var lastSec = 0L
            var lastTag = 0L
            var lastUnion = 0L
            repeat(count) {
                lastSec = u32(parcel, off)
                lastTag = u32(parcel, off + 4)
                lastUnion = u32(parcel, off + 8)
                off += FLAT_STRIDE_HEADER
                off = alignWord(off + flatPayloadSize(parcel, off, lastUnion))
            }
            off = skipDriftedByteArray(parcel, off)
            off = skipDriftedByteArray(parcel, off)
            val modtime = parcel.getLong(alignWord(off))
            val unknownUnion = lastUnion !in 0..14
            modtime > HIGH_MODTIME ||
                (modtime == SENTINEL_MODTIME &&
                    (lastSec == 4L || lastSec == 256L) &&
                    lastTag == 1L &&
                    lastUnion == 32L &&
                    unknownUnion)
        }.getOrDefault(false)

    private fun flatPayloadSize(parcel: ByteBuffer, off: Int, union: Long): Int =
        when {
            union in 1..11 -> 4
            union == 12L || union == 13L -> 8
            union == 14L -> alignWord(off + 4 + parcel.getInt(off)) - off
            else -> 0
        }

    private fun skipDriftedByteArray(parcel: ByteBuffer, off: Int): Int {
        if (parcel.getInt(off) == 0) return off + 4
        val lengthPos = off + 4
        return alignWord(lengthPos + 4 + parcel.getInt(lengthPos))
    }

    private fun u32(parcel: ByteBuffer, off: Int): Long = parcel.getInt(off).toLong() and 0xFFFFFFFFL

    private fun alignWord(off: Int): Int = (off + 3) and 3.inv()
}

package org.matrix.TEESimulator.interception.keystore.shim

import android.hardware.security.keymint.Algorithm
import android.hardware.security.keymint.BlockMode
import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyPurpose
import android.hardware.security.keymint.PaddingMode
import android.hardware.security.keymint.Tag
import org.matrix.TEESimulator.attestation.KeyMintAttestation

object AuthorizeCreate {

    fun check(
        keyParams: KeyMintAttestation?,
        opParams: KeyMintAttestation,
        rawOpParams: Array<KeyParameter>? = null,
    ): Int? {
        if (keyParams == null) return null
        val purpose = opParams.purpose.firstOrNull() ?: return null
        // Algorithm-level rejection runs before purpose-list check (AOSP HAL behavior)
        return checkAlgorithmPurpose(keyParams, purpose)
            ?: checkPurpose(keyParams, purpose)
            ?: checkOperationAuthorizations(keyParams, opParams)
            ?: checkTemporalValidity(keyParams, purpose)
            ?: checkCallerNonce(keyParams, purpose, rawOpParams)
    }

    private fun checkAlgorithmPurpose(keyParams: KeyMintAttestation, purpose: Int): Int? {
        val algo = keyParams.algorithm
        // EC 只能签名/验签/协商，加密与解密确实不支持；RSA 四项都支持，只有密钥协商
        // 不支持。以前这里写成"EC|RSA 一律拒 VERIFY 和 ENCRYPT"，会把合法的 ECDSA 验签
        // 和 RSA 加密一起拒掉。过度拒绝也是可观测的差异：真实 TEE 不会拒绝自己钥匙
        // 本来就能做的操作，一个"什么都拒"的实现本身就是特征。
        if (algo == Algorithm.EC && (purpose == KeyPurpose.ENCRYPT || purpose == KeyPurpose.DECRYPT)
        ) {
            return KeystoreErrorCodes.unsupportedPurpose
        }
        if (algo == Algorithm.RSA && purpose == KeyPurpose.AGREE_KEY) {
            return KeystoreErrorCodes.unsupportedPurpose
        }
        return null
    }

    private fun checkPurpose(keyParams: KeyMintAttestation, purpose: Int): Int? {
        if (purpose == KeyPurpose.WRAP_KEY) return KeystoreErrorCodes.incompatiblePurpose
        if (purpose !in keyParams.purpose) return KeystoreErrorCodes.incompatiblePurpose
        return null
    }

    private fun checkOperationAuthorizations(
        keyParams: KeyMintAttestation,
        opParams: KeyMintAttestation,
    ): Int? {
        if (opParams.blockMode.any { it !in keyParams.blockMode }) {
            return KeystoreErrorCodes.incompatibleBlockMode
        }
        if (opParams.padding.any { it !in keyParams.padding }) {
            return KeystoreErrorCodes.incompatiblePaddingMode
        }
        if (opParams.digest.any { it !in keyParams.digest }) {
            return KeystoreErrorCodes.incompatibleDigest
        }
        if (opParams.rsaOaepMgfDigest.any { it !in keyParams.rsaOaepMgfDigest }) {
            return KeystoreErrorCodes.incompatibleDigest
        }

        if (keyParams.algorithm == Algorithm.AES && opParams.blockMode.contains(BlockMode.GCM)) {
            val requestedMacLength = opParams.minMacLength
            val keyMinMacLength = keyParams.minMacLength
            if (
                requestedMacLength != null &&
                    keyMinMacLength != null &&
                    requestedMacLength < keyMinMacLength
            ) {
                return KeystoreErrorCodes.invalidMacLength
            }
        }

        if (
            keyParams.algorithm == Algorithm.RSA &&
                opParams.padding.contains(PaddingMode.RSA_OAEP) &&
                opParams.digest.isEmpty()
        ) {
            return KeystoreErrorCodes.incompatibleDigest
        }

        return null
    }

    private fun checkTemporalValidity(keyParams: KeyMintAttestation, purpose: Int): Int? {
        val now = System.currentTimeMillis()

        keyParams.activeDateTime?.let { activeDate ->
            if (now < activeDate.time) return KeystoreErrorCodes.keyNotYetValid
        }

        keyParams.originationExpireDateTime?.let { expireDate ->
            if (purpose == KeyPurpose.SIGN || purpose == KeyPurpose.ENCRYPT) {
                if (now > expireDate.time) return KeystoreErrorCodes.keyExpired
            }
        }

        keyParams.usageExpireDateTime?.let { expireDate ->
            if (purpose == KeyPurpose.VERIFY || purpose == KeyPurpose.DECRYPT) {
                if (now > expireDate.time) return KeystoreErrorCodes.keyExpired
            }
        }

        return null
    }

    private fun checkCallerNonce(
        keyParams: KeyMintAttestation,
        purpose: Int,
        rawOpParams: Array<KeyParameter>?,
    ): Int? {
        if (purpose != KeyPurpose.SIGN && purpose != KeyPurpose.ENCRYPT) return null
        if (keyParams.callerNonce == true) return null
        if (rawOpParams?.any { it.tag == Tag.NONCE } == true)
            return KeystoreErrorCodes.callerNonceProhibited
        return null
    }
}

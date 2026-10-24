package me.weishu.kernelsu.privilege

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.weishu.kernelsu.Natives

/**
 * APatch 式超级密钥的管理器侧（借鉴自 bmax121/APatch 的 superkey 模型）。
 *
 * 三层职责：
 *  1. 生成与加密存储：密钥随机生成（或用户自定），用 Android Keystore 的
 *     AES/GCM 密钥加密后落 SharedPreferences——明文永不落盘；Keystore 密钥
 *     不可导出，硬件安全模块可用时随 StrongBox。
 *  2. 内核同步：set（改密钥需旧密钥持证）/ auth（守卫生效时给本进程 fd 打
 *     认证标记）/ status；守卫开关走 KSU_FEATURE_SUPERKEY_GUARD。
 *  3. 自动认证：守卫开启后，内核侧策略类 ioctl 需要已认证 fd；本对象提供
 *     [ensureAuthed] 供保存 AppProfile/受限白名单等操作前调用。
 *
 * 语义提醒（与内核 superkey.c 对齐）：
 *  - 内核只存哈希且**掉电即失**——重启后密钥仍在管理器（Keystore），但守卫
 *     处于未武装态，需重新武装（这同时是"忘掉密钥"的恢复路径：重启→未武装）。
 *  - GRANT_ROOT 不受守卫保护：app 级 su 由 per-uid allow list 把关，给 su
 *     协议加密钥位会破坏一切标准 su 客户端。
 */
object SuperkeyStore {

    private const val PREFS = "xec_superkey"
    private const val KEY_CIPHER = "superkey_cipher"
    private const val KEY_IV = "superkey_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xec_superkey_alias"

    // ---- Keystore 加密的本地存储 ----

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                }
            }
        generator.init(spec)
        return generator.generateKey()
    }

    fun generateKey(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** @return 是否已存有本地密钥（不代表内核已设置/守卫已武装）。 */
    fun hasLocal(context: Context): Boolean =
        prefs(context).contains(KEY_CIPHER)

    fun saveLocal(context: Context, key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_CIPHER, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    fun loadLocal(context: Context): String? = runCatching {
        val prefs = prefs(context)
        val enc = Base64.decode(prefs.getString(KEY_CIPHER, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(enc), Charsets.UTF_8)
    }.getOrNull()

    fun clearLocal(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ---- 内核同步 ----

    data class KernelStatus(val set: Boolean, val guardArmed: Boolean)

    fun kernelStatus(): KernelStatus? = runCatching {
        Natives.superkeyStatus()
    }.getOrNull()

    /** 首次设置或更换（更换需旧密钥持证）。 */
    fun setKernelKey(newKey: String, oldKey: String?): Boolean =
        runCatching {
            Natives.setSuperkey(newKey, oldKey, false)
        }.getOrDefault(false)

    /** 清除内核密钥（需当前密钥持证；清除即解除守卫）。 */
    fun clearKernelKey(oldKey: String): Boolean =
        runCatching {
            Natives.setSuperkey("", oldKey, true)
        }.getOrDefault(false)

    /** 守卫武装/解除（走 feature 表；武装时内核要求密钥已设置）。 */
    fun armGuard(armed: Boolean): Boolean =
        runCatching {
            Natives.setSuperkeyGuard(armed)
        }.getOrDefault(false)

    /**
     * 守卫生效期间的策略操作前置认证：用本地密钥给本进程 fd 打认证标记。
     * @return 守卫未武装（无需认证）或认证成功；false = 需要认证但失败。
     */
    fun ensureAuthed(context: Context): Boolean {
        val status = kernelStatus() ?: return false
        if (!status.guardArmed) return true
        val key = loadLocal(context) ?: return false
        return runCatching { Natives.superkeyAuth(key) }.getOrDefault(false)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

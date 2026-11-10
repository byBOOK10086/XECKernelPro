package me.weishu.kernelsu.ui.screen.settings

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * 真·密钥库取证（2026-11-10）。
 *
 * 为什么要自己生成一把带 attestation challenge 的密钥：**这是唯一能证明"这台机器的
 * TEE 到底有没有在工作"的证据**。管理器读配置文件只能知道"引擎想干什么"，读日志只能
 * 知道"引擎说过什么"，只有让 keystore 现场签一张带 KeyDescription 的证书，才能看到
 * 检测方真正会看到的东西：
 *
 *  - `attestationSecurityLevel` = Software(0) / TEE(1) / StrongBox(2)
 *      → 0 表示这张证明是**纯软件伪造**的（引擎在 FORGE 模式给出：TEE 坏了或者目标包
 *        在 target.txt 里带 `!`）；1/2 表示真的走了硬件密钥库。
 *  - `verifiedBootState`（0 Verified / 1 SelfSigned / 2 Unverified / 3 Failed）
 *      → 检测方判"BL 是否解锁"看的就是这一位。属性可以被 resetprop 改，
 *        **这一位改不了**（它在 TEE 里签），所以它才是可信的那一半。
 *  - `deviceLocked`、`osVersion`、`osPatchLevel`
 *      → 前两个是 Play Integrity DEVICE 判定的输入；patchLevel 是厂商安全补丁级别。
 *
 * 这里只做**读**：生成一把临时密钥（alias 固定，测完立刻删），解析证书链里的
 * KeyDescription 扩展（OID 1.3.6.1.4.1.11129.2.1.17）。不写配置、不改任何状态。
 *
 * 挂在 TEESimulator 引擎上时，拿到的正是"被引擎替换后的证明" —— 也就是说用户看到的
 * 就是检测方看到的，不会出现"本地显示 TEE 正常、验机工具却报软件证明"的错位。
 */
object TeeAttestation {

    private const val ALIAS = "xec_tee_probe"
    private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

    /** KeyDescription 的 SecurityLevel 枚举。 */
    object Level {
        const val SOFTWARE = KeyDescription.Level.SOFTWARE
        const val TEE = KeyDescription.Level.TEE
        const val STRONGBOX = KeyDescription.Level.STRONGBOX
        const val UNKNOWN = KeyDescription.Level.UNKNOWN
    }

    /** verifiedBootState 枚举（AOSP VerifiedBootState）。 */
    object BootState {
        const val VERIFIED = KeyDescription.BootState.VERIFIED
        const val SELF_SIGNED = KeyDescription.BootState.SELF_SIGNED
        const val UNVERIFIED = KeyDescription.BootState.UNVERIFIED
        const val FAILED = KeyDescription.BootState.FAILED
        const val UNKNOWN = KeyDescription.BootState.UNKNOWN
    }

    data class Result(
        val ok: Boolean,
        val backend: String,
        val securityLevel: Int,
        val keymasterLevel: Int,
        val verifiedBootState: Int,
        val deviceLocked: Boolean?,
        val osVersion: Int?,
        val osPatchLevel: Int?,
        val chainSize: Int,
        val chainSubjects: List<String>,
        val googleRoot: Boolean,
        val error: String?,
    ) {
        val levelText: String
            get() = when (securityLevel) {
                Level.SOFTWARE -> "软件证明"
                Level.TEE -> "TEE"
                Level.STRONGBOX -> "StrongBox"
                else -> "未知"
            }

        val bootText: String
            get() = when (verifiedBootState) {
                BootState.VERIFIED -> "已验证"
                BootState.SELF_SIGNED -> "自签名"
                BootState.UNVERIFIED -> "未验证（已解锁）"
                BootState.FAILED -> "验证失败"
                else -> "未知"
            }
    }

    /**
     * 现场要一张硬件证明。TEE（EC）先试；TEE 不可用再试 StrongBox —— 有些机器把
     * 两类密钥库挂在不同的 KeyMint 实例上，一个坏另一个还好。
     */
    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        val tee = tryProbe(strongBox = false)
        if (tee.ok) return@withContext tee
        val sb = tryProbe(strongBox = true)
        when {
            sb.ok -> sb
            // 两次都失败：把 TEE 的报错留下（StrongBox 多半只是"这台机器没有"）。
            tee.error?.contains("StrongBox", ignoreCase = true) == true -> sb
            else -> tee
        }
    }

    private fun tryProbe(strongBox: Boolean): Result = try {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(ByteArray(32).also { SecureRandom().nextBytes(it) })
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
        val chain = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getCertificateChain(ALIAS) as? Array<*>)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
        } finally {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
            }
        }
        parse(strongBox, chain)
    } catch (e: StrongBoxUnavailableException) {
        failure(strongBox, "这台机器没有 StrongBox（${e.javaClass.simpleName}）")
    } catch (e: Throwable) {
        failure(strongBox, "${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
    }

    private fun failure(strongBox: Boolean, reason: String) = Result(
        ok = false,
        backend = if (strongBox) "StrongBox" else "TEE",
        securityLevel = Level.UNKNOWN,
        keymasterLevel = Level.UNKNOWN,
        verifiedBootState = BootState.UNKNOWN,
        deviceLocked = null,
        osVersion = null,
        osPatchLevel = null,
        chainSize = 0,
        chainSubjects = emptyList(),
        googleRoot = false,
        error = reason,
    )

    private fun parse(strongBox: Boolean, chain: List<X509Certificate>): Result {
        if (chain.isEmpty()) return failure(strongBox, "keystore 没有返回证书链")
        val leaf = chain.first()
        val desc = readKeyDescription(leaf)
        val subjects = chain.mapNotNull { it.subjectX500Principal?.name }
        val googleRoot = chain.any { cert ->
            val dn = cert.subjectX500Principal?.name.orEmpty()
            dn.contains("Google Hardware Attestation Root", ignoreCase = true) ||
                dn.contains("O=Google", ignoreCase = true)
        }
        return Result(
            ok = true,
            backend = if (strongBox) "StrongBox" else "TEE",
            securityLevel = desc?.securityLevel ?: Level.UNKNOWN,
            keymasterLevel = desc?.keymasterLevel ?: Level.UNKNOWN,
            verifiedBootState = desc?.verifiedBootState ?: BootState.UNKNOWN,
            deviceLocked = desc?.deviceLocked,
            osVersion = desc?.osVersion,
            osPatchLevel = desc?.osPatchLevel,
            chainSize = chain.size,
            chainSubjects = subjects,
            googleRoot = googleRoot,
            error = if (desc == null) {
                "证书里没有 KeyDescription 扩展（1.3.6.1.4.1.11129.2.1.17）：" +
                    "这张证不是硬件密钥库签出来的"
            } else {
                null
            },
        )
    }

    // ------------------------------------------------------------------ KeyDescription

    /**
     * 从叶子证书里取出 KeyDescription。解析本体在 [KeyDescription]（纯 Kotlin，可单测）。
     *
     * `getExtensionValue()` 返回的是 **DER OCTET STRING 包着 KeyDescription**（X.509
     * 扩展的规矩），所以要先剥一层 OCTET STRING 再交给解析器。
     */
    private fun readKeyDescription(cert: X509Certificate): KeyDescription.Parsed? = try {
        val raw = cert.getExtensionValue(KEY_DESCRIPTION_OID) ?: return null
        val octets = KeyDescription.unwrapOctetString(raw) ?: return null
        KeyDescription.parse(octets)
    } catch (_: Throwable) {
        null
    }

    /** 给 UI 用的一句话结论。 */
    fun summary(result: Result, keyboxTrusted: Boolean?): String {
        if (!result.ok) return "取不到硬件证明：${result.error ?: "未知原因"}"
        val teePart = when (result.securityLevel) {
            Level.SOFTWARE -> "软件证明（TEE 未参与）"
            Level.TEE -> "硬件 TEE 证明"
            Level.STRONGBOX -> "StrongBox 证明"
            else -> "安全级别未知"
        }
        val kb = when (keyboxTrusted) {
            true -> "keybox 可信"
            false -> "keybox 不可信/无效"
            null -> "keybox 未知"
        }
        val boot = when (result.verifiedBootState) {
            BootState.VERIFIED -> "锁已回锁"
            BootState.UNVERIFIED, BootState.SELF_SIGNED -> "BL 呈解锁态"
            BootState.FAILED -> "验证失败"
            else -> "锁态未知"
        }
        return "$teePart · $boot · $kb"
    }

    /** 只用于日志/诊断面板，判断当前系统版本是否支持我们探测的属性。 */
    fun apiLevel(): Int = Build.VERSION.SDK_INT
}

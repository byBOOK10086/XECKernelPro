package me.weishu.kernelsu.ui.screen.settings

/**
 * KeyMint `KeyDescription`（X.509 扩展 OID 1.3.6.1.4.1.11129.2.1.17）的极简解析器。
 *
 * 单独成一个**纯 Kotlin**（无 Android 依赖）文件，是为了能脱离设备单独跑测试 ——
 * 这一段解析错了，TEE 面板就会给出一个"看起来很确定但其实错"的结论：
 * 把软件伪造读成硬件证明、把未锁定的机器读成已回锁。所以它必须在本地就能被喂
 * 构造好的 DER 验证（见 `_scratch/kd_test` 的 harness）。
 *
 * ```text
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion       INTEGER,
 *   attestationSecurityLevel ENUMERATED,   -- 0 Software / 1 TEE / 2 StrongBox
 *   keymasterVersion         INTEGER,
 *   keymasterSecurityLevel   ENUMERATED,
 *   attestationChallenge     OCTET STRING,
 *   uniqueId                 OCTET STRING,
 *   softwareEnforced         AuthorizationList,
 *   teeEnforced              AuthorizationList
 * }
 * AuthorizationList = SEQUENCE of context-tagged values; 704=rootOfTrust, 705=osVersion,
 *                     706=osPatchLevel；rootOfTrust 内部 [0]=verifiedBootState, [1]=deviceLocked
 * ```
 *
 * **设计要点（踩过的坑）**：每个 [Der] 节点自己持有"它被解析时的那段字节"，取值和取
 * 子节点都不需要再传数组。早期版本让调用方传数组，而节点的偏移量其实是相对**子切片**
 * 算的 —— 于是 `fields[1].bytesOf(der)` 少读了一个 SEQUENCE 头，把 ENUMERATED 的
 * **tag 字节** 0x0A 当成了安全级别（结果永远是 10）。这种事只有构造数据跑一遍才抓得到。
 */
internal object KeyDescription {

    object Level {
        const val SOFTWARE = 0
        const val TEE = 1
        const val STRONGBOX = 2
        const val UNKNOWN = -1
    }

    object BootState {
        const val VERIFIED = 0
        const val SELF_SIGNED = 1
        const val UNVERIFIED = 2
        const val FAILED = 3
        const val UNKNOWN = -1
    }

    data class Parsed(
        val securityLevel: Int,
        val keymasterLevel: Int,
        val verifiedBootState: Int,
        val deviceLocked: Boolean?,
        val osVersion: Int?,
        val osPatchLevel: Int?,
    )

    private const val TAG_OCTET_STRING = 4
    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCH_LEVEL = 706
    private const val TAG_BOOT_STATE = 0
    private const val TAG_DEVICE_LOCKED = 1

    /**
     * X.509 扩展的 `getExtensionValue()` 外面还包着一层 OCTET STRING，先剥掉。
     * 返回 null 表示结构不认识（调用方按"拿不到证明"处理，不要猜）。
     */
    fun unwrapOctetString(extensionValue: ByteArray): ByteArray? {
        val nodes = Der.parse(extensionValue)
        if (nodes.size != 1) return null
        if (nodes[0].tag != TAG_OCTET_STRING) return null
        // OCTET STRING 是**原始类型**：要取的是 value()，不是 content()（后者只对构造类型有效）。
        return nodes[0].value()
    }

    /** [der] 是整个 KeyDescription SEQUENCE 的字节（已剥掉外层 OCTET STRING）。 */
    fun parse(der: ByteArray): Parsed? = try {
        val top = Der.parse(der)
        if (top.isEmpty()) return null
        val fields = top[0].children()
        if (fields.isEmpty()) return null
        var securityLevel = Level.UNKNOWN
        var keymasterLevel = Level.UNKNOWN
        if (fields.size >= 4) {
            securityLevel = Der.smallInt(fields[1].value()) ?: Level.UNKNOWN
            keymasterLevel = Der.smallInt(fields[3].value()) ?: Level.UNKNOWN
        }
        var bootState = BootState.UNKNOWN
        var locked: Boolean? = null
        var osVersion: Int? = null
        var osPatch: Int? = null
        // 第 7、8 个元素才是两个 AuthorizationList（前 6 个是版本/级别/挑战/唯一 ID）。
        for (i in 6 until fields.size) {
            for (item in fields[i].children()) {
                when (item.tag) {
                    TAG_ROOT_OF_TRUST -> {
                        for (sub in item.children()) {
                            when (sub.tag) {
                                TAG_BOOT_STATE -> bootState =
                                    Der.smallInt(sub.value()) ?: BootState.UNKNOWN
                                TAG_DEVICE_LOCKED -> locked = Der.bool(sub.value())
                            }
                        }
                    }
                    TAG_OS_VERSION -> osVersion = Der.integer(item.value())
                    TAG_OS_PATCH_LEVEL -> osPatch = Der.integer(item.value())
                }
            }
        }
        Parsed(securityLevel, keymasterLevel, bootState, locked, osVersion, osPatch)
    } catch (_: Throwable) {
        null
    }

    /**
     * 极简 DER 走查器：只读 tag / 长度 / 子节点。
     *
     * 每个节点持有自己被解析时的那段 `src`，所以 [value] / [children] 都不需要参数 ——
     * 这就是"偏移量相对谁"这个坑的唯一可靠解法。
     */
    internal class Der private constructor(
        val tag: Int,
        private val src: ByteArray,
        private val valueStart: Int,
        private val valueEnd: Int,
        private val constructed: Boolean,
    ) {
        /** 本节点的值字节。 */
        fun value(): ByteArray = src.copyOfRange(valueStart, valueEnd)

        /** 构造类型节点的内容；原始类型返回空数组（调用方无需区分 null/空）。 */
        fun content(): ByteArray? = if (constructed) src.copyOfRange(valueStart, valueEnd) else null

        /** 子节点；原始类型返回空列表。 */
        fun children(): List<Der> = content()?.let { Der.parse(it) } ?: emptyList()

        companion object {
            fun parse(src: ByteArray, from: Int = 0, to: Int = src.size): List<Der> {
                val out = ArrayList<Der>()
                var i = from
                while (i < to) {
                    val first = src[i].toInt() and 0xFF
                    var cursor = i + 1
                    var tagNumber = first and 0x1F
                    if (tagNumber == 0x1F) {
                        tagNumber = 0
                        while (cursor < to) {
                            val b = src[cursor].toInt() and 0xFF
                            cursor++
                            tagNumber = (tagNumber shl 7) or (b and 0x7F)
                            if (b and 0x80 == 0) break
                        }
                    }
                    if (cursor >= to) break
                    val lenByte = src[cursor].toInt() and 0xFF
                    cursor++
                    val length: Int
                    if (lenByte and 0x80 == 0) {
                        length = lenByte
                    } else {
                        val n = lenByte and 0x7F
                        if (n <= 0 || n > 4 || cursor + n > to) break
                        var value = 0
                        for (k in 0 until n) {
                            value = (value shl 8) or (src[cursor + k].toInt() and 0xFF)
                        }
                        cursor += n
                        length = value
                    }
                    if (length < 0 || cursor + length > to) break
                    out += Der(tagNumber, src, cursor, cursor + length, first and 0x20 != 0)
                    i = cursor + length
                }
                return out
            }

            fun smallInt(bytes: ByteArray): Int? {
                if (bytes.isEmpty() || bytes.size > 4) return null
                var v = 0
                for (b in bytes) v = (v shl 8) or (b.toInt() and 0xFF)
                return v
            }

            fun integer(bytes: ByteArray): Int? = smallInt(bytes)

            fun bool(bytes: ByteArray): Boolean? =
                if (bytes.isEmpty()) null else bytes[0].toInt() != 0
        }
    }
}

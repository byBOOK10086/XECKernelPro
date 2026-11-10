package me.weishu.kernelsu.ui.screen.settings

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 极简 tar **只读**扫描器：给模块备份/恢复用，不依赖任何 Android API（可以直接跑 JVM 单测）。
 *
 * 为什么要自己写：恢复别人的 tar 是"以 root 身份往磁盘写文件"，条目名直接决定落点，
 * 所以**必须**在解包前用我们自己的解析器核一遍 —— 外壳 `tar -tf` 的输出不可信（有换行
 * 注入、有 PAX/GNU 扩展头覆盖名字的玩法）。
 *
 * 这是 2026-11-09 那版 `scanTar()` 的重写版本，改掉了五个真问题：
 *
 *  1. **`./` 前缀**：`tar -cf x.tar -C /data/adb/modules .` 存进去的名字是 `./mod_a/`。
 *     旧代码 `substringBefore('/')` 拿到的永远是 `"."`，随后被 `!= "."` 过滤掉 ⇒
 *     `top` 恒为空 ⇒ **恢复自己的备份也 100% 报"没有模块目录"**。现在统一规范化。
 *  2. **PAX `path=` 覆盖**：`x` 扩展头可以给下一条目改名（GNU tar 认，我们旧的只跳过
 *     载荷），于是"头里叫 mod_a/module.prop、实际写到 ../../etc/passwd"。现在解析
 *     PAX 记录并把 `path` / `linkpath` 一起判。
 *  3. **`K`（GNU longlink）语义**：`K` 给的是下一条目的**链接目标**，不是名字；旧的
 *     把它赋给 pendingName ⇒ 软链接校验拿错了字符串。
 *  4. **其它条目类型**：字符/块设备、FIFO、sparse(`S`) 一律拒绝，不做"看起来无害就放行"。
 *  5. **严格 UTF-8**：非法字节不再被替换成 U+FFFD 蒙过去，直接判整个归档不可信。
 */
internal object TarScan {

    /**
     * 顶层目录名 = 模块 id。
     *
     * **必须和 ksud 的校验规则一字不差**（`userspace/ksud/src/module.rs:115`：
     * `^[a-zA-Z][a-zA-Z0-9._-]+$`，首字符必须是字母、长度至少 2、**没有上限**）。
     * 旧版这里放宽成 `[A-Za-z0-9._-]{1,64}`，于是 `1foo`、`_bar` 这种目录能恢复成功，
     * 却在之后 `ksud module uninstall/enable/action` 里被判非法 —— 模块在，但管理器
     * 再也管不动它；反过来若给它加长度上限，又会把 ksud 认得的超长 id 判成非法。
     * （条目名本身的长度另有 [MAX_NAME] 兜底，那是防畸形归档，不是模块 id 规则。）
     */
    val MODULE_ID = Regex("^[A-Za-z][A-Za-z0-9._-]+$")

    /**
     * 按 ksud 的 `parse_module_prop()` 语义取 `id`：逐行 `trim()` → 跳过空行与
     * `#`/`!` 开头 → `split_once('=')` → key/value 各自 `trim()` → **后出现的覆盖先出现的**。
     *
     * 这些细节必须对齐：`sed 's/^id=//'` 会把 `id = x`（等号旁有空格）漏掉、把
     * `#id=evil` 当成 id、遇到重复 `id=` 行时取第一个 —— 而 ksud 取最后一个。
     * 两边不一致的后果是"管理器校验通过、ksud 却把模块认成另一个 id"。
     */
    fun modulePropId(content: String): String? {
        var id: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            if (key != "id") continue
            id = line.substring(eq + 1).trim()
        }
        return id
    }

    /**
     * 归档里的一个条目。
     * [name] 已规范化：去掉 `./` 前缀、折叠重复 `/`、去掉结尾 `/`；根条目是空串。
     */
    data class Entry(val name: String, val type: Char, val linkName: String)

    private const val MAX_ENTRIES = 20_000
    private const val MAX_SIZE = 8L * 1024 * 1024 * 1024
    private const val MAX_META = 64 * 1024
    private const val MAX_NAME = 4096

    /**
     * 允许出现在 PAX 扩展头里的键。
     *
     * 只有"纯元数据"的键能进来：**`size` / `GNU.sparse.*` / `SCHILY.*` / 未知键一律拒绝**。
     * 原因：扫描器是按 ustar 头里的 size 往后跳数据的，而设备上的 tar 可能按 PAX 的
     * `size` 解释 —— 两边对"下一条目从哪里开始"的理解一不一致，校验过的条目序列就不是
     * 实际解包的序列。这类键在正常的模块归档里根本不会出现，fail closed 没有代价。
     */
    private val PAX_META_KEYS = setOf(
        "mtime", "atime", "ctime", "uid", "gid", "uname", "gname", "comment", "charset",
    )

    /**
     * 读条目表。**任何一处不合规就返回 null**（调用方当成"这个备份不能用"处理），
     * 不做"跳过坏条目继续解"的容错 —— 半个模块落地比不落地危险。
     *
     * 除了"名字本身要相对、不含 `..`"，这里还维护一张**链接图**：先建出来的软链接不能
     * 成为后续条目的祖先（`aa -> .` 再写 `aa/etc/x` 就能转义），也不能和已有条目
     * 同名不同类型（同一路径既是目录又是链接）。仅靠词法深度判断是不够的 ——
     * 词法上安全的 `aa/bb` 在文件系统里可能是穿过链接的路径。
     */
    fun readEntries(file: File): List<Entry>? {
        if (!file.isFile || file.length() < 512) return null
        val out = ArrayList<Entry>()
        var pendingName: String? = null
        var pendingLink: String? = null
        // 路径 -> 类型字符；用来发现"同名不同类型"和"链接成为别人祖先"。
        val seen = HashMap<String, Char>()
        val symlinks = HashSet<String>()
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            val header = ByteArray(512)
            var entries = 0
            while (true) {
                if (!readFully(input, header)) return null
                if (isZeroBlock(header)) break
                if (++entries > MAX_ENTRIES) return null

                val stored = fieldOctal(header, 148, 8) ?: return null
                var sum = 0L
                for (i in header.indices) {
                    sum += if (i in 148..155) 0x20L else (header[i].toInt() and 0xFF).toLong()
                }
                if (sum != stored) return null

                val size = fieldOctal(header, 124, 12) ?: return null
                if (size < 0 || size > MAX_SIZE) return null
                val type = (header[156].toInt() and 0xFF).toChar()

                when (type) {
                    // GNU 长名字 / 长链接目标：载荷是下一条目的名字 / 目标。
                    'L', 'K' -> {
                        val payload = readMeta(input, size) ?: return null
                        if (type == 'L') {
                            val name = normalize(payload) ?: return null
                            if (name.isEmpty()) return null
                            pendingName = name
                        } else {
                            pendingLink = payload.trimEnd('\u0000')
                        }
                    }
                    // 扩展头：`x` 是下一条目专属；`size`/sparse 等影响解包语义的键直接拒绝。
                    'x' -> {
                        val records = readPax(input, size) ?: return null
                        for ((key, value) in records) {
                            when {
                                key == "path" -> {
                                    val name = normalize(value) ?: return null
                                    if (name.isEmpty()) return null
                                    pendingName = name
                                }
                                key == "linkpath" -> pendingLink = value
                                key in PAX_META_KEYS -> Unit
                                else -> return null
                            }
                        }
                    }
                    // 全局扩展头会改变"后面所有条目"的解释，而我们不实现它的语义：拒绝。
                    'g' -> return null

                    // 只接受普通文件、目录、相对软链接。
                    '0', '\u0000', '7', '5', '2' -> {
                        val raw = ustarName(header) ?: return null
                        val name = normalize(pendingName ?: raw) ?: return null
                        val link = pendingLink ?: linkNameOf(header)
                        pendingName = null
                        pendingLink = null
                        if (!skipPayload(input, size)) return null
                        if (name.isEmpty()) {
                            // 归档根目录（`.` / `./`）：只有目录才合法。
                            if (type != '5') return null
                            continue
                        }
                        // 祖先不能是已经建出来的软链接（否则这条目的落点由链接决定）。
                        var slash = name.indexOf('/')
                        while (slash > 0) {
                            if (name.substring(0, slash) in symlinks) return null
                            slash = name.indexOf('/', slash + 1)
                        }
                        val previousType = seen[name]
                        if (previousType != null && previousType != type) return null
                        seen[name] = type
                        if (type == '2') {
                            if (!isSafeLink(name, link)) return null
                            // 新链接不能是已有条目的祖先（解包顺序不同结果不同，直接拒）。
                            if (seen.keys.any { it.length > name.length && it.startsWith("$name/") }) {
                                return null
                            }
                            // 链接目标本身也不能穿过另一个软链接。
                            val resolved = resolveLink(name, link) ?: return null
                            var cut = resolved.indexOf('/')
                            while (cut > 0) {
                                if (resolved.substring(0, cut) in symlinks) return null
                                cut = resolved.indexOf('/', cut + 1)
                            }
                            symlinks += name
                        } else if (link.isNotEmpty()) {
                            return null
                        }
                        out += Entry(name, type, link)
                    }

                    // 硬链接（目标是任意路径）、设备节点、FIFO、sparse 等：一律不收。
                    else -> return null
                }
            }
        }
        return out
    }

    /**
     * 把软链接目标按词法解析成归档内的相对路径（`..` 到顶即失败）。
     * 只用于"目标是否会穿过另一个链接"的判断，不碰文件系统。
     */
    private fun resolveLink(entry: String, target: String): String? {
        val parts = ArrayList<String>()
        val dir = entry.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) parts += dir.split('/').filter { it.isNotEmpty() }
        for (part in target.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    /**
     * 归档里能当模块用的顶层目录（= 模块 id），已排序去重。
     *
     * 只认"有目录条目"或"底下有文件"的顶层名字；顶层散文件不算模块。
     * 以 `.` 开头的目录跳过 —— 我们自己的暂存目录就长这样，别把暂存区当模块恢复。
     */
    fun moduleIds(entries: List<Entry>): List<String> {
        val ids = LinkedHashSet<String>()
        for (entry in entries) {
            val slash = entry.name.indexOf('/')
            val top = if (slash < 0) entry.name else entry.name.substring(0, slash)
            if (top.isEmpty() || top.startsWith(".")) continue
            if (!MODULE_ID.matches(top)) continue
            if (slash >= 0 || entry.type == '5') ids += top
        }
        return ids.sorted()
    }

    // ------------------------------------------------------------------ 名字 / 路径

    /**
     * 规范化条目名。返回 null = 这个归档不可信；返回空串 = 归档根。
     *
     * 拒绝：绝对路径、`..` 段、反斜杠、控制字符、超长、非法 UTF-8 替换符。
     */
    fun normalize(raw: String): String? {
        var name = raw.trimEnd('\u0000')
        if (name.length > MAX_NAME) return null
        for (ch in name) {
            if (ch < ' ' || ch == '\u007f') return null
            if (ch == '\\') return null
            if (ch == '\uFFFD') return null
        }
        if (name.startsWith('/')) return null
        // tar 用 `./` 表示"相对当前目录"，可以连续出现。
        while (name.startsWith("./")) name = name.substring(2)
        if (name == "." || name.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (part in name.split('/')) {
            when {
                part.isEmpty() || part == "." -> continue
                part == ".." -> return null
                else -> parts += part
            }
        }
        if (parts.isEmpty()) return ""
        return parts.joinToString("/")
    }

    /** 相对软链接不能把落点带出归档根。 */
    fun isSafeLink(entry: String, target: String): Boolean {
        if (target.isEmpty() || target.length > MAX_NAME) return false
        if (target.startsWith('/') || target.startsWith('\\')) return false
        for (ch in target) {
            if (ch < ' ' || ch == '\u007f' || ch == '\\' || ch == '\uFFFD') return false
        }
        // 链接所在的目录深度（entry 本身不计入）。
        var depth = entry.split('/').count { it.isNotEmpty() } - 1
        for (part in target.split('/')) {
            when {
                part.isEmpty() || part == "." -> continue
                part == ".." -> {
                    depth -= 1
                    if (depth < 0) return false
                }
                else -> depth += 1
            }
        }
        return true
    }

    // ------------------------------------------------------------------ 底层读取

    private fun ustarName(header: ByteArray): String? {
        val name = fieldString(header, 0, 100) ?: return null
        val prefix = fieldString(header, 345, 155) ?: return null
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun linkNameOf(header: ByteArray): String =
        fieldString(header, 157, 100).orEmpty()

    /**
     * ustar 定长字段：到第一个 NUL 为止，再严格 UTF-8 解码。
     *
     * **不能**整段解码 —— 定长字段后面全用 NUL 填充，整段解出来的字符串里带着一串
     * `\u0000`，会把合法归档判成"名字里有控制字符"。真实 bsdtar/GNU tar 的归档
     * 每一个条目都会撞上这一点。
     */
    private fun fieldString(header: ByteArray, offset: Int, length: Int): String? {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return decodeUtf8(header, offset, end - offset)
    }

    private fun isZeroBlock(header: ByteArray): Boolean {
        for (b in header) if (b.toInt() != 0) return false
        return true
    }

    private fun readFully(input: DataInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun paddedSize(size: Long): Long = (size + 511L) / 512L * 512L

    private fun skipRaw(input: DataInputStream, bytes: Long): Boolean {
        var left = bytes
        val sink = ByteArray(8192)
        while (left > 0) {
            val want = minOf(left, sink.size.toLong()).toInt()
            val n = input.read(sink, 0, want)
            if (n <= 0) return false
            left -= n
        }
        return true
    }

    private fun skipPayload(input: DataInputStream, size: Long): Boolean =
        skipRaw(input, paddedSize(size))

    private fun readMeta(input: DataInputStream, size: Long): String? {
        if (size <= 0 || size > MAX_META) return null
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) return null
        if (!skipRaw(input, paddedSize(size) - size)) return null
        return decodeUtf8(buf, 0, buf.size)
    }

    /** PAX 记录：`<长度> <键>=<值>\n`，长度是**字节数**（含自身），所以必须按字节解析。 */
    private fun readPax(input: DataInputStream, size: Long): List<Pair<String, String>>? {
        if (size <= 0 || size > MAX_META) return null
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) return null
        if (!skipRaw(input, paddedSize(size) - size)) return null
        val records = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < buf.size) {
            if (buf[i] == 0.toByte()) break
            var sp = i
            while (sp < buf.size && buf[sp] != ' '.code.toByte()) sp++
            if (sp >= buf.size || sp == i) return null
            val len = String(buf, i, sp - i, StandardCharsets.US_ASCII).toIntOrNull() ?: return null
            if (len <= sp - i + 2) return null
            if (i + len > buf.size) return null
            if (buf[i + len - 1] != '\n'.code.toByte()) return null
            var eq = sp + 1
            while (eq < i + len - 1 && buf[eq] != '='.code.toByte()) eq++
            if (eq >= i + len - 1) return null
            val key = String(buf, sp + 1, eq - (sp + 1), StandardCharsets.US_ASCII)
            val value = decodeUtf8(buf, eq + 1, (i + len - 1) - (eq + 1)) ?: return null
            records += key to value
            i += len
        }
        return records
    }

    /**
     * 严格 UTF-8 解码：非法序列返回 null（不用替换符糊过去）。
     *
     * 条目名合法与否会决定"能不能往这个路径写文件"，这里不能有"差不多"。
     */
    private fun decodeUtf8(buf: ByteArray, offset: Int, length: Int): String? {
        if (length <= 0) return ""
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(buf, offset, length)).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 八进制字段（tar 老格式）；base-256 编码、非八进制字符一律判不可信。 */
    private fun fieldOctal(header: ByteArray, offset: Int, length: Int): Long? {
        if (((header[offset].toInt() and 0xFF) and 0x80) != 0) return null
        var i = offset
        val limit = offset + length
        var value = 0L
        var digits = 0
        while (i < limit) {
            val c = header[i].toInt() and 0xFF
            when {
                c == 0 || c == ' '.code -> if (digits == 0) {
                    i++
                    continue
                } else {
                    break
                }
                c < '0'.code || c > '7'.code -> return null
                else -> {
                    value = value * 8 + (c - '0'.code)
                    digits++
                    i++
                }
            }
        }
        return value
    }
}

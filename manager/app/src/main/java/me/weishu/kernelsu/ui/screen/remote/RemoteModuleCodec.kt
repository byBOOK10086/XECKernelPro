package me.weishu.kernelsu.ui.screen.remote

import android.util.Base64
import java.util.Locale
import java.util.zip.CRC32

private fun crc32Hex(payload: ByteArray): String {
    val crc = CRC32()
    crc.update(payload)
    return String.format(Locale.US, "%08X", crc.value)
}

private fun xorWithKey(data: ByteArray, key: String): ByteArray =
    ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.length].code).toByte() }

/**
 * 远程模块助手的链接编解码。
 *
 * 格式（V2，一条码可携带多条链接）：`XEC2.<CRC32-8位大写HEX>.<Base64URL(XOR(links, key))>`
 * 载荷为多条链接以 `\n` 相连；`XEC1.` 为历史单链接格式，仅保留解码兼容。
 * 服务器上传页（XEC 模块仓库）用同一套算法生成加密码，两端互通。
 *
 * CRC32 不是加密，这里做的是「混淆 + 完整性校验」：链接明文不直接出现在码里，
 * 接收端解码后重算 CRC32 与码内校验段比对，能挡住复制不全/转发出错的情况。
 */
object RemoteModuleCodec {
    private const val PREFIX_V1 = "XEC1"
    private const val PREFIX_V2 = "XEC2"
    private const val OBF_KEY = "XecRemoteModule1"
    private const val CRC_LEN = 8

    /** 把一组直链编码成一条整合加密码（批量上传/多条外链共用）。 */
    fun encodeAll(links: List<String>): String {
        val text = links.map { it.trim() }.filter { it.isNotEmpty() }
        require(text.isNotEmpty()) { "empty links" }
        val payload = text.joinToString("\n").toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(
            xorWithKey(payload, OBF_KEY),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$PREFIX_V2.${crc32Hex(payload)}.$encoded"
    }

    /**
     * 解出原始链接列表。以 http 开头的输入按原始链接直接放行（便于直接粘贴，
     * 不走加密串）；`XEC2.` 解出多条链接，`XEC1.` 解出单条；CRC32 不符视为
     * 复制不完整。
     *
     * 解码健壮性：加密码本体只可能含 `[A-Za-z0-9._-]`，清洗时把聊天工具夹带的
     * 全角空格、零宽字符、引号与"来自 xx 的分享"之类装饰**全部**剔除——过去一个
     * 不可见字符就能让整条码报废；Base64 先按 URL_SAFE 解，失败再退标准字母表，
     * 兼容旧端用 `+/` 生成的码。
     */
    fun decodeAll(code: String): Result<List<String>> {
        // 纯链接直通：只清普通空白，保住 URL 里的冒号、斜杠等字符
        val loose = code.trim()
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
        if (loose.startsWith("http", ignoreCase = true)) {
            return Result.success(listOf(loose))
        }
        val cleaned = loose.filter {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it == '.' || it == '_' || it == '-' || it == '='
        }
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("unrecognized share code"))
        }
        val prefix = when {
            cleaned.startsWith("$PREFIX_V2.") -> PREFIX_V2
            cleaned.startsWith("$PREFIX_V1.") -> PREFIX_V1
            else -> return Result.failure(IllegalArgumentException("unrecognized share code"))
        }
        val body = cleaned.substring(prefix.length + 1)
        val dot = body.indexOf('.')
        if (dot != CRC_LEN) {
            return Result.failure(IllegalArgumentException("unrecognized share code"))
        }
        val crcPart = body.substring(0, dot)
        val payload = body.substring(dot + 1)
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val xored = try {
            try {
                Base64.decode(padded, Base64.URL_SAFE)
            } catch (first: IllegalArgumentException) {
                Base64.decode(padded, Base64.DEFAULT)
            }
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("unrecognized share code"))
        }
        val plain = xorWithKey(xored, OBF_KEY)
        if (!crc32Hex(plain).equals(crcPart, ignoreCase = true)) {
            return Result.failure(IllegalStateException("share code checksum mismatch"))
        }
        val text = String(plain, Charsets.UTF_8)
        val links = if (prefix == PREFIX_V1) {
            listOf(text)
        } else {
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return Result.success(links)
    }
}

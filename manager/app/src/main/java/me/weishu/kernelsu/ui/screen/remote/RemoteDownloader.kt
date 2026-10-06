package me.weishu.kernelsu.ui.screen.remote

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网盘「分享页」域名特征——这类链接打开的是网页（要登录/要跳转），
 * 程序拿到手只会是一坨 HTML，不是文件。提前识别，直接给出人话报错。
 */
private val PAN_SHARE_HOSTS = listOf("123pan", "lanzou", "pan.baidu.com", "cloud.189.cn", "caiyun.139.com")

fun isPanSharePage(url: String): Boolean {
    val host = runCatching { URL(url).host }.getOrNull() ?: return false
    return PAN_SHARE_HOSTS.any { host.contains(it) } ||
        Regex("""123\d{3}\.(com|cn)""").containsMatchIn(host)
}

/** 任意外链的直连下载器：带进度回调、500MB 上限、zip 头校验与一次自动重试。 */
object RemoteDownloader {
    private const val MAX_FILE_BYTES: Long = 500L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 16) XECKernelPro/1.0"

    fun downloadTo(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        try {
            downloadOnce(url, target, onProgress)
        } catch (e: IOException) {
            // 一次自动重试：移动网络切换、OpenList 中转抖动造成的超时/连接重置
            // 很常见，不值得让用户手动重来一遍。重试前必须清掉半截文件。
            target.delete()
            downloadOnce(url, target, onProgress)
        }
    }

    private fun downloadOnce(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        runCatching {
            val host = URL(url).host
            conn.setRequestProperty("Referer", "https://$host/")
        }
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("download HTTP $code")
        val total = conn.contentLengthLong
        if (total > MAX_FILE_BYTES) throw IOException("file too large: $total")
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var logged = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > MAX_FILE_BYTES) throw IOException("file too large")
                    out.write(buf, 0, n)
                    if (read - logged >= 2L * 1024 * 1024) {
                        logged = read
                        onProgress(read, if (total > 0) total else 0)
                    }
                }
                onProgress(read, if (total > 0) total else read)
            }
        }
        target.inputStream().use { ins ->
            val head = ByteArray(4)
            val n = ins.read(head)
            val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            if (!isZip) {
                if (n >= 1 && (head[0] == '<'.code.toByte() || head[0] == '{'.code.toByte())) {
                    throw IOException("链接返回的是网页/JSON 而不是文件（需要 zip 的直链）")
                }
                throw IOException("下载内容不是 zip（链接指向的可能不是文件本身）")
            }
        }
    }
}

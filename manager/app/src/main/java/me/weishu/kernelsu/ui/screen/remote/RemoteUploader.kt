package me.weishu.kernelsu.ui.screen.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun sanitizeName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module.zip" }

/**
 * XEC 模块仓库上传通道：服务器 OpenList（:5244）+ 专用上传账号。
 * 该账号权限仅限「写入 /uploads」——不能读仓库其余部分，也不能删除文件，
 * 即使凭据被提取也不会波及已有内容。
 */
object RemoteUploader {
    private const val BASE = "http://42.193.123.248:5244"
    private const val USER = "xupload"
    private const val PASS = "Xup-YRAvmDxs8f45NL87"
    private const val PUBLIC_DIR = "/d/uploads"

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        return uri.lastPathSegment ?: "module.zip"
    }

    /** 上传单个文件到仓库，成功返回公网直链；失败抛 IOException。 */
    fun upload(context: Context, uri: Uri, rawName: String, onProgress: (Long, Long) -> Unit): String =
        upload(context, uri, rawName, onProgress, dir = "")

    /**
     * 上传到 `/uploads` 下的子目录（[dir] 为空 = 直接放 `/uploads`）。
     *
     * 主题包走 `pending`：主题仓库有一个定时任务扫这个目录自动收录，
     * **用户不用去 GitHub 开 issue**（国内手机上 github.com 常常打不开，
     * 那是"上传了但商城一直不出现"的主因）。同一个直链照样能贴进 issue 表单，
     * 两条路都通。
     */
    fun upload(
        context: Context,
        uri: Uri,
        rawName: String,
        onProgress: (Long, Long) -> Unit,
        dir: String,
    ): String {
        val token = login()
        val clean = dir.trim('/').replace(Regex("[^A-Za-z0-9._-]"), "")
        if (clean.isNotEmpty()) ensureDir(token, clean)
        val name = "${System.currentTimeMillis()}_${sanitizeName(rawName)}"
        val remote = if (clean.isEmpty()) "/$name" else "/$clean/$name"
        val encodedPath = URLEncoder.encode(remote, "UTF-8").replace("+", "%20")
        val conn = URL("$BASE/api/fs/put").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("File-Path", encodedPath)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            val total = querySize(context, uri)
            // 先声明 body 长度：HttpURLConnection 不再把整个 zip 缓存在内存里，
            // 进度也不会等到写完全部才跳到 100%；大小拿不到时退回分块传输。
            if (total > 0) conn.setFixedLengthStreamingMode(total) else conn.setChunkedStreamingMode(64 * 1024)
            context.contentResolver.openInputStream(uri)?.use { input ->
                conn.outputStream.use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastMark = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (read - lastMark >= 256 * 1024) {
                            lastMark = read
                            onProgress(read, total)
                        }
                    }
                    onProgress(read, total)
                }
            } ?: throw IOException("cannot open selected file")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                null
            }
            if (code !in 200..299 || json?.optInt("code", -1) != 200) {
                throw IOException(json?.optString("message") ?: "HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
        val publicDir = if (clean.isEmpty()) PUBLIC_DIR else "$PUBLIC_DIR/$clean"
        return "$BASE$publicDir/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    /** 建子目录（已存在时 OpenList 也返回成功）；失败不致命，put 会再报一次。 */
    private fun ensureDir(token: String, dir: String) {
        val conn = URL("$BASE/api/fs/mkdir").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("path", "/$dir").toString().toByteArray())
            }
            conn.responseCode
            runCatching { (conn.inputStream ?: conn.errorStream)?.close() }
        } catch (e: Exception) {
            // 目录已经存在 / 服务器不支持：都不影响后面的 PUT，让它自己去报错。
        } finally {
            conn.disconnect()
        }
    }

    /** 选中文件的大小（拿不到返回 -1）；上传前用它做 500MB 上限判断。 */
    fun querySize(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

    private fun login(): String {
        val conn = URL("$BASE/api/auth/login").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("username", USER).put("password", PASS).toString().toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                null
            }
            if (code !in 200..299 || json?.optInt("code", -1) != 200) {
                throw IOException(json?.optString("message") ?: "HTTP $code")
            }
            return json.getJSONObject("data").getString("token")
        } finally {
            conn.disconnect()
        }
    }
}

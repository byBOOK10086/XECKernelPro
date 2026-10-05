package me.weishu.kernelsu.ui.screen.hidepack

import android.content.Context
import me.weishu.kernelsu.ui.screen.remote.RemoteDownloader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 一键隐藏资源包的云端分发：资源不再内嵌 APK，改从服务器按需下载。
 *
 * 服务端 = OpenList /hidepack/ 目录下的 manifest.json + 若干 zip。manifest
 * 携带每个文件的直链、sha256 与大小；本地缓存在 filesDir/hidepack/，按
 * sha256 命名 —— 版本更新即哈希变化，天然触发重新下载，无需额外元数据。
 */
object HidePackDownloader {
    const val MANIFEST_URL = "http://42.193.123.248:5244/d/hidepack/manifest.json"

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    data class HideFile(val name: String, val url: String, val sha256: String, val size: Long)
    data class HideManifest(val version: Int, val updatedAt: String, val files: List<HideFile>)

    fun cacheDir(context: Context): File = File(context.filesDir, "hidepack")

    fun cachedFile(context: Context, sha256: String): File =
        File(cacheDir(context), sha256.lowercase(Locale.US) + ".zip")

    /** 拉取并解析资源清单。任何异常都以人话 IOException 抛出，调用方直接进日志。 */
    fun fetchManifest(): HideManifest {
        val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("manifest HTTP $code")
        val text = conn.inputStream.use { it.readBytes().decodeUTF8Bounded() }
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("manifest not json")
        }
        val filesJson = obj.optJSONArray("files") ?: JSONArray()
        if (filesJson.length() == 0) throw IOException("manifest has no files")
        val files = (0 until filesJson.length()).map { i ->
            val f = filesJson.getJSONObject(i)
            val name = f.optString("name")
            val url = f.optString("url")
            val sha = f.optString("sha256").lowercase(Locale.US)
            if (name.isEmpty() || !url.startsWith("http://") && !url.startsWith("https://")) {
                throw IOException("manifest bad entry: $name")
            }
            if (!Regex("^[0-9a-f]{64}$").matches(sha)) throw IOException("manifest bad sha256: $name")
            HideFile(name, url, sha, f.optLong("size", -1L))
        }
        return HideManifest(obj.optInt("version", 0), obj.optString("updated_at"), files)
    }

    /**
     * 确保清单里每个文件都在本地缓存且哈希正确；缺/旧则下载。
     * 返回 name → 本地缓存文件的映射。log 输出步骤行，progress 阶段进文件日志。
     */
    fun ensureResources(
        context: Context,
        manifest: HideManifest,
        log: (String) -> Unit,
    ): Map<String, File> {
        cacheDir(context).mkdirs()
        val out = LinkedHashMap<String, File>()
        for (f in manifest.files) {
            val target = cachedFile(context, f.sha256)
            if (target.exists() && target.length() > 0 && sha256Hex(target) == f.sha256) {
                log("✓ ${f.name} (cached)")
                out[f.name] = target
                continue
            }
            log("→ ${f.name}")
            val part = File(cacheDir(context), f.sha256.lowercase(Locale.US) + ".part")
            var lastPct = -10
            RemoteDownloader.downloadTo(f.url, part) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                if (pct >= 0 && pct >= lastPct + 10) {
                    lastPct = pct
                    log("   ${f.name} $pct%")
                }
            }
            val got = sha256Hex(part)
            if (got != f.sha256) {
                part.delete()
                throw IOException("sha256 mismatch: ${f.name}")
            }
            if (f.size > 0 && part.length() != f.size) {
                part.delete()
                throw IOException("size mismatch: ${f.name}")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            log("✓ ${f.name}")
            out[f.name] = target
        }
        return out
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.decodeUTF8Bounded(): String {
        val capped = if (size > 256 * 1024) copyOf(256 * 1024) else this
        return String(capped, Charsets.UTF_8)
    }
}

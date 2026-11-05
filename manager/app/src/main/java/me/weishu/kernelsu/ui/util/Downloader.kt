package me.weishu.kernelsu.ui.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * 计算一个（已经下载到本地的）文件的 sha256，小写十六进制。
 *
 * 模块仓库的索引里带 zip 的 sha256；下载完成后校验一次，中转/CDN 被替换或传输被截断都能当场发现，
 * 而不是把一个来路不明的 zip 交给安装器。读不到文件返回 null（调用方按"无法判定"处理）。
 */
fun sha256Of(context: Context, uri: Uri): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    } ?: return@runCatching null
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/** 同上，但直接对一个本地文件算（主题包、下载到私有目录的产物）。 */
fun sha256Of(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/**
 * @author weishu
 * @date 2023/6/22.
 */
suspend fun download(
    url: String,
    fileName: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {}
) {
    onDownloading()

    val downloadId = DownloadManager.enqueue(
        context = ksuApp,
        url = url,
        fileName = fileName,
        onCompleted = onDownloaded,
    )

    DownloadManager.downloads
        .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
        .first { map ->
            val status = map[downloadId]?.status
            status == DownloadManager.Status.COMPLETED ||
                status == DownloadManager.Status.FAILED
        }
}

/**
 * 我们自己的更新源：官网 OpenList 上的 `version.json`，由 `publish_site.py` 在镜像 APK
 * 时一起写入（`/apk/version.json`）。
 *
 * 之前只有 GitHub 一条路。`api.github.com` 对国内用户经常直接不可达，而失败会被
 * `runCatching` 吞成"没有新版本"，于是首页永远不提示更新——2026-11-05 反馈的
 * "正式版没收到通知"就是这个形态：不是没推送，是检查根本没拿到数据。
 */
private const val SITE_VERSION_URL = "http://42.193.123.248:5244/d/apk/version.json"

private fun checkNewVersionFromSite(): LatestVersionInfo {
    ksuApp.okhttpClient.newCall(Request.Builder().url(SITE_VERSION_URL).build()).execute()
        .use { response ->
            if (!response.isSuccessful) return LatestVersionInfo()
            val json = org.json.JSONObject(response.body.string())
            val versionCode = json.optInt("versionCode")
            // 光有版本号不够：apkUrl 空串时会提示"有更新"、点下载却必然失败（审计 P3）。
            // 两者都在才算一条可用的更新信息。
            val apkUrl = json.optString("apkUrl").trim()
            if (versionCode <= 0 || apkUrl.isEmpty()) return LatestVersionInfo()
            return LatestVersionInfo(
                versionCode = versionCode,
                downloadUrl = apkUrl,
                changelog = json.optString("changelog"),
            )
        }
}

/**
 * 两条路都试一次：官网优先（国内可达），GitHub 那份只额外贡献累计下载量。
 *
 * 任何一条拿到版本号就足以让首页显示"有新版本"，所以单点不可达不再等于"已是最新"。
 */
fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val fromSite = runCatching { checkNewVersionFromSite() }
        .getOrDefault(LatestVersionInfo())
    val fromGithub = runCatching { checkNewVersionFromGithub() }
        .getOrDefault(LatestVersionInfo())
    return when {
        fromSite.versionCode > 0 && fromGithub.downloadCount > 0 ->
            fromSite.copy(downloadCount = fromGithub.downloadCount)
        fromSite.versionCode > 0 -> fromSite
        else -> fromGithub
    }
}

private fun checkNewVersionFromGithub(): LatestVersionInfo {
    // 拉取全部 release（按发布时间倒序，最新在前），用于累计下载量与最新版本信息
    val url = "https://api.github.com/repos/byBOOK10086/XECKernelPro/releases?per_page=100"
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val releases = org.json.JSONArray(response.body.string())

                var totalDownloads = 0L
                var versionCode = 0
                var downloadUrl = ""
                var changelog = ""

                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    val assets = release.optJSONArray("assets") ?: continue

                    for (j in 0 until assets.length()) {
                        totalDownloads += assets.getJSONObject(j).optLong("download_count")
                    }

                    // 取首个包含 apk 的 release 作为版本信息（release 已按最新在前排序）
                    if (downloadUrl.isEmpty()) {
                        changelog = release.optString("body")
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val name = asset.getString("name")
                            if (!name.endsWith(".apk")) {
                                continue
                            }
                            // 兼容 XECKernelPro_<versionName>_<versionCode>-release.apk 以及
                            // 上游 KernelSU_v<ver>_<versionCode>-release.apk 等命名，只取文件名末尾的版本号
                            val regex = Regex("_(\\d+)(?:-release|-debug)?\\.apk$")
                            val matchResult = regex.find(name) ?: continue
                            versionCode = matchResult.groupValues[1].toInt()
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                }

                return LatestVersionInfo(
                    versionCode,
                    downloadUrl,
                    changelog,
                    totalDownloads
                )
            }
    }
    return defaultValue
}

package me.weishu.kernelsu.ui.util

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import okhttp3.Request

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

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
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

package me.weishu.kernelsu.ui.util.module

import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class ModuleDetail(
    val readme: String,
    val readmeHtml: String,
    val latestTag: String,
    val latestTime: String,
    val latestAssetName: String?,
    val latestAssetUrl: String?,
    val releases: List<ReleaseInfo>,
    val homepageUrl: String,
    val sourceUrl: String,
    val url: String
)

data class ReleaseInfo(
    val name: String,
    val tagName: String,
    val publishedAt: String,
    val descriptionHTML: String,
    val assets: List<ReleaseAssetInfo>
)

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val downloadCount: Int,
    /** 索引里的 zip sha256；空串 = 该源没提供。 */
    val sha256: String = "",
)

fun sanitizeVersionString(version: String): String {
    return version.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
}

fun stripTicks(s: String): String {
    val t = s.trim()
    return if (t.startsWith("`") && t.endsWith("`") && t.length >= 2) t.substring(1, t.length - 1) else t
}

/**
 * 官方模块仓库的地址。
 *
 * 官方索引已经搬到 `api/v1`：老的 `https://modules.kernelsu.org/module/<id>.json` 现在返回 404，
 * 于是**任何**模块的详情页都拿不到数据 —— 说明页永远转圈、版本页永远空白，而列表本身照常显示。
 * 两个地址都试（先新后旧），旧地址留着兜底别家镜像/自建站按老协议提供的 JSON。
 */
private const val OFFICIAL_BASE = "https://modules.kernelsu.org"
private const val OFFICIAL_V1 = "$OFFICIAL_BASE/api/v1/modules"
private const val OFFICIAL_LEGACY = "$OFFICIAL_BASE/module"

private fun httpText(url: String): String? = runCatching {
    ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) null else resp.body.string()
    }
}.getOrNull()

fun fetchReleaseDescriptionHtml(moduleId: String, latestTag: String): String? {
    if (!isNetworkAvailable(ksuApp)) return null
    // v1：每个 release 的变更说明在 changelogHtml 里（旧版叫 descriptionHTML）。
    httpText("$OFFICIAL_V1/$moduleId.json")?.let { body ->
        runCatching {
            val releasesArray = JSONObject(body).optJSONArray("releases") ?: return@runCatching null
            var fallbackHtml: String? = null
            for (i in 0 until releasesArray.length()) {
                val r = releasesArray.optJSONObject(i) ?: continue
                val descHtml = r.optString("changelogHtml", "")
                if (fallbackHtml == null && descHtml.isNotBlank()) fallbackHtml = descHtml
                val rname = r.optString("name", r.optString("tag", r.optString("version", "")))
                if (rname == latestTag && descHtml.isNotBlank()) return@runCatching descHtml
            }
            fallbackHtml
        }.getOrNull()?.let { if (it.isNotBlank()) return it }
    }
    // 旧协议兜底。
    val url = "$OFFICIAL_LEGACY/$moduleId.json"
    return runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val body = resp.body.string()
                val obj = JSONObject(body)
                val releasesArray = obj.optJSONArray("releases") ?: return@use null
                var fallbackHtml: String? = null
                for (i in 0 until releasesArray.length()) {
                    val r = releasesArray.optJSONObject(i) ?: continue
                    val descHtml = r.optString("descriptionHTML", "")
                    if (fallbackHtml == null && descHtml.isNotBlank()) {
                        fallbackHtml = descHtml
                    }
                    val rname = r.optString("name", r.optString("tagName", r.optString("version", "")))
                    if (rname == latestTag && descHtml.isNotBlank()) {
                        return@use descHtml
                    }
                }
                fallbackHtml
            }
        }
    }.getOrNull()
}


fun fetchModuleDetail(moduleId: String): ModuleDetail? {
    if (!isNetworkAvailable(ksuApp)) return null
    return fetchDetailV1(moduleId) ?: fetchDetailLegacy(moduleId)
}

/** 官方 v1 详情：readme 是渲染好的 HTML（单独一个路径），资产在 releases[].zip 里。 */
private fun fetchDetailV1(moduleId: String): ModuleDetail? {
    val body = httpText("$OFFICIAL_V1/$moduleId.json") ?: return null
    return runCatching {
        val obj = JSONObject(body)
        val readmePath = obj.optString("readme", "").trim()
        val readmeHtml = if (readmePath.isEmpty()) {
            ""
        } else {
            val readmeUrl = if (readmePath.startsWith("http")) {
                readmePath
            } else {
                "$OFFICIAL_BASE/${readmePath.trimStart('/')}"
            }
            httpText(readmeUrl).orEmpty()
        }
        val sourceUrl = obj.optJSONObject("source")?.optString("url", "").orEmpty()
        val homepageUrl = obj.optString("homepage", "")
        val releasesArray = obj.optJSONArray("releases") ?: JSONArray()
        val releases = (0 until releasesArray.length()).mapNotNull { idx ->
            val r = releasesArray.optJSONObject(idx) ?: return@mapNotNull null
            val tag = r.optString("tag", r.optString("version", ""))
            val zip = r.optJSONObject("zip")
            val zipUrl = zip?.optString("url", "").orEmpty()
            val assets = if (zip == null || zipUrl.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    ReleaseAssetInfo(
                        name = zip.optString("name", zipUrl.substringAfterLast('/')),
                        downloadUrl = zipUrl,
                        size = zip.optLong("size", 0L),
                        downloadCount = 0,
                        sha256 = zip.optString("sha256", "").trim(),
                    )
                )
            }
            ReleaseInfo(
                name = r.optString("name", tag),
                tagName = tag,
                publishedAt = r.optString("publishedAt", ""),
                descriptionHTML = r.optString("changelogHtml", ""),
                assets = assets,
            )
        }
        val latest = releases.firstOrNull()
        ModuleDetail(
            readme = "",
            readmeHtml = readmeHtml,
            latestTag = latest?.tagName.orEmpty(),
            latestTime = latest?.publishedAt.orEmpty(),
            latestAssetName = latest?.assets?.firstOrNull()?.name,
            latestAssetUrl = latest?.assets?.firstOrNull()?.downloadUrl,
            releases = releases,
            homepageUrl = homepageUrl,
            sourceUrl = sourceUrl,
            url = "",
        )
    }.getOrNull()
}

/** 旧协议详情：readme/readmeHTML 内联在同一个 JSON 里。自建仓库与老镜像走这条。 */
private fun fetchDetailLegacy(moduleId: String): ModuleDetail? {
    val url = "$OFFICIAL_LEGACY/$moduleId.json"
    return runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body.string()
            val obj = JSONObject(body)
            val readme = obj.optString("readme", "")
            val readmeHtml = obj.optString("readmeHTML", "")
            val homepageUrl = stripTicks(obj.optString("homepageUrl", ""))
            val sourceUrl = stripTicks(obj.optString("sourceUrl", ""))
            val url = stripTicks(obj.optString("url", ""))
            val lr = obj.optJSONObject("latestRelease")
            var latestTag: String
            var latestTime = ""
            var latestAssetName: String? = null
            var latestAssetUrl: String? = null
            if (lr != null) {
                latestTag = lr.optString("name", lr.optString("version", ""))
                latestTime = lr.optString("time", "")
                var urlDl = lr.optString("downloadUrl", "")
                urlDl = stripTicks(urlDl)
                if (urlDl.isNotEmpty()) {
                    latestAssetName = urlDl.substringAfterLast('/')
                    latestAssetUrl = urlDl
                }
            } else {
                latestTag = obj.optString("latestRelease", "")
            }

            val releasesArray = obj.optJSONArray("releases")
            val releases = if (releasesArray != null) {
                (0 until releasesArray.length()).mapNotNull { rIdx ->
                    val r = releasesArray.optJSONObject(rIdx) ?: return@mapNotNull null
                    val rname = r.optString("name", r.optString("tagName", r.optString("version", "")))
                    val publishedAt = r.optString("publishedAt", "")
                    val descHtml = r.optString("descriptionHTML", "")
                    val assetsArray = r.optJSONArray("releaseAssets") ?: JSONArray()
                    val assets = (0 until assetsArray.length()).mapNotNull { aIdx ->
                        val a = assetsArray.optJSONObject(aIdx) ?: return@mapNotNull null
                        val aname = a.optString("name", "")
                        var adl = a.optString("downloadUrl", "")
                        adl = stripTicks(adl)
                        val asz = a.optLong("size", 0L)
                        val dcnt = a.optInt("downloadCount", 0)
                        if (aname.isEmpty() || adl.isEmpty()) {
                            null
                        } else {
                            ReleaseAssetInfo(aname, adl, asz, dcnt, a.optString("sha256", "").trim())
                        }
                    }
                    ReleaseInfo(
                        name = rname,
                        tagName = r.optString("tagName", rname),
                        publishedAt = publishedAt,
                        descriptionHTML = descHtml,
                        assets = assets
                    )
                }
            } else emptyList()

            return@use ModuleDetail(
                readme = readme,
                readmeHtml = readmeHtml,
                latestTag = latestTag,
                latestTime = latestTime,
                latestAssetName = latestAssetName,
                latestAssetUrl = latestAssetUrl,
                releases = releases,
                homepageUrl = homepageUrl,
                sourceUrl = sourceUrl,
                url = url
            )
        }
    }.getOrNull()
}

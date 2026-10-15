package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Author
import me.weishu.kernelsu.data.model.ReleaseAsset
import me.weishu.kernelsu.data.model.RepoModule
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ModuleRepoRepositoryImpl : ModuleRepoRepository {

    @Volatile
    override var lastSource: String? = null
        private set

    /**
     * 依次尝试各仓库源：配置的主源（默认自建仓库）优先，失败再回退官方源。
     * 自建站挂掉不能让模块列表整个空白 —— 界面拿 [lastSource] 就能看出用的是哪一个。
     */
    override suspend fun fetchModules(): Result<List<RepoModule>> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(ksuApp)) {
            return@withContext Result.failure(Exception("Network unavailable"))
        }
        var lastError: Exception? = null
        for (source in ModuleRepoSettings.sources()) {
            try {
                val modules = fetchFrom(source)
                lastSource = source
                return@withContext Result.success(modules)
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: Exception("no module repository source"))
    }

    private fun fetchFrom(url: String): List<RepoModule> {
        val request = Request.Builder().url(url).build()
        ksuApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Fetch failed: ${response.code} @ $url")
            }
            val body = response.body.string()
            // 两种索引形状都要认：
            //   * 自建仓库 / 历史官方：裸数组 [ {moduleId, moduleName, latestRelease{…}} ]
            //   * 官方 v1：           {schemaVersion, buildId, modules:[ {id, name, stable{…}} ]}
            // 只改 URL 不认对象形状的话，官方源会在 JSONArray(body) 上抛异常，于是又默默
            // 回退到自建仓库 —— 用户点「官方源」却看到自家模块，且没有任何提示。
            val trimmed = body.trimStart()
            val json = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("modules") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            return (0 until json.length()).mapNotNull { idx ->
                json.optJSONObject(idx)?.let { item -> parseRepoModule(item) }
            }
        }
    }

    private fun parseRepoModule(item: JSONObject): RepoModule? {
        // 官方 v1 用 id/name/stable{version,versionCode,publishedAt}/stars，
        // 自建仓库用 moduleId/moduleName/latestRelease{…}/stargazerCount。
        val moduleId = item.optString("moduleId", "").ifEmpty { item.optString("id", "") }
        if (moduleId.isEmpty()) return null
        val moduleName = item.optString("moduleName", "").ifEmpty { item.optString("name", "") }
        val authorsArray = item.optJSONArray("authors")
        val authorList = if (authorsArray != null) {
            (0 until authorsArray.length())
                .mapNotNull { idx ->
                    val authorObj = authorsArray.optJSONObject(idx) ?: return@mapNotNull null
                    val name = authorObj.optString("name", "").trim()
                    // 自建仓库写 link，官方 v1 写 url
                    var link = authorObj.optString("link", "")
                        .ifEmpty { authorObj.optString("url", "") }
                        .trim()
                    if (link.startsWith("`") && link.endsWith("`") && link.length >= 2) {
                        link = link.substring(1, link.length - 1)
                    }
                    if (name.isEmpty()) null else Author(name = name, link = link)
                }
        } else {
            emptyList()
        }
        val authors = if (authorList.isNotEmpty()) authorList.joinToString(", ") { it.name } else item.optString("authors", "")
        val summary = item.optString("summary", "")
        val metamodule = item.optBoolean("metamodule", false)
        val stargazerCount = item.optInt("stargazerCount", item.optInt("stars", 0))
        val updatedAt = item.optString("updatedAt", "")
        val createdAt = item.optString("createdAt", "").ifEmpty { updatedAt }

        var latestRelease = ""
        var latestReleaseTime = ""
        var latestVersionCode = 0
        var latestAsset: ReleaseAsset? = null
        // 自建仓库直接在列表里带下载地址；官方 v1 的列表条目只有 stable{…}，
        // 资产要等详情页（releases[].zip）才有，所以这里允许 latestAsset 为空。
        val lr = item.optJSONObject("latestRelease") ?: item.optJSONObject("stable")
        if (lr != null) {
            val official = item.optJSONObject("latestRelease") == null
            val lrName = if (official) {
                lr.optString("version", "")
            } else {
                lr.optString("name", lr.optString("version", ""))
            }
            val lrTime = lr.optString("time", "").ifEmpty { lr.optString("publishedAt", "") }
            var lrUrl = lr.optString("downloadUrl", "")
            lrUrl = lrUrl.trim().let {
                var s = it
                if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
                    s = s.substring(1, s.length - 1)
                }
                s
            }

            latestVersionCode = lr.optInt("versionCode", 0)
            latestRelease = lrName
            latestReleaseTime = lrTime
            if (lrUrl.isNotEmpty()) {
                val fileName = lrUrl.substringAfterLast('/')
                latestAsset = ReleaseAsset(
                    name = fileName,
                    downloadUrl = lrUrl,
                    size = lr.optLong("size", 0L),
                    sha256 = lr.optString("sha256", "").trim(),
                )
            }
        }

        return RepoModule(
            moduleId = moduleId,
            moduleName = moduleName,
            authors = authors,
            authorList = authorList,
            summary = summary,
            metamodule = metamodule,
            stargazerCount = stargazerCount,
            updatedAt = updatedAt,
            createdAt = createdAt,
            latestRelease = latestRelease,
            latestReleaseTime = latestReleaseTime,
            latestVersionCode = latestVersionCode,
            latestAsset = latestAsset,
        )
    }
}

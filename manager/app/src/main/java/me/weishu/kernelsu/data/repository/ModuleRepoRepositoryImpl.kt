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
            val json = JSONArray(body)
            return (0 until json.length()).mapNotNull { idx ->
                json.optJSONObject(idx)?.let { item -> parseRepoModule(item) }
            }
        }
    }

    private fun parseRepoModule(item: JSONObject): RepoModule? {
        val moduleId = item.optString("moduleId", "")
        if (moduleId.isEmpty()) return null
        val moduleName = item.optString("moduleName", "")
        val authorsArray = item.optJSONArray("authors")
        val authorList = if (authorsArray != null) {
            (0 until authorsArray.length())
                .mapNotNull { idx ->
                    val authorObj = authorsArray.optJSONObject(idx) ?: return@mapNotNull null
                    val name = authorObj.optString("name", "").trim()
                    var link = authorObj.optString("link", "").trim()
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
        val stargazerCount = item.optInt("stargazerCount", 0)
        val updatedAt = item.optString("updatedAt", "")
        val createdAt = item.optString("createdAt", "")

        var latestRelease = ""
        var latestReleaseTime = ""
        var latestVersionCode = 0
        var latestAsset: ReleaseAsset? = null
        val lr = item.optJSONObject("latestRelease")
        if (lr != null) {
            val lrName = lr.optString("name", lr.optString("version", ""))
            val lrTime = lr.optString("time", "")
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
                latestAsset = ReleaseAsset(name = fileName, downloadUrl = lrUrl, size = 0L)
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

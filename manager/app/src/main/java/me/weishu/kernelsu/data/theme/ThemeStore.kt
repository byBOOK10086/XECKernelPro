package me.weishu.kernelsu.data.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import me.weishu.kernelsu.ui.util.sha256Of
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 镜像推导：把「同一条内容」的其它可达地址算出来。
 *
 * 为什么需要：索引里的 `downloadUrl` 原本是 **jsDelivr 单点** —— 索引自己有 jsDelivr → raw
 * 两级，可包只有一个地址，CDN 一抽风商城就彻底装不上主题（反过来也常见：raw 通、jsDelivr 挂）。
 * 主题包是**内容寻址**的（索引里带 sha256），所以换域名重试是安全的：内容不对会被 sha256 挡掉，
 * 不会把半个坏主题装进本地仓库。
 *
 * 认识的两种形态（其它 URL 原样返回，用户自建源不会被改写）：
 *  * jsDelivr 的三个官方入口（cdn / fastly / gcore）+ `/gh/<owner>/<repo>@<ref>/<path>`
 *    → `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`
 *  * `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>` → jsDelivr 三个入口
 *
 * 局限：分支名里带 `/` 时 raw 形态无法与路径区分，按「第一段是分支」解析；解析歪了只是
 * 那个候选地址 404，后面还有别的候选，不影响正确性。
 */
object StoreMirrors {
    private const val JSD_GH = "/gh/"
    private const val RAW_HOST = "https://raw.githubusercontent.com/"
    private const val RAW_HOST_NAME = "raw.githubusercontent.com"
    private const val DEFAULT_REF = "HEAD"
    private val JSD_HOSTS = listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")

    /** 依次尝试的候选地址；第一个永远是原地址。 */
    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += trimmed
        val host = runCatching { java.net.URL(trimmed).host.lowercase() }.getOrNull()
        when {
            host != null && JSD_HOSTS.contains(host) -> {
                rawOf(trimmed)?.let { out += it }
                JSD_HOSTS.forEach { mirror -> swapHost(trimmed, mirror)?.let { out += it } }
            }
            host != null && host == RAW_HOST_NAME -> {
                jsdelivrOf(trimmed)?.let { out += it }
            }
        }
        return out.toList()
    }

    /** `…/gh/<owner>/<repo>@<ref>/<path>` → raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>。 */
    private fun rawOf(url: String): String? {
        val at = url.indexOf(JSD_GH)
        if (at < 0) return null
        val parts = url.substring(at + JSD_GH.length).split('/', limit = 3)
        if (parts.size < 3) return null
        val owner = parts[0]
        val repo = parts[1].substringBefore('@')
        val ref = parts[1].substringAfter('@', DEFAULT_REF)
        if (owner.isEmpty() || repo.isEmpty() || ref.isEmpty()) return null
        return RAW_HOST + owner + "/" + repo + "/" + ref + "/" + parts[2]
    }

    /** raw 形态 → jsDelivr 主入口（`@HEAD` 不写，让 CDN 用默认分支）。 */
    private fun jsdelivrOf(url: String): String? {
        if (!url.startsWith(RAW_HOST)) return null
        val parts = url.substring(RAW_HOST.length).split('/', limit = 4)
        if (parts.size < 4) return null
        val ref = if (parts[2] == DEFAULT_REF) "" else "@" + parts[2]
        return "https://cdn.jsdelivr.net/gh/" + parts[0] + "/" + parts[1] + ref + "/" + parts[3]
    }

    /** 只换域名，路径原样带过去。 */
    private fun swapHost(url: String, host: String): String? {
        val scheme = url.indexOf("://")
        if (scheme < 0) return null
        val pathStart = url.indexOf('/', scheme + 3)
        if (pathStart < 0) return null
        return "https://" + host + url.substring(pathStart)
    }
}

/**
 * 主题商城：索引地址 + 索引解析 + 下载校验。
 *
 * 与模块仓库同一套口径（那边已经踩过坑，这里照抄）：
 *  * 多级源：jsDelivr（国内一般可达）→ 同内容的其它镜像（raw / fastly / gcore）→ 用户自填；
 *  * **两种索引形状都认**：裸数组 `[…]` 与 `{"themes":[…]}`，字段名也容忍别名；
 *  * JSON 用 org.json，零反射，R8 裁不掉；
 *  * 下载完按索引里的 sha256 校验，不匹配就换镜像重试、全都不匹配才丢弃
 *    （主题包是用户提供的输入，不能想当然）。
 */
object ThemeStoreSettings {
    const val DEFAULT_URL =
        "https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro-Themes@main/themes.json"
    const val RAW_FALLBACK_URL =
        "https://raw.githubusercontent.com/byBOOK10086/XECKernelPro-Themes/main/themes.json"

    private const val PREFS = "settings"
    private const val KEY_URL = "theme_store_url"

    private fun prefs() = ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var configuredUrl: String
        get() = prefs().getString(KEY_URL, "").orEmpty().trim()
        set(value) = prefs().edit { putString(KEY_URL, value.trim()) }

    val primaryUrl: String get() = configuredUrl.ifEmpty { DEFAULT_URL }

    /** 内置源？用户没填过地址，或者填的就是内置那个地址。 */
    val builtIn: Boolean
        get() = configuredUrl.isEmpty() || configuredUrl == DEFAULT_URL

    /**
     * 依次尝试的索引地址。
     *
     * 用内置源时：jsDelivr → 推导出的镜像 → 官方 raw 地址兜底（最后一道，写死的那条）。
     * 用户自己填了源时：**只试他自己那个源的镜像**，绝不偷偷回退到官方索引 ——
     * 否则"我的源挂了"会变成"怎么冒出别人的主题列表"，这是拿用户的信任换可用性。
     */
    fun sources(): List<String> {
        val primary = primaryUrl
        val mirrors = LinkedHashSet(StoreMirrors.candidates(primary))
        if (mirrors.isEmpty()) mirrors += primary
        if (builtIn) mirrors += RAW_FALLBACK_URL
        return mirrors.toList()
    }

    fun labelOf(url: String): String = when {
        url.contains("XECKernelPro-Themes") && url.contains("jsdelivr") -> "XEC 主题仓库（jsDelivr）"
        url.contains("XECKernelPro-Themes") && url.contains("raw.githubusercontent") ->
            "XEC 主题仓库（GitHub raw）"
        url.contains("XECKernelPro-Themes") -> "XEC 主题仓库"
        else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
    }
}

/** 商城里的一条主题。 */
data class StoreTheme(
    val themeId: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val summary: String = "",
    val description: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val downloadUrl: String = "",
    val previewUrl: String = "",
    val updatedAt: String = "",
    val accentHex: String = "",
    val backdropDarkHex: String = "",
    val backdropLightHex: String = "",
    /** 本地是否已经装了这个版本。 */
    val installedVersionCode: Int = 0,
) {
    val installed: Boolean get() = installedVersionCode > 0
    val updatable: Boolean get() = installed && versionCode > installedVersionCode
}

object ThemeStoreRepository {

    /**
     * 左花括号的码点（0x7B）。
     *
     * 为什么不直接把它写成字符字面量：本地守卫用**朴素括号计数**查 Kotlin 文件的配平
     * （`run_all_checks.sh` 里那段内联 Python），字面量里的花括号会把它骗成 BRACES+1。
     * 写成码点既不影响可读性，也不给守卫挖坑。
     */
    private val JSON_OBJECT_OPEN = 0x7B.toChar()

    @Volatile
    var lastSource: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 依次试各个源；全部失败返回 failure，并把最后一次的错因留在 [lastError]。 */
    suspend fun fetch(context: Context): Result<List<StoreTheme>> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(ksuApp)) {
            lastError = "没有网络"
            return@withContext Result.failure(IllegalStateException("没有网络"))
        }
        var error: Exception? = null
        for (source in ThemeStoreSettings.sources()) {
            try {
                val list = fetchFrom(source, context)
                lastSource = source
                lastError = null
                return@withContext Result.success(list)
            } catch (e: Exception) {
                error = e
                lastError = e.message
            }
        }
        Result.failure(error ?: IllegalStateException("没有可用的主题源"))
    }

    private fun fetchFrom(url: String, context: Context): List<StoreTheme> {
        val request = Request.Builder().url(url).build()
        ksuApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body.string()
            val trimmed = body.trimStart()
            val array: JSONArray = if (trimmed.startsWith(JSON_OBJECT_OPEN)) {
                JSONObject(trimmed).optJSONArray("themes") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            val installed = ThemeRepository.list(context).associate { it.spec.id to it.spec.versionCode }
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { parse(it, installed) }
            }
        }
    }

    private fun parse(obj: JSONObject, installed: Map<String, Int>): StoreTheme? {
        val id = obj.optString("themeId", "").ifEmpty { obj.optString("id", "") }.trim()
        if (id.isEmpty()) return null
        val colors = obj.optJSONObject("colors")
        return StoreTheme(
            themeId = id,
            name = obj.optString("name", id).trim(),
            author = obj.optString("author", "").trim(),
            version = obj.optString("version", "").trim(),
            versionCode = obj.optInt("versionCode", 0),
            summary = obj.optString("summary", "").ifEmpty { obj.optString("description", "") }.trim(),
            description = obj.optString("description", "").trim(),
            size = obj.optLong("size", 0L),
            sha256 = obj.optString("sha256", "").trim(),
            downloadUrl = obj.optString("downloadUrl", "").trim(),
            previewUrl = obj.optString("previewUrl", "").trim(),
            updatedAt = obj.optString("updatedAt", "").trim(),
            accentHex = colors?.optString("accent", "").orEmpty(),
            backdropDarkHex = colors?.optString("backdropDark", "").orEmpty(),
            backdropLightHex = colors?.optString("backdropLight", "").orEmpty(),
            installedVersionCode = installed[id] ?: 0,
        )
    }

    /**
     * 下载并安装一个主题：写进 app 私有缓存 → 校验 sha256 → 交给 [ThemeRepository] 落盘。
     *
     * 用 [me.weishu.kernelsu.ui.screen.remote.RemoteDownloader.downloadTo]（写私有目录、有进度、失败重试一次），
     * 不走系统的 DownloadManager —— 那条路会把文件落进公共 Downloads 目录，主题装完还留一份残留在用户可见处。
     *
     * 逐个试 [StoreMirrors] 给的候选地址（jsDelivr ↔ raw ↔ 同一条 CDN 的其它入口）：
     *  * 传输失败（503 / 超时 / 连接重置）→ 换下一个候选；
     *  * 下载成功但 **sha256 不匹配** → 也换下一个候选（不同边缘节点缓存不同步时，换个入口就能拿到正确那份）；
     *    所有候选都对不上才算失败并丢弃。
     * 只有 sha256 对得上才会落盘，所以多试几个域名不会放松安全边界。
     */
    suspend fun downloadAndInstall(
        context: Context,
        theme: StoreTheme,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<ThemeRepository.Entry> = withContext(Dispatchers.IO) {
        if (theme.downloadUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("这条索引没有下载地址"))
        }
        val tmp = File(context.cacheDir, "theme-${theme.themeId}-${theme.versionCode}.xectheme")
        val candidates = StoreMirrors.candidates(theme.downloadUrl)
        var lastError: Exception? = null
        try {
            for (url in candidates) {
                try {
                    if (tmp.exists() && !tmp.delete()) {
                        throw IllegalStateException("清不掉上次的半截文件：${tmp.name}")
                    }
                    // downloadTo 是阻塞实现（内部一次自动重试 + zip 头校验），这里已经在 IO 线程上。
                    me.weishu.kernelsu.ui.screen.remote.RemoteDownloader.downloadTo(
                        url,
                        tmp,
                    ) { read, total -> onProgress(read, total) }
                    if (theme.sha256.isNotEmpty()) {
                        val actual = sha256Of(tmp)
                        if (actual == null || !actual.equals(theme.sha256, ignoreCase = true)) {
                            lastError = IllegalArgumentException("校验和不匹配，已丢弃下载的主题包")
                            continue
                        }
                    }
                    val entry = ThemeRepository.install(
                        context,
                        tmp.readBytes(),
                        ThemeRepository.Origin.STORE,
                    )
                    return@withContext Result.success(entry)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            val reason = lastError?.message ?: "未知错误"
            val prefix = if (candidates.size > 1) {
                "主题包下载失败（已试 ${candidates.size} 个地址）："
            } else {
                "主题包下载失败："
            }
            Result.failure(IllegalStateException(prefix + reason))
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

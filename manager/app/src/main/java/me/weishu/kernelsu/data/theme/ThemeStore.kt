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
 * 主题商城：索引地址 + 索引解析 + 下载校验。
 *
 * 与模块仓库同一套口径（那边已经踩过坑，这里照抄）：
 *  * 三级源：jsDelivr（国内一般可达）→ GitHub raw（CDN 抽风时）→ 用户自填；
 *  * **两种索引形状都认**：裸数组 `[…]` 与 `{"themes":[…]}`，字段名也容忍别名；
 *  * JSON 用 org.json，零反射，R8 裁不掉；
 *  * 下载完按索引里的 sha256 校验，不匹配就丢弃（主题包是用户提供的输入，不能想当然）。
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

    fun sources(): List<String> = listOf(primaryUrl, RAW_FALLBACK_URL).distinct()

    fun labelOf(url: String): String = when {
        url.contains("XECKernelPro-Themes") && url.contains("jsdelivr") -> "XEC 主题仓库（jsDelivr）"
        url.contains("XECKernelPro-Themes") -> "XEC 主题仓库（GitHub raw）"
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
     * 用 [me.weishu.kernelsu.ui.remote.RemoteDownloader.downloadTo]（写私有目录、有进度、失败重试一次），
     * 不走系统的 DownloadManager —— 那条路会把文件落进公共 Downloads 目录，主题装完还留一份残留在用户可见处。
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
        try {
            // downloadTo 是阻塞实现（内部一次自动重试 + zip 头校验），这里已经在 IO 线程上。
            me.weishu.kernelsu.ui.screen.remote.RemoteDownloader.downloadTo(
                theme.downloadUrl,
                tmp,
            ) { read, total -> onProgress(read, total) }
            if (theme.sha256.isNotEmpty()) {
                val actual = sha256Of(tmp)
                if (actual == null || !actual.equals(theme.sha256, ignoreCase = true)) {
                    tmp.delete()
                    return@withContext Result.failure(
                        IllegalStateException("校验和不匹配，已丢弃下载的主题包")
                    )
                }
            }
            val entry = ThemeRepository.install(context, tmp.readBytes(), ThemeRepository.Origin.STORE)
            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

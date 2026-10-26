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
    /**
     * 索引地址带 `?v=3` 的**缓存破冰版本号**。
     *
     * 原因：jsDelivr / 校园网代理这类中间缓存会长期返回同一份旧索引（真机上撞到过：
     * 仓库已经是 schema 3，客户端还拿到几个月前的数组形态）。加一个随索引格式走的常量
     * 查询串，一次性绕开所有旧缓存；以后再改索引格式就把它 +1。
     *
     * 查询串对 jsDelivr 的文件解析没有影响，镜像推导（[StoreMirrors]）也会原样带上它。
     */
    private const val INDEX_CACHE_TAG = "?v=3"

    const val DEFAULT_URL =
        "https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro-Themes@main/themes.json$INDEX_CACHE_TAG"
    const val RAW_FALLBACK_URL =
        "https://raw.githubusercontent.com/byBOOK10086/XECKernelPro-Themes/main/themes.json$INDEX_CACHE_TAG"

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

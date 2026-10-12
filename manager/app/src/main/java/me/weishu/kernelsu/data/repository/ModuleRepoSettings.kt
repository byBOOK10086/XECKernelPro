package me.weishu.kernelsu.data.repository

import android.content.Context
import androidx.core.content.edit
import me.weishu.kernelsu.ksuApp

/**
 * 模块仓库源。
 *
 * 默认走自建仓库（可以在上面自己上传模块），拉不到时回退官方源 —— "仓库换成自己的"
 * 最怕的就是自建站一挂模块列表整个空白，所以这里永远保留一条兜底路径，并且把
 * **实际生效的源**返还给界面，让用户看得见自己现在看的是谁的仓库。
 */
object ModuleRepoSettings {

    /** 自建仓库（默认源）：和官方同 schema 的 modules.json。 */
    const val DEFAULT_URL = "http://42.193.123.248/modrepo/modules.json"

    /** 官方仓库，只做兜底。 */
    const val OFFICIAL_URL = "https://modules.kernelsu.org/modules.json"

    private const val PREFS = "settings"
    private const val KEY_MODULE_REPO_URL = "module_repo_url"

    private fun prefs() = ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户填的地址；空串 = 用默认自建仓库。 */
    var configuredUrl: String
        get() = prefs().getString(KEY_MODULE_REPO_URL, "").orEmpty().trim()
        set(value) = prefs().edit { putString(KEY_MODULE_REPO_URL, value.trim()) }

    /** 主源（自建仓库）。 */
    val primaryUrl: String
        get() = configuredUrl.ifEmpty { DEFAULT_URL }

    /** 依次尝试的源：主源优先，官方兜底，重复只试一次。 */
    fun sources(): List<String> = listOf(primaryUrl, OFFICIAL_URL).distinct()

    /** 展示用短标签：去掉协议与路径，只留主机（含端口）。 */
    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

package me.weishu.kernelsu.data.theme

import android.content.Context
import androidx.core.content.edit
import me.weishu.kernelsu.ksuApp

/**
 * 收藏夹（只有本地，不上传——用户没登录体系，做"云端收藏"就是假功能）。
 *
 * 存 SharedPreferences 的字符串集合，值就是 `themeId`。读的时候顺手丢掉空白项，
 * 避免历史脏数据把收藏页画出一堆空卡。
 */
object StoreFavorites {

    private const val PREFS = "settings"
    private const val KEY_IDS = "theme_store_favorites"
    private const val KEY_SEEN_AT = "theme_store_favorite_seen_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context = ksuApp): Set<String> =
        prefs(context).getStringSet(KEY_IDS, emptySet())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    fun contains(themeId: String, context: Context = ksuApp): Boolean = load(context).contains(themeId)

    /**
     * 切换收藏状态。
     *
     * 有快照的时间戳一起存：收藏页要按"最近收藏"倒序，而 StringSet 本身无序。
     */
    fun toggle(context: Context, themeId: String): Boolean {
        if (themeId.isBlank()) return false
        val current = load(context).toMutableSet()
        val added = current.add(themeId)
        if (!added) current.remove(themeId)
        prefs(context).edit {
            putStringSet(KEY_IDS, current)
            if (added) {
                // 每次收藏都写时间戳，最近收藏的 id 会覆盖成最新时间。
                putLong("$KEY_SEEN_AT:$themeId", System.currentTimeMillis())
            }
        }
        return added
    }

    /** 最近收藏时间（没有记录就是 0，排序时排最后）。 */
    fun favoritedAt(themeId: String, context: Context = ksuApp): Long =
        prefs(context).getLong("$KEY_SEEN_AT:$themeId", 0L)

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_IDS) }
    }
}

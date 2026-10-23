package me.weishu.kernelsu.widget

import android.content.Context

/**
 * Last-known XEC Score, persisted so the home-screen widget and the Quick
 * Settings tile can render immediately without waking the manager process.
 *
 * This is a display cache, not a security boundary: the score is recomputed by
 * HomeViewModel on every refresh, and the one-tap action is still authenticated
 * by [com.xecpro.kernel.XecIntents].
 */
data class XecWidgetSnapshot(
    val score: Int = 0,
    val rank: String = "?",
    val summary: String = "",
    val detail: String = "",
    val active: Boolean = false,
    val updatedAt: Long = 0L,
)

object XecWidgetStore {
    private const val PREFS = "xec_widget_state"

    fun read(context: Context): XecWidgetSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return XecWidgetSnapshot(
            score = p.getInt("score", 0).coerceIn(0, 100),
            rank = p.getString("rank", "?") ?: "?",
            summary = p.getString("summary", "") ?: "",
            detail = p.getString("detail", "") ?: "",
            active = p.getBoolean("active", false),
            updatedAt = p.getLong("updated_at", 0L),
        )
    }

    fun write(context: Context, snapshot: XecWidgetSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("score", snapshot.score.coerceIn(0, 100))
            .putString("rank", snapshot.rank)
            .putString("summary", snapshot.summary)
            .putString("detail", snapshot.detail)
            .putBoolean("active", snapshot.active)
            .putLong("updated_at", snapshot.updatedAt)
            .apply()
    }
}

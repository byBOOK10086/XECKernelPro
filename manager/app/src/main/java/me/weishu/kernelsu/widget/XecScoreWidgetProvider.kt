package me.weishu.kernelsu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xecpro.kernel.XecIntents
import me.weishu.kernelsu.R

/** Home-screen XEC Score card. Tapping it opens the manager; the hide button
 *  opens the existing OneTapHide flow (which asks for confirmation itself). */
class XecScoreWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, XecScoreWidgetProvider::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = XecWidgetStore.read(context)
            val hasState = snapshot.updatedAt > 0L
            val views = RemoteViews(context.packageName, R.layout.xec_score_widget).apply {
                setTextViewText(R.id.widget_score, if (hasState) snapshot.score.toString() else "—")
                setTextViewText(R.id.widget_rank, if (hasState) snapshot.rank else "?")
                setTextViewText(
                    R.id.widget_summary,
                    snapshot.summary.ifBlank { context.getString(R.string.xec_widget_summary_empty) },
                )
                setTextViewText(
                    R.id.widget_detail,
                    snapshot.detail.ifBlank { context.getString(R.string.xec_widget_detail_empty) },
                )
                setOnClickPendingIntent(R.id.widget_root, homePendingIntent(context))
                setOnClickPendingIntent(R.id.widget_hide, hidePendingIntent(context))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun homePendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0xEC10,
                XecIntents.openHome(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun hidePendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0xEC11,
                XecIntents.oneTapHide(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

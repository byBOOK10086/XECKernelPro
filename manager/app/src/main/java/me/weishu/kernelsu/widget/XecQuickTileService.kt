package me.weishu.kernelsu.widget

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xecpro.kernel.XecIntents
import me.weishu.kernelsu.R

/**
 * Quick Settings entry for the existing OneTapHide flow.
 *
 * The tile never performs a root action silently: it opens the same confirmation
 * screen as the widget. The tile state mirrors the last XEC Score snapshot.
 */
class XecQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = XecIntents.oneTapHide(this)
        val pending = PendingIntent.getActivity(
            this,
            0xEC20,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render() {
        val snapshot = XecWidgetStore.read(this)
        qsTile?.apply {
            label = getString(R.string.xec_tile_label)
            state = if (snapshot.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            icon = Icon.createWithResource(this@XecQuickTileService, R.drawable.ic_xec_shield)
            subtitle = snapshot.summary.ifBlank { getString(R.string.xec_tile_subtitle) }
            updateTile()
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, XecQuickTileService::class.java),
            )
        }
    }
}

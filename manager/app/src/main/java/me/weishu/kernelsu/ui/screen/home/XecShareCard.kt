package me.weishu.kernelsu.ui.screen.home

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.weishu.kernelsu.R

/**
 * Renders a self-contained PNG "device security card" and hands it to the
 * Android share sheet. No screenshot permission and no Compose capture: a
 * plain Canvas keeps the output deterministic and cheap.
 */
object XecShareCard {

    private const val W = 1080
    private const val H = 1440

    fun share(context: Context, state: HomeUiState, score: XecScoreResult) {
        val bitmap = render(context, state, score)
        val dir = File(context.cacheDir, "xec-share").apply { mkdirs() }
        val file = File(dir, "xec-score-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prune(dir)
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("XEC Score", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.xec_score_share_chooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun prune(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun render(context: Context, state: HomeUiState, score: XecScoreResult): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        val accent = AColor.rgb(18, 184, 134)
        val text = AColor.rgb(231, 237, 238)
        val muted = AColor.rgb(148, 163, 184)
        val faint = AColor.rgb(111, 138, 132)

        // Background: deep green-black with two soft aurora pools.
        paint.shader = LinearGradient(
            0f, 0f, W.toFloat(), H.toFloat(),
            AColor.rgb(7, 17, 18), AColor.rgb(4, 9, 11), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = RadialGradient(
            160f, 120f, 700f,
            AColor.argb(120, 18, 184, 134), AColor.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = RadialGradient(
            980f, 420f, 560f,
            AColor.argb(90, 59, 130, 246), AColor.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null

        // Header.
        paint.color = accent
        c.drawRoundRect(RectF(72f, 72f, 140f, 140f), 20f, 20f, paint)
        paint.color = AColor.rgb(11, 15, 16)
        paint.typeface = bold
        paint.textSize = 46f
        c.drawText("X", 92f, 124f, paint)
        paint.color = text
        paint.textSize = 34f
        c.drawText("XECKernelPro", 164f, 112f, paint)
        paint.color = muted
        paint.textSize = 21f
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("DEVICE SECURITY CARD", 1008f, 108f, paint)
        paint.textAlign = Paint.Align.LEFT

        // Score ring.
        val ring = RectF(86f, 218f, 486f, 618f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 26f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = AColor.argb(60, 148, 163, 184)
        c.drawArc(ring, 0f, 360f, false, paint)
        paint.color = accent
        c.drawArc(ring, -90f, 360f * (score.total / 100f), false, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = bold
        paint.textAlign = Paint.Align.CENTER
        paint.color = AColor.WHITE
        paint.textSize = 132f
        c.drawText(score.total.toString(), 286f, 470f, paint)
        paint.color = muted
        paint.textSize = 26f
        c.drawText("/100", 286f, 512f, paint)
        paint.color = accent
        paint.textSize = 28f
        c.drawText(context.getString(R.string.xec_score_rank_label, score.rank.name), 286f, 566f, paint)
        paint.textAlign = Paint.Align.LEFT

        // Summary block.
        paint.color = text
        paint.typeface = bold
        paint.textSize = 42f
        c.drawText(context.getString(R.string.xec_share_score_title), 548f, 286f, paint)
        paint.color = accent
        paint.textSize = 26f
        c.drawText(score.headline + " · " + context.getString(R.string.xec_share_rank), 548f, 330f, paint)
        paint.color = muted
        paint.typeface = regular
        paint.textSize = 23f
        c.drawText(context.getString(R.string.xec_share_passed, score.passed, score.items.size), 548f, 372f, paint)
        c.drawText(context.getString(R.string.xec_share_generated, dateString()), 548f, 406f, paint)

        // Divider.
        paint.color = AColor.argb(40, 231, 237, 238)
        c.drawRect(86f, 664f, 994f, 666f, paint)

        // Device facts.
        paint.typeface = bold
        paint.color = text
        paint.textSize = 27f
        c.drawText(context.getString(R.string.xec_share_device), 86f, 724f, paint)
        paint.typeface = regular
        paint.color = muted
        paint.textSize = 23f
        val device = state.systemInfo.deviceModel.ifBlank { "Android device" }
        c.drawText(device.take(38), 86f, 760f, paint)
        c.drawText(state.systemInfo.kernelVersion.take(46), 86f, 792f, paint)
        c.drawText("Manager " + state.systemInfo.managerVersion, 86f, 824f, paint)
        val susfs = state.susfsVersion?.takeIf { it.isNotBlank() && it != "unsupport" }
            ?: context.getString(R.string.xec_share_not_available)
        c.drawText("SUSFS " + susfs, 86f, 856f, paint)

        // Checks, two columns.
        paint.typeface = bold
        paint.color = text
        paint.textSize = 25f
        c.drawText(context.getString(R.string.xec_share_checks), 86f, 914f, paint)
        val columns = 2
        val cellW = 454f
        val startY = 956f
        val rowH = 58f
        score.items.forEachIndexed { index, item ->
            val col = index % columns
            val row = index / columns
            val x = 86f + col * cellW
            val y = startY + row * rowH
            paint.color = if (item.ok) accent else AColor.rgb(148, 163, 184)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            c.drawCircle(x + 12f, y - 8f, 12f, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = bold
            paint.textSize = 18f
            paint.textAlign = Paint.Align.CENTER
            c.drawText(if (item.ok) "OK" else "--", x + 12f, y - 1f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = regular
            paint.color = if (item.ok) text else muted
            paint.textSize = 23f
            c.drawText(label(context, item.id), x + 36f, y, paint)
        }

        // Footer.
        paint.color = AColor.argb(50, 231, 237, 238)
        c.drawRect(86f, 1318f, 994f, 1320f, paint)
        paint.color = faint
        paint.textSize = 21f
        paint.typeface = regular
        c.drawText(context.getString(R.string.xec_share_footer), 86f, 1372f, paint)
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("xecscore / local", 994f, 1372f, paint)
        return bitmap
    }

    private fun dateString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())

    private fun label(context: Context, id: XecScoreItemId): String = context.getString(
        when (id) {
            XecScoreItemId.ROOT -> R.string.xec_score_check_root
            XecScoreItemId.SELINUX -> R.string.xec_score_check_selinux
            XecScoreItemId.SUSFS -> R.string.xec_score_check_susfs
            XecScoreItemId.TEE -> R.string.xec_score_check_tee
            XecScoreItemId.DAEMON -> R.string.xec_score_check_daemon
            XecScoreItemId.LKM -> R.string.xec_score_check_lkm
            XecScoreItemId.COMPAT -> R.string.xec_score_check_compat
            XecScoreItemId.STABLE -> R.string.xec_score_check_stable
            XecScoreItemId.MODE -> R.string.xec_score_check_mode
        },
    )
}

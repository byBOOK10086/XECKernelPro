package me.weishu.kernelsu.ui.screen.home

/*
 * 模式牌自定义（root 方式显示）：主页状态卡上 LKM / GKI / KernelPatch 那一行。
 *
 * 混搭生态的第一个独立元素（对齐小米主题商店的元素化思路）：
 *  - 前后缀词、底图、字体、文字颜色各自可换；
 *  - **模式关键词本身由系统生成，永远不落库、不进编辑器**——用户只能改它
 *    前后的字（"无敌GKI环境" = prefix"无敌" + 系统词"GKI" + suffix"环境"）；
 *  - 底图复用 [ModuleBackgrounds] 的存储/解码/LruCache（新 slot），字体复制
 *    进 filesDir/fonts 后用系统 Typeface 加载；
 *  - 全默认时渲染路径与旧版逐字节一致（不设即不变原则）。
 */
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.screen.module.ModuleBackgrounds
import me.weishu.kernelsu.ui.screen.module.rememberBackgroundBitmap
import java.io.File

/** 模式牌样式。[keyword] 永远由渲染方传入，不在本模型里。 */
data class ModeBadgeStyle(
    val prefix: String = "",
    val suffix: String = "",
    val bgUri: String? = null,
    val fontPath: String? = null,
    val textColor: Int? = null,
) {
    fun normalized(): ModeBadgeStyle = copy(
        prefix = sanitize(prefix),
        suffix = sanitize(suffix),
    )

    /** 没有任何自定义 → 渲染走旧路径（逐字节一致）。 */
    fun isDefault(): Boolean =
        prefix.isEmpty() && suffix.isEmpty() && bgUri == null && fontPath == null && textColor == null

    companion object {
        const val MAX_TEXT_LEN = 12
        /** 底图在 [ModuleBackgrounds] 里的存储槽位（复用其解码/LruCache 管线）。 */
        const val MODE_BADGE_BG_SLOT = "home_mode_badge"
        fun sanitize(s: String) = s.take(MAX_TEXT_LEN).filter { !it.isISOControl() }
    }
}

object ModeBadgeStyleStore {
    private const val PREFS = "home_mode_badge"

    fun load(context: Context): ModeBadgeStyle {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ModeBadgeStyle(
            prefix = p.getString("prefix", "").orEmpty(),
            suffix = p.getString("suffix", "").orEmpty(),
            bgUri = p.getString("bg", null)?.takeIf { it.isNotBlank() },
            fontPath = p.getString("font", null)?.takeIf { it.isNotBlank() },
            textColor = if (p.contains("textColor")) p.getInt("textColor", 0) else null,
        ).normalized()
    }

    fun save(context: Context, style: ModeBadgeStyle) {
        val v = style.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("prefix", v.prefix)
            .putString("suffix", v.suffix)
            .putString("bg", v.bgUri)
            .putString("font", v.fontPath)
            .apply {
                if (v.textColor == null) remove("textColor") else putInt("textColor", v.textColor)
            }
            .apply()
    }

    /** 自定义字体落盘（选完字体立刻复制，之后不再依赖 content uri）。 */
    fun copyFont(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        val name = "mode_badge_${System.currentTimeMillis()}"
        val dst = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (dst.length() == 0L || dst.length() > 8 * 1024 * 1024) {
            dst.delete()
            return null
        }
        // 换字体时清掉旧文件，避免 filesDir 堆积。
        prefsOldFonts(context)?.forEach { old ->
            if (old != dst.path) File(old).delete()
        }
        dst.path
    }.getOrNull()

    private fun prefsOldFonts(context: Context): List<String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = p.getString("font", null) ?: return null
        return listOf(cur)
    }
}

@Composable
fun rememberModeBadgeStyle(): MutableState<ModeBadgeStyle> {
    val context = LocalContext.current
    return remember(context) { mutableStateOf(ModeBadgeStyleStore.load(context)) }
}

/** 自定义字体 → [FontFamily]；文件缺失/损坏回落 null（用默认字体）。 */
@Composable
fun rememberModeBadgeFont(path: String?): FontFamily? {
    if (path == null) return null
    return remember(path) {
        runCatching {
            val file = File(path)
            if (file.isFile) FontFamily(Typeface.createFromFile(file)) else null
        }.getOrNull()
    }
}

/**
 * 模式牌渲染（仅处理**有自定义**的情形；默认态由调用方走原渲染路径保证逐字节
 * 一致）。文字 = `prefix + keyword + suffix`，[keyword] 由调用方用系统状态生成
 * （LKM / GKI / KernelPatch），本组件不改写不拼接猜测。
 */
@Composable
fun ModeBadge(
    keyword: String,
    style: ModeBadgeStyle,
    modifier: Modifier = Modifier,
) {
    val font = rememberModeBadgeFont(style.fontPath)
    val color = style.textColor?.let { Color(it) }
    val bg = rememberBackgroundBitmap(style.bgUri)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bg != null) {
            Image(
                bitmap = bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (style.blur > 0f) {
                            Modifier.blur(style.blur.dp, BlurredEdgeTreatment.Unbounded)
                        } else {
                            Modifier
                        }
                    ),
            )
            // 固定轻度压暗保证任意底图上的可读性；无底图时不加任何底色。
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.30f)))
        }
        androidx.compose.material3.Text(
            text = "${style.prefix}$keyword${style.suffix}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = font,
            color = color ?: Color.Unspecified,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

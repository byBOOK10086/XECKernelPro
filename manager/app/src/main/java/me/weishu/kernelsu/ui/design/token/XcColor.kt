package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * XEC Fluid Glass · 语义色令牌。
 *
 * 这一层刻意不读 miuix / Material3 的配色，而是自带一套完整取值，
 * 目的就是让界面不再"长得像上游库的默认皮肤"。
 * 浅色模式同样自带取值，只统一形状与动效，不另做一套设计。
 */
@Immutable
data class XcColors(
    val isDark: Boolean,
    /** 页面最底：背景图之下的纯色垫底。 */
    val backdrop: Color,
    /** 压在背景图上的渐变遮罩，把照片推向深色流体玻璃的色调。 */
    val backdropScrim: Color,
    /** 玻璃基色。 */
    val surface: Color,
    /** 次级玻璃 / 内嵌块。 */
    val surfaceMuted: Color,
    /** 玻璃上覆色（半透明），叠在模糊之上。 */
    val glassTint: Color,
    /**
     * 透明玻璃（Clear 形态）的本体染色。
     *
     * 浅色档是 [Color.Transparent]——浅色壁纸下保持全透，只靠折射与亮度自适应塑形；
     * 深色档是黑色玻璃：深色壁纸本身已经压暗，透明玻璃再全透就会显得又亮又飘，
     * 这就是"黑液态玻璃"——介质吸光，但折射、菲涅尔亮边与高光全部保留。
     */
    val clearGlassTint: Color = Color.Transparent,
    /** 玻璃 1px 描边，用于在没有模糊的设备上依然能分辨层次。 */
    val glassRim: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    /** 强调色的低饱和底，用于选中态、药丸背景。 */
    val accentSoft: Color,
    /** 水滴本体色（已带 alpha）。 */
    val water: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
) {
    /**
     * 语义色的「容器态」。
     *
     * 告警卡、状态徽章这类容器不能直接用高饱和的语义实色铺满：深色玻璃上
     * 会刺眼，浅色上又会糊成一块。统一按"实色 @ 低透明度压在 [surface] 上"
     * 合成，深浅两档只调 alpha、规则一致，结果本身不透明——容器一旦半透明
     * 就会透出下面的背景图，红/橙直接变成脏色。
     */
    val dangerTint: Color get() = danger.over(surface, if (isDark) 0.22f else 0.13f)

    val warningTint: Color get() = warning.over(surface, if (isDark) 0.22f else 0.15f)

    val successTint: Color get() = success.over(surface, if (isDark) 0.22f else 0.15f)

    /**
     * 压在容器态之上的前景色。
     *
     * 不能直接拿 [danger] / [warning] / [success] 当文字色：这三个实色在浅色档
     * 是给实底用的（`#D93025` / `#D08700`），铺在将近纯白的容器上，14sp 的小字
     * 对比度只有 3:1 上下，读起来发飘；深色档又反过来偏暗。
     *
     * 所以朝 [text] 方向混一档——浅色档 [text] 近黑，混完变深（红更沉、橙更褐）；
     * 深色档 [text] 近白，混完变亮（红转粉、橙转杏）。两档都能拿到 7:1 以上的
     * 对比度，同时保住语义色相。
     */
    val onDangerTint: Color get() = danger.over(text, 0.55f)

    val onWarningTint: Color get() = warning.over(text, 0.55f)

    val onSuccessTint: Color get() = success.over(text, 0.55f)

    /**
     * 语义实色**当实底用**时的前景色（例：告警卡里的实心操作按钮）。
     *
     * [danger] 一族在深浅两档都偏中艳，白色是唯一两档都稳的取值。
     */
    val onSemanticSolid: Color get() = Color.White
}

/** 把 [this] 按 [alpha] 压在 [base] 上，返回一个不透明的合成色。 */
private fun Color.over(base: Color, alpha: Float): Color = Color(
    red = red * alpha + base.red * (1f - alpha),
    green = green * alpha + base.green * (1f - alpha),
    blue = blue * alpha + base.blue * (1f - alpha),
    alpha = 1f,
)

private val XcDarkBase = XcColors(
    isDark = true,
    backdrop = Color(0xFF141416),
    backdropScrim = Color(0xE6141416),
    surface = Color(0xFF1D1D20),
    surfaceMuted = Color(0xFF212125),
    glassTint = Color(0x8C1D1D20),
    // 已关闭玻璃形态：这一档保留字段但现在不参与渲染
    clearGlassTint = Color(0x00000000),
    glassRim = Color(0xFF2C2C31),
    text = Color(0xFFEDEDEF),
    textSecondary = Color(0xFFA4A4AA),
    textMuted = Color(0xFF7B7B82),
    accent = Color(0xFFFF6900),
    accentSoft = Color(0xFF2A1C12),
    water = Color(0x6BFF6900),
    danger = Color(0xFFFF6B60),
    warning = Color(0xFFE0A233),
    success = Color(0xFF45C07A),
)

private val XcLightBase = XcColors(
    isDark = false,
    // MIUI 8：整页浅灰底，所有内容靠白色分组面板分层
    backdrop = Color(0xFFF3F3F3),
    backdropScrim = Color(0xF2F3F3F3),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFFAFAFA),
    glassTint = Color(0x8CFFFFFF),
    glassRim = Color(0xFFEBEBEB),
    text = Color(0xFF1F2022),
    textSecondary = Color(0xFF6B6C70),
    textMuted = Color(0xFF9A9BA0),
    // MIUI 8 的强调橙
    accent = Color(0xFFFF6900),
    accentSoft = Color(0xFFFFF3E9),
    water = Color(0x6BFF6900),
    danger = Color(0xFFD93025),
    warning = Color(0xFFD9880A),
    success = Color(0xFF1F9D55),
)

/**
 * DARK_AMOLED 档位：底色压到纯黑，面板与分组再抬一档，
 * 否则纯黑底上的面板边界与 1px 分隔线会糊成一片。
 */
private val XcAmoled = XcDarkBase.copy(
    backdrop = Color(0xFF000000),
    backdropScrim = Color(0xF2000000),
    surface = Color(0xFF101012),
    surfaceMuted = Color(0xFF161618),
    glassTint = Color(0x6B101012),
    glassRim = Color(0xFF232327),
)

fun xcColorsFor(isDark: Boolean, isAmoled: Boolean = false): XcColors = when {
    !isDark -> XcLightBase
    isAmoled -> XcAmoled
    else -> XcDarkBase
}

val LocalXcColors = staticCompositionLocalOf { XcDarkBase }

package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import me.weishu.kernelsu.ui.theme.isInDarkTheme

/**
 * 「模块」页专属的霓虹色板（设计稿：暗底霓虹紫 / 青）。
 *
 * 刻意**不**进 [XcTheme] 全局下发：这套取值只服务模块页，
 * 其余页面继续使用 XEC Fluid Glass（墨绿灰 #0B0F10 + teal #12B886）。
 * 因此模块页内自行按 [isInDarkTheme] 取档，保证换肤不外溢到别的页面。
 *
 * 暗色档数值逐条取自设计稿 HTML 原文，注释里保留 CSS 出处；
 * 浅色档是把同一套语义映射到浅底（设计稿只有暗色档，浅色档按可读性补充）。
 */
data class XcNeonColors(
    /** 卡片 / 面板 / 按钮底色：暗 `rgba(20,20,45,0.6)`。 */
    val cardBg: Color,
    /** 卡片描边：暗 `rgba(139,92,246,0.2)`。 */
    val cardBorder: Color,
    /** 按压时描边：`rgba(139,92,246,0.65)`。 */
    val cardBorderPressed: Color,
    /** `.detail-action` 描边：`rgba(139,92,246,0.25)`。 */
    val actionBorder: Color,
    /** `.detail-action:active` 底色：`rgba(139,92,246,0.25)`。 */
    val actionBgPressed: Color,
    /** `.detail-action:active` 描边：`rgba(139,92,246,0.6)`。 */
    val actionBorderPressed: Color,
    /** `.detail-action.danger:active` 描边：`rgba(239,68,68,0.6)`。 */
    val dangerBorderPressed: Color,
    /** `.detail-action.danger:active` 底色：`rgba(239,68,68,0.2)`。 */
    val dangerBgPressed: Color,
    /** `.detail-action.danger` 描边：`rgba(239,68,68,0.3)`。 */
    val dangerBorder: Color,
    /** 霓虹紫 `#8b5cf6`。 */
    val accentPurple: Color,
    /** 霓虹青 `#06b6d4`；`text-neonCyan` 与更新药丸取它。 */
    val accentCyan: Color,
    /** 危险色 `#ef4444`。 */
    val danger: Color,
    /** 卡片主文字 `text-white`，暗色档即纯白。 */
    val textMain: Color,
    /** 卡片次文字 `text-textSub` = `#94a3b8`。 */
    val textSub: Color,
    /** `border-white/5` 用的分隔线（暗底上取白 @0.05）。 */
    val divider: Color,
    /** `META` 徽标底色：`accentPurple` @ 0.18。 */
    val metaBg: Color,
    /** `META` 徽标文字：暗 `#c4b5fd`。 */
    val metaText: Color,
    /** 更新药丸底色：`accentCyan` @ 0.12。 */
    val pillBg: Color,
    /** 渐变起点 `#8b5cf6`。 */
    val gradientStart: Color,
    /** 渐变终点 `#06b6d4`。 */
    val gradientEnd: Color,
    /** 卡片外阴影 `0 4px 24px rgba(0,0,0,0.3)`。 */
    val cardShadow: Color,
    /** 按压外辉光 `0 0 22px rgba(139,92,246,0.35)`。 */
    val glow: Color,
    /** FAB 辉光 `0 4px 20px rgba(139,92,246,0.5)`。 */
    val fabGlow: Color,
    /** 页面光晕（左上，紫 `rgba(139,92,246,0.15)`）。 */
    val pageGlowPurple: Color,
    /** 页面光晕（右下，青 `rgba(6,182,212,0.15)`）。 */
    val pageGlowCyan: Color,
)

/**
 * 暗色档 → 扁平方案 A 的深色令牌（2026-11-09）。
 *
 * 模块页原来是紫色霓虹板（#8b5cf6 / #06b6d4 + 半透明卡 + 外辉光），和新的
 * "实色卡 + 1px 发丝线 + 单一强调色"是两套语言。这里不重写 1176 行的模块页，
 * **只把这块板子的取值映射到扁平令牌**：卡片=surface、描边=rim、强调=accent，
 * 所有阴影/辉光/光晕归零 —— 于是模块页不改一行也变成扁平形态。
 */
private val XcNeonDark = XcNeonColors(
    cardBg = Color(0xFF151519),
    cardBorder = Color(0xFF26262E),
    cardBorderPressed = Color(0xFF3A3A46),
    actionBorder = Color(0xFF26262E),
    actionBgPressed = Color(0xFF1B1B21),
    actionBorderPressed = Color(0xFF3A3A46),
    dangerBorderPressed = Color(0x99FF6B60),
    dangerBgPressed = Color(0x33FF6B60),
    dangerBorder = Color(0x4DFF6B60),
    accentPurple = Color(0xFF6B84FF),
    accentCyan = Color(0xFFB4B4BF),
    danger = Color(0xFFFF6B60),
    textMain = Color(0xFFF5F5F7),
    textSub = Color(0xFFB4B4BF),
    divider = Color(0xFF26262E),
    metaBg = Color(0xFF26262E),
    metaText = Color(0xFFB4B4BF),
    pillBg = Color(0xFF1B2140),
    gradientStart = Color(0xFF6B84FF),
    gradientEnd = Color(0xFF6B84FF),
    cardShadow = Color(0x00000000),
    glow = Color(0x00000000),
    fabGlow = Color(0x00000000),
    pageGlowPurple = Color(0x00000000),
    pageGlowCyan = Color(0x00000000),
)

/**
 * 浅色档：同一套扁平映射。
 */
private val XcNeonLight = XcNeonColors(
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE6E6EA),
    cardBorderPressed = Color(0xFFC8C8D0),
    actionBorder = Color(0xFFE6E6EA),
    actionBgPressed = Color(0xFFF5F5F7),
    actionBorderPressed = Color(0xFFC8C8D0),
    dangerBorderPressed = Color(0x99F04438),
    dangerBgPressed = Color(0x33F04438),
    dangerBorder = Color(0x4DF04438),
    accentPurple = Color(0xFF3B5BFF),
    accentCyan = Color(0xFF6E6E78),
    danger = Color(0xFFF04438),
    textMain = Color(0xFF111114),
    textSub = Color(0xFF6E6E78),
    divider = Color(0xFFE6E6EA),
    metaBg = Color(0xFFEEF1FF),
    metaText = Color(0xFF3B5BFF),
    pillBg = Color(0xFFEEF1FF),
    gradientStart = Color(0xFF3B5BFF),
    gradientEnd = Color(0xFF3B5BFF),
    cardShadow = Color(0x00000000),
    glow = Color(0x00000000),
    fabGlow = Color(0x00000000),
    pageGlowPurple = Color(0x00000000),
    pageGlowCyan = Color(0x00000000),
)

fun xcNeonFor(isDark: Boolean): XcNeonColors = if (isDark) XcNeonDark else XcNeonLight

/**
 * 主题对霓虹板的覆盖（null = 用内置板子）。
 *
 * 这块板子当初**刻意**不进 `XcTheme`，为的是"模块页的花哨配色不外溢到别的页面"。
 * 主题系统沿用这个约定：只有主题包显式写了 `neon.dark/light` 才覆盖，而且覆盖的也只是
 * 模块页自己的取值；没写就完全是原来的样子（默认 null ⇒ 行为零变化）。
 */
val LocalXcNeonOverride = staticCompositionLocalOf<XcNeonColors?> { null }

/** 模块页取色的唯一入口；只在模块页内使用。 */
object XcNeon {
    val colors: XcNeonColors
        @Composable
        @ReadOnlyComposable
        get() = LocalXcNeonOverride.current ?: xcNeonFor(isInDarkTheme())

    /** `linear-gradient(135deg, #8b5cf6, #06b6d4)`（FAB / 进度条取色同源）。 */
    val gradient: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd))

    /** `linear-gradient(90deg, #8b5cf6, #06b6d4)`（长按进度条为水平渐变）。 */
    val horizontalGradient: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.horizontalGradient(listOf(colors.gradientStart, colors.gradientEnd))
}

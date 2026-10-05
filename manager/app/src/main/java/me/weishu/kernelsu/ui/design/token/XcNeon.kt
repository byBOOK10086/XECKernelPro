package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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

/** 暗色档 = 设计稿 HTML 原文。 */
private val XcNeonDark = XcNeonColors(
    cardBg = Color(0x9914142D), // rgba(20,20,45,0.6)
    cardBorder = Color(0x338B5CF6), // rgba(139,92,246,0.2)
    cardBorderPressed = Color(0xA68B5CF6), // rgba(139,92,246,0.65)
    actionBorder = Color(0x408B5CF6), // rgba(139,92,246,0.25)
    actionBgPressed = Color(0x408B5CF6), // rgba(139,92,246,0.25)
    actionBorderPressed = Color(0x998B5CF6), // rgba(139,92,246,0.6)
    dangerBorderPressed = Color(0x99EF4444), // rgba(239,68,68,0.6)
    dangerBgPressed = Color(0x33EF4444), // rgba(239,68,68,0.2)
    dangerBorder = Color(0x4DEF4444), // rgba(239,68,68,0.3)
    accentPurple = Color(0xFF8B5CF6),
    accentCyan = Color(0xFF06B6D4),
    danger = Color(0xFFEF4444),
    textMain = Color(0xFFE0E7FF),
    textSub = Color(0xFF94A3B8),
    divider = Color(0x0DFFFFFF), // border-white/5
    metaBg = Color(0x2E8B5CF6), // rgba(139,92,246,0.18)
    metaText = Color(0xFFC4B5FD),
    pillBg = Color(0x1F06B6D4), // rgba(6,182,212,0.12)
    gradientStart = Color(0xFF8B5CF6),
    gradientEnd = Color(0xFF06B6D4),
    cardShadow = Color(0x4D000000), // rgba(0,0,0,0.3)
    glow = Color(0x598B5CF6), // rgba(139,92,246,0.35)
    fabGlow = Color(0x808B5CF6), // rgba(139,92,246,0.5)
    pageGlowPurple = Color(0x268B5CF6), // rgba(139,92,246,0.15)
    pageGlowCyan = Color(0x2606B6D4), // rgba(6,182,212,0.15)
)

/**
 * 浅色档：把同一套语义映射到浅底。
 * 紫色整体压深一档（`#6d28d9`），否则 `#8b5cf6` 在白色卡片上几乎看不见；
 * 「白字」语义换成近黑，否则浅底卡片上的主文字会消失。
 */
private val XcNeonLight = XcNeonColors(
    cardBg = Color(0xEBFFFFFF), // rgba(255,255,255,0.92)
    cardBorder = Color(0x386D28D9), // rgba(109,40,217,0.22)
    cardBorderPressed = Color(0xA66D28D9), // rgba(109,40,217,0.65)
    actionBorder = Color(0x406D28D9), // rgba(109,40,217,0.25)
    actionBgPressed = Color(0x336D28D9), // rgba(109,40,217,0.2)
    actionBorderPressed = Color(0x996D28D9), // rgba(109,40,217,0.6)
    dangerBorderPressed = Color(0x99DC2626), // rgba(220,38,38,0.6)
    dangerBgPressed = Color(0x2EDC2626), // rgba(220,38,38,0.18)
    dangerBorder = Color(0x4DDC2626), // rgba(220,38,38,0.3)
    accentPurple = Color(0xFF6D28D9),
    accentCyan = Color(0xFF0E7490),
    danger = Color(0xFFDC2626),
    textMain = Color(0xFF1E1B4B),
    textSub = Color(0xFF5B6478),
    divider = Color(0x0D0B0B1A),
    metaBg = Color(0x2E6D28D9),
    metaText = Color(0xFF6D28D9),
    pillBg = Color(0x1F0E7490),
    gradientStart = Color(0xFF6D28D9),
    gradientEnd = Color(0xFF0E7490),
    cardShadow = Color(0x29000000), // rgba(0,0,0,0.16)
    glow = Color(0x476D28D9), // rgba(109,40,217,0.28)
    fabGlow = Color(0x666D28D9), // rgba(109,40,217,0.4)
    pageGlowPurple = Color(0x268B5CF6),
    pageGlowCyan = Color(0x2606B6D4),
)

fun xcNeonFor(isDark: Boolean): XcNeonColors = if (isDark) XcNeonDark else XcNeonLight

/** 模块页取色的唯一入口；只在模块页内使用。 */
object XcNeon {
    val colors: XcNeonColors
        @Composable
        @ReadOnlyComposable
        get() = xcNeonFor(isInDarkTheme())

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

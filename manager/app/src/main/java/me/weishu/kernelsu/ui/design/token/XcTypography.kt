package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * XEC Fluid Glass · 字阶令牌。
 *
 * 五档，比 miuix 的字阶更紧凑：标题更重、正文更松行距，
 * 目的是让玻璃表面上的文字更"贴"而不是更"飘"。
 */
@Immutable
data class XcTypography(
    /** 28sp / 大标题。 */
    val display: TextStyle,
    /** 22sp / 页面标题。 */
    val title: TextStyle,
    /** 16sp / 分组标题。 */
    val subtitle: TextStyle,
    /** 14sp / 正文与列表项。 */
    val body: TextStyle,
    /** 12sp / 注脚、徽标。 */
    val caption: TextStyle,
)

val XcTypographyDefault = XcTypography(
    display = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    title = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.1).sp,
    ),
    subtitle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    caption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
)

val LocalXcTypography = staticCompositionLocalOf { XcTypographyDefault }

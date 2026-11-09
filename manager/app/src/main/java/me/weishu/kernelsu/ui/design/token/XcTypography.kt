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
    /** 34sp / 页面主结论（首页「内核正常运行」）。 */
    val display: TextStyle,
    /** 24sp / 页面标题、应用名。 */
    val title: TextStyle,
    /** 17sp / 分组标题。 */
    val subtitle: TextStyle,
    /** 14sp / 正文与列表项。 */
    val body: TextStyle,
    /** 12sp / 注脚、说明。 */
    val caption: TextStyle,
    /** 11sp / 分组标签（字距放大，如「设备」「工具」）。 */
    val micro: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
)

val XcTypographyDefault = XcTypography(
    display = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    ),
    title = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    subtitle = TextStyle(
        fontSize = 17.sp,
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
    micro = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    ),
)

val LocalXcTypography = staticCompositionLocalOf { XcTypographyDefault }

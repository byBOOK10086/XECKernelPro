package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * XEC Fluid Glass · 层级令牌。
 *
 * 玻璃本身靠"描边 + 内阴影"分层，投影只用于把浮层从内容里拔出来。
 */
@Immutable
data class XcElevations(
    /** 贴底玻璃：不投影。 */
    val glass: Dp,
    /** 内容卡片：极轻投影。 */
    val raised: Dp,
    /** 浮层：对话框、悬浮栏。 */
    val overlay: Dp,
    /** 按下时投影收缩到的值。 */
    val pressed: Dp,
)

val XcElevationsDefault = XcElevations(
    glass = 0.dp,
    raised = 4.dp,
    overlay = 12.dp,
    pressed = 1.dp,
)

val LocalXcElevations = staticCompositionLocalOf { XcElevationsDefault }

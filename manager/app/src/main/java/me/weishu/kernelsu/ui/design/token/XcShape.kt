package me.weishu.kernelsu.ui.design.token

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * XEC Fluid Glass · 形状令牌。
 *
 * 硬性约束：**所有矩形表面都必须取这里的值**。
 * 本设计里不存在直角矩形——`RectangleShape` 只允许出现在
 * 全屏背景、分隔线这类"非容器"用途上。
 */
@Immutable
data class XcShapes(
    /** 8dp：标签、徽标、小药丸。 */
    val xs: Shape,
    /** 12dp：列表项内嵌块、输入框、缩略图。 */
    val sm: Shape,
    /** 16dp：卡片、分组容器。 */
    val md: Shape,
    /** 22dp：大卡片、面板、底部弹层内容。 */
    val lg: Shape,
    /** 28dp：对话框、悬浮面板。 */
    val xl: Shape,
    /** 32dp：顶栏 / 导航栏 / 悬浮栏。 */
    val bar: Shape,
    /** 999dp：按钮、分段控件、开关。 */
    val pill: Shape,
    /**
     * 16dp：按下高光的圆角半径。
     *
     * 这里单独存一个 `Dp` 而不是复用上面的 [Shape]，是因为按下高光由
     * `drawRoundRect` 直接绘制、需要数值半径，并且还要按控件实际尺寸做一次夹取
     * （见 [me.weishu.kernelsu.ui.design.liquid.XcIndication]）。
     */
    val pressRadius: Dp = 16.dp,
) {
    /** 供聚焦态、按下态使用的内缩一档圆角。 */
    val inner: Shape get() = sm
}

val XcShapesDefault = XcShapes(
    xs = RoundedCornerShape(XcRadius.xs),
    sm = RoundedCornerShape(XcRadius.sm),
    md = RoundedCornerShape(XcRadius.md),
    lg = RoundedCornerShape(XcRadius.lg),
    xl = RoundedCornerShape(XcRadius.xl),
    bar = RoundedCornerShape(XcRadius.bar),
    pill = RoundedCornerShape(999.dp),
)

/**
 * 形状令牌的**数值**形式。
 *
 * 只接受 `Dp` 而不是 `Shape` 的组件必须从这里取值，否则"同一层级用同一半径"
 * 会因为这些组件无法消费 [XcShapes] 而悄悄跑偏：
 * - miuix `Card(cornerRadius = …)`、`CardDefaults.CornerRadius = 16.dp`；
 * - 已移除的 `OverlayDialog` 及其后继 `XDialog` / `XGlassDialog`（圆角统一走 [Xc.shapes]）；
 * - `miuix-squircle` 的 `squircleSurface(cornerRadius = …)` / `squircleBorder(width = …)`。
 *
 * [XcShapesDefault] 也是由这套数值构造出来的，两者不可能不一致。
 * 注意这里是全局常量而非 `CompositionLocal`：半径不随深浅色变化，
 * 做成可主题化只会让"同一个壳在不同页面圆角不同"这类 bug 有可乘之机。
 */
object XcRadius {
    /** 8dp：标签、徽标、小药丸。 */
    val xs = 8.dp
    /** 12dp：列表项内嵌块、输入框、缩略图。 */
    val sm = 12.dp
    /** 16dp：卡片、分组容器。 */
    val md = 16.dp
    /** 22dp：大卡片、面板、底部弹层内容。 */
    val lg = 22.dp
    /** 28dp：对话框、悬浮面板。 */
    val xl = 28.dp
    /** 32dp：顶栏 / 导航栏 / 悬浮栏。 */
    val bar = 32.dp
}

val LocalXcShapes = staticCompositionLocalOf { XcShapesDefault }

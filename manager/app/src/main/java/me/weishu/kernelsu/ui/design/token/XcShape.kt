package me.weishu.kernelsu.ui.design.token

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * XEC · 形状令牌（MIUI 8 设计逻辑）。
 *
 * 硬性约束：**所有矩形表面都必须取这里的值**。
 *
 * 与上一版（HyperOS 风格 8/12/16/22/28/32）的区别是全局压平：
 * MIUI 8 的容器是"白色分组面板 + 1px 分隔线"，圆角只用来削掉直角，
 * 不用来塑形。所以最大一档也只有 6dp，卡片只要 4dp。
 * 唯一的例外是 [pill]：开关、分段控件这类控件在 MIUI 8 里本来就是胶囊形。
 */
@Immutable
data class XcShapes(
    /** 2dp：标签、徽标、小药丸。 */
    val xs: Shape,
    /** 3dp：列表项内嵌块、输入框、缩略图。 */
    val sm: Shape,
    /** 4dp：卡片、分组容器（改动这一个值就覆盖全站卡片）。 */
    val md: Shape,
    /** 5dp：大卡片、面板、底部弹层内容。 */
    val lg: Shape,
    /** 6dp：对话框、悬浮面板。 */
    val xl: Shape,
    /** 4dp：顶栏 / 导航栏 / 悬浮栏（MIUI 8 的栏是方角）。 */
    val bar: Shape,
    /** 999dp：开关、分段控件这类真正的胶囊控件。 */
    val pill: Shape,
    /**
     * 2dp：按下高光的圆角半径。
     *
     * 这里单独存一个 `Dp` 而不是复用上面的 [Shape]，是因为按下高光由
     * `drawRoundRect` 直接绘制、需要数值半径，并且还要按控件实际尺寸做一次夹取
     * （见 [me.weishu.kernelsu.ui.design.liquid.XcIndication]）。
     */
    val pressRadius: Dp = 2.dp,
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
    /** 4dp：标签、徽标、小药丸。 */
    val xs = 4.dp
    /** 8dp：列表项内嵌块、输入框、缩略图。 */
    val sm = 8.dp
    /** 12dp：卡片、分组容器（设计稿口径）。 */
    val md = 12.dp
    /** 16dp：大卡片、面板、底部弹层内容。 */
    val lg = 16.dp
    /** 20dp：对话框、悬浮面板。 */
    val xl = 20.dp
    /** 12dp：顶栏 / 导航栏 / 悬浮栏。 */
    val bar = 12.dp
}

val LocalXcShapes = staticCompositionLocalOf { XcShapesDefault }

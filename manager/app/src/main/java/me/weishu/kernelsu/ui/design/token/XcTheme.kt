package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * XEC Fluid Glass · 令牌下发。
 *
 * 一次把六组令牌铺到子树里，之后任何组件只通过 [Xc] 取值，
 * 不再直接读 miuix / Material3 的配色，这是"界面不再长得像上游默认皮肤"的关键一步。
 *
 * [theme] 就是**换肤入口**：传 null 用内置默认（= 今天的样子），传一套主题则颜色/形状/
 * 玻璃参数全部来自它。主题只在最外层 provide 一次，全 App 跟着重组一次 —— 这也是
 * `staticCompositionLocalOf` 在这里正合适的原因（读不追踪，换主题本来就要整树刷新）。
 *
 * 注意：这里**只**负责设计令牌，不负责 miuix / Material3 自身的 Theme。
 * 两者是叠加关系——底层仍是 miuix 主题（保住所有既有组件不崩），
 * 上层由 XEC 令牌决定新写的壳层与容器长什么样。
 */
@Composable
fun XcTheme(
    isDark: Boolean,
    isAmoled: Boolean = false,
    theme: XcThemeData? = null,
    content: @Composable () -> Unit,
) {
    val colors = remember(isDark, isAmoled, theme) { theme?.colorsFor(isDark, isAmoled) ?: xcColorsFor(isDark, isAmoled) }
    // 形状/玻璃/动效是全局的：主题给什么用什么，不给就用默认。
    val shapes = theme?.shapes ?: XcShapesDefault
    val glass = theme?.glass ?: XcGlass.Default
    CompositionLocalProvider(
        LocalXcColors provides colors,
        LocalXcShapes provides shapes,
        LocalXcGlass provides glass,
        // 霓虹板默认 null（= 内置板子）：主题没写 neon 就完全不动模块页。
        LocalXcNeonOverride provides theme?.let { if (isDark) it.neonDark else it.neonLight },
        LocalXcMotion provides XcMotionDefault,
        LocalXcElevations provides XcElevationsDefault,
        LocalXcTypography provides XcTypographyDefault,
        content = content,
    )
}

/**
 * 令牌读取入口。
 *
 * 写法统一成 `Xc.colors.accent` / `Xc.shapes.lg`，
 * 目的是让"这个值是设计令牌"在调用点一眼可辨，而不是混进一堆裸 `16.dp`。
 */
object Xc {
    val colors: XcColors
        @Composable @ReadOnlyComposable get() = LocalXcColors.current

    val shapes: XcShapes
        @Composable @ReadOnlyComposable get() = LocalXcShapes.current

    /** 玻璃参数的**倍率**（默认全 1.0）。 */
    val glass: XcGlass
        @Composable @ReadOnlyComposable get() = LocalXcGlass.current

    val motion: XcMotion
        @Composable @ReadOnlyComposable get() = LocalXcMotion.current

    val elevations: XcElevations
        @Composable @ReadOnlyComposable get() = LocalXcElevations.current

    val typography: XcTypography
        @Composable @ReadOnlyComposable get() = LocalXcTypography.current
}

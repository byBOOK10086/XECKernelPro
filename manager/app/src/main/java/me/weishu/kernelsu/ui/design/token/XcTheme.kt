package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    /**
     * 组件不透明度的**额外**乘数（默认 1 = 不叠加）。
     *
     * 设置里那个「组件透明」开关走这一条，而不是去改主题包：主题是用户自己导入的资产，
     * 开关是运行期偏好，两者相乘才是"当前该有多透"。0 = 全透明。
     */
    componentAlpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors = remember(isDark, isAmoled, theme) { theme?.colorsFor(isDark, isAmoled) ?: xcColorsFor(isDark, isAmoled) }
    // 形状/玻璃/动效是全局的：主题给什么用什么，不给就用默认。
    val shapes = theme?.shapes ?: XcShapesDefault
    val themeGlass = theme?.glass ?: XcGlass.Default
    val glass = remember(themeGlass, componentAlpha) {
        if (componentAlpha >= 1f) {
            themeGlass
        } else {
            themeGlass.copy(surfaceAlpha = (themeGlass.surfaceAlpha * componentAlpha.coerceIn(0f, 1f)).coerceIn(0f, 1f))
        }
    }
    CompositionLocalProvider(
        LocalXcColors provides colors,
        LocalXcShapes provides shapes,
        LocalXcGlass provides glass,
        // 霓虹板默认 null（= 内置板子）：主题没写 neon 就完全不动模块页。
        LocalXcNeonOverride provides theme?.let { if (isDark) it.neonDark else it.neonLight },
        // 卡片背景默认空表（= 每张卡片都是今天的样子）。这里下发的是**整张表**，
        // 不做明暗筛选：`when`（both/dark/light）是每条背景自己的属性，得由读它的
        // 那一次组合按当时的明暗档判定，而不是在这里预先砍掉一半。
        LocalXcBlocks provides (theme?.blocks ?: emptyMap()),
        LocalXcThemeId provides (theme?.id.orEmpty()),
        LocalXcMotion provides XcMotionDefault,
        LocalXcElevations provides XcElevationsDefault,
        LocalXcTypography provides XcTypographyDefault,
        content = content,
    )
}

/**
 * 卡片（区块）背景的下发点。
 *
 * 与 [LocalXcNeonOverride] 同一套做法（`staticCompositionLocalOf` + 默认值 = 原样）：
 * 默认是空表，所以"没有主题"或"主题没写 blocks"时，任何调用点读到的都是"这张卡片没有
 * 自己的背景"，渲染侧连一次分支都不用多进 —— 既有界面零变化。
 */
val LocalXcBlocks = staticCompositionLocalOf<Map<String, XcBlockBackground>> { emptyMap() }

/**
 * 当前主题 id 的下发点（只有 [Xc.themeId] 一个读者）。
 *
 * 单独开一个 local 而不是让渲染侧去读 `XcBlocks` 的键集合：主题 id 是"缓存该不该失效"
 * 的判据，跟"有哪些区块背景"是两件事，混在一起会让缓存键依赖一堆与它无关的值。
 */
private val LocalXcThemeId = staticCompositionLocalOf { "" }

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

    /**
     * 组件底色（面板 / 行 / 按钮 / 文本域 / 分段控件都用它）。
     *
     * 与 [colors] 里的 `surface` 的差别只有一个：这里乘上了"组件透明度"
     * （[XcGlass.surfaceAlpha]）。全透明时描边与文字不变，分组边界靠 1px 线维持。
     */
    val surface: Color
        @Composable @ReadOnlyComposable get() = colors.surface.copy(alpha = surfaceAlpha)

    /** 次级组件底色（按下态、内嵌块），透明度规则同 [surface]。 */
    val surfaceMuted: Color
        @Composable @ReadOnlyComposable get() = colors.surfaceMuted.copy(alpha = surfaceAlpha)

    /** 当前生效的组件不透明度（0~1），一次算好给上面两个属性与渲染分支共用。 */
    val surfaceAlpha: Float
        @Composable @ReadOnlyComposable get() = glass.surfaceAlpha.coerceIn(0f, 1f)

    /** 全部区块背景（默认空表）。按键取用 [block]。 */
    val blocks: Map<String, XcBlockBackground>
        @Composable @ReadOnlyComposable get() = LocalXcBlocks.current

    /**
     * 当前主题的 id（`空串` = 默认外观）。
     *
     * 给区块背景的解码缓存当键用：同一个区块在"默认外观 / 主题 A / 主题 B"下是不同的图，
     * 缓存只按区块 id 记就会串图。
     */
    val themeId: String
        @Composable @ReadOnlyComposable get() = LocalXcThemeId.current

    /**
     * 取某个区块的背景；没设过就是 null。
     *
     * 这是渲染侧唯一的取值口：卡片只写自己的区块 id，不关心主题表里有没有它。
     * 这里**不**判 `when`（明暗档）—— 判定的唯一实现在 [XcBlockBackground.visibleIn]，
     * 由调用方按当时的明暗档调一次，免得"这里判了一半、那里忘了判"。
     */
    @Composable
    @ReadOnlyComposable
    fun block(id: String): XcBlockBackground? = LocalXcBlocks.current[id]
}

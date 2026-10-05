package me.weishu.kernelsu.ui.util

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.design.glass.XGlassBar
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面内容的 backdrop（模糊采样源）。
 *
 * 由页面内容自己调用一次，再交给顶栏/底栏去采样。返回 `null` 表示
 * 设备不支持 RenderEffect 或用户关掉了模糊，调用方应退回不透明底色。
 */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    // 用不透明的 surface 垫底：miuix 的 surface 在深色档是半透明(alpha 0.48)，
    // 直接 drawRect 会透出窗口的黑色背景，顶栏/玻璃块就变成「黑框」。
    val surfaceColor = MiuixTheme.colorScheme.surface.copy(alpha = 1f)
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 全局玻璃栏（顶栏 / 底栏 / 导航轨的内容底）。
 *
 * 现在只是 [XGlassBar] 的一层薄包装——玻璃画法、圆角取值、三档降级
 * 全部由 `ui/design/glass` 一处说了算（见 `XGlassSurface` 的 KDoc）。
 * 这里只保留两件壳层语义：
 *
 * 1. **全宽**：贴满可用宽度。
 * 2. **悬浮内缩** [inset]：左右缩进，让圆角露出来，形成一条悬浮的玻璃条，
 *    而不是贴边的色块。这也是"每个方形框角都要圆滑"在顶/底栏上的落点。
 *
 * 历史坑（不要再犯）：这里曾经传 `RectangleShape`。除了四角是直角，它还让
 * miuix 的 `lens()` 静默失效（`Lens.kt` 里 `shape as? CornerBasedShape ?: return`），
 * 也就是过去的「液态玻璃」其实只有模糊、从来没有折射。现在默认取
 * `Xc.shapes.bar`，并且裁剪切由 `XGlassSurface` 排在绘制**之后**，
 * 不会把折射所需的外扩取样区一起裁掉。
 *
 * @param blurActive 为 `false`（用户关掉模糊、或设备不支持模糊）时玻璃层退化为不透明底色
 *   + 1dp 渐变描边，依旧不是直角色块，也不会让栏体变成半透明。
 * @param tint 有模糊时的玻璃上覆色。默认取当前明暗档的 [Xc.colors]，需要一根"固定配色"的栏
 *   （例：首页顶栏的白毛玻璃）时在调用点覆盖。
 * @param solidTint 不能模糊时的不透明底色。默认取 `surface` 实色 —— 与调用方原本自己画的
 *   `barColor` 完全一致，叠加后看不出差别；覆盖 [tint] 时通常也要一并覆盖它，
 *   否则玻璃是白的、关掉模糊后又变回深色。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.bar,
    inset: Dp = 12.dp,
    blurActive: Boolean = true,
    tint: Color = Xc.colors.glassTint,
    solidTint: Color = MiuixTheme.colorScheme.surface.copy(alpha = 1f),
    content: @Composable () -> Unit,
) {
    // 栏是压在滚动内容最上层的，一旦半透明，文字就会和下面的列表糊在一起。
    // 所以只有「真的能模糊」时才用玻璃上覆色；其余情况由玻璃层提供一个不透明底，
    // 取 `surface` 的实色正好等于调用方原本自己画的 barColor，叠加后完全一致。
    val glassOn = blurActive && backdrop != null
    XGlassBar(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset),
        shape = shape,
        tint = if (glassOn) tint else solidTint,
        glassEnabled = glassOn,
    ) {
        content()
    }
}

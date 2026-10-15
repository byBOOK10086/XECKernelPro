package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * XEC Fluid Glass · 玻璃参数令牌。
 *
 * 这一组**全是倍率**，默认 1.0 = 今天的样子。之所以不做成绝对值：
 * 玻璃的观感是"折射 + 高光 + 暗部 + 描边"四项一起调出来的，主题只该在这个基础上
 * 做整体加减（更透/更实/更亮/更雾），一旦允许逐项写死绝对值，主题作者很难调对，
 * 我们也没法保证任何组合下文字仍然可读。
 *
 * 消费点（约 17 处，全部在玻璃壳层里）：
 *  * [me.weishu.kernelsu.ui.design.glass.XGlassSurface] —— 模糊半径、覆色浓度、亮边；
 *  * [me.weishu.kernelsu.ui.design.clear.ClearGlass] —— 透明玻璃的折射/菲涅尔/暗部/高光；
 *  * [me.weishu.kernelsu.ui.design.liquid.XcIndication] —— 按下高光的暗部与光泽。
 *
 * 取值区间由主题包校验兜住（0.0~8.0），越界一律忽略回默认。
 */
@Immutable
data class XcGlass(
    /** 模糊半径倍率：1.0 = 默认；0.0 = 完全不模糊（省电档/纯色主题常用）。 */
    val blurRadius: Float = 1f,
    /** 折射倍率：透明玻璃的位移量与边缘扭曲。 */
    val refraction: Float = 1f,
    /** 覆色浓度倍率：乘在 [XcColors.glassTint] / [XcColors.clearGlassTint] 的 alpha 上。 */
    val tintAlpha: Float = 1f,
    /** 1px 描边（亮边）强度倍率。 */
    val rimAlpha: Float = 1f,
    /** 高光（镜面反射）强度倍率。 */
    val specular: Float = 1f,
    /** 暗部（玻璃下的阴影）强度倍率。 */
    val shadeAlpha: Float = 1f,
    /** 光泽扫过（sheen）强度倍率。 */
    val sheenAlpha: Float = 1f,
) {
    companion object {
        val Default = XcGlass()
    }
}

/** 把倍率夹到合理区间：主题包已经校验过，这里再兜一层（调用点直接乘，不能再出 NaN/负值）。 */
internal fun Float.glassFactor(): Float = if (isFinite()) coerceIn(0f, 8f) else 1f

/**
 * 按倍率缩放颜色的 alpha（1.0 = 原样）。
 *
 * 玻璃的"有多深/多亮"是 alpha 决定的，所以主题只给倍率、不换色相：
 * 浅色档的透明玻璃本来就是 `Color.Transparent`（alpha 0），乘任何倍率仍是全透 —— 这是对的，
 * 那种形态靠折射与亮边塑形，不靠覆色。
 */
fun Color.scaleAlpha(factor: Float): Color =
    if (factor == 1f) this else copy(alpha = (alpha * factor).coerceIn(0f, 1f))

/** 模糊半径按倍率缩放（1.0 = 原样；0 = 完全不模糊）。 */
fun Dp.scaleBy(factor: Float): Dp =
    if (factor == 1f) this else (value * factor).coerceAtLeast(0f).dp

val LocalXcGlass = staticCompositionLocalOf { XcGlass.Default }

package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * XEC · 玻璃参数令牌（**已按 MIUI 8 归零**）。
 *
 * 这一组全是倍率。改成 MIUI 8 的扁平分组面板之后，界面里不该再出现
 * 模糊、折射、镜面高光、光泽扫过——它们和"白底 + 1px 分隔线"是两套语言。
 *
 * 所以除 [shadeAlpha] 之外全部归零：保留一点按下暗部，否则按钮/列表项
 * 按下去完全没有反馈，那已经不是克制，是坏掉了。
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
    /** 模糊半径倍率：0 = 完全不模糊（MIUI 8 不用模糊）。 */
    val blurRadius: Float = 0f,
    /** 折射倍率：0 = 不做边缘扭曲。 */
    val refraction: Float = 0f,
    /** 覆色浓度倍率：0 = 不额外染色，面板就是纯白/纯深灰。 */
    val tintAlpha: Float = 0f,
    /** 1px 描边强度倍率：0 = 不画玻璃亮边。 */
    val rimAlpha: Float = 0f,
    /** 高光（镜面反射）强度倍率：0 = 没有镜面反射。 */
    val specular: Float = 0f,
    /** 暗部（按下阴影）强度倍率：保留一点，按下要有反馈。 */
    val shadeAlpha: Float = 0.35f,
    /** 光泽扫过（sheen）强度倍率：0 = 没有扫光。 */
    val sheenAlpha: Float = 0f,
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

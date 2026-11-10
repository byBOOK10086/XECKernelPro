package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 文字排版规范（9 个角色）。
 *
 * 2026-11-11 重写：上一版只有 6 档，而页面里散落了 **22 种**手写 `fontSize`（含 11.5 /
 * 12.5 / 13.5 这种半像素），标题一律 Bold，367 处字号里只有 54 处写了行高。结果是
 * 同一层级的文字在不同页面大小不一、中文行距挤、数字忽大忽小。
 *
 * 这次的口径（与 UI 重构方案一致）：
 *  * **最高字重 600**，标题不再用 Bold；
 *  * **每个角色都带行高**，中文行高 = 字号 × 1.4–1.5；
 *  * 数字一律 `tnum`（等宽数字），右侧数值列才能竖着对齐；
 *  * 字号只许来自这里 —— 页面里不再出现裸 `fontSize = 14.sp`。
 *
 * 角色顺序即视觉层级，从大到小：
 * ```
 * heroTitle(28) > pageTitle(26) > dataNumberHero(32) > dataNumber(24)
 *   > section/listTitle(15) > caption(13) > label(12) > mono(13 等宽)
 * ```
 * 旧的 `display / title / subtitle / body / caption / micro` 保留为映射别名，
 * 老调用点会自动拿到新度量，不需要一次性改完所有页面。
 */
@Immutable
data class XcTypography(
    /** 26sp / Medium：页面名称（模块 / 授权 / 设置 的顶栏大标题）。 */
    val pageTitle: TextStyle,
    /** 28sp / SemiBold：页面主标题（首页那句结论）。 */
    val heroTitle: TextStyle,
    /** 32sp / SemiBold：唯一的主数值（评分）。 */
    val dataNumberHero: TextStyle,
    /** 24sp / SemiBold：三格数据、计数。 */
    val dataNumber: TextStyle,
    /** 15sp / Medium：分组标题。 */
    val section: TextStyle,
    /** 15sp / Regular：列表主文字。 */
    val listTitle: TextStyle,
    /** 13sp / Regular：辅助说明。 */
    val caption: TextStyle,
    /** 12sp / Medium：状态标签、徽标、日志级别。 */
    val label: TextStyle,
    /** 13sp / 等宽：版本号、包名、路径（`tnum`）。 */
    val mono: TextStyle,
) {
    // ---------------------------------------------------------------- 兼容别名
    // 这些名字是旧版 token 的字段，页面改造期间仍然会被读到；映射关系就是新规范里
    // 对应的角色，所以"没改过的页面"也会跟着新度量走。
    /** @deprecated 用 [heroTitle]。 */
    val display: TextStyle get() = heroTitle

    /** @deprecated 用 [pageTitle]。 */
    val title: TextStyle get() = pageTitle

    /** @deprecated 用 [section]。 */
    val subtitle: TextStyle get() = section

    /** @deprecated 用 [listTitle]。 */
    val body: TextStyle get() = listTitle

    /** @deprecated 用 [label]。 */
    val micro: TextStyle get() = label
}

private const val TABULAR = "tnum"

val XcTypographyDefault = XcTypography(
    pageTitle = TextStyle(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    ),
    heroTitle = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    dataNumberHero = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR,
    ),
    dataNumber = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = TABULAR,
    ),
    section = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    listTitle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    caption = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = TABULAR,
    ),
    label = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
    mono = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = TABULAR,
        letterSpacing = 0.sp,
    ),
)

val LocalXcTypography = staticCompositionLocalOf { XcTypographyDefault }

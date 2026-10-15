package me.weishu.kernelsu.ui.design.token

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 一套主题解析出来的**全部令牌**。
 *
 * [XcTheme] 拿到它以后直接 provide，于是"换主题 = 换这套值"，全 App 跟着重组一次。
 * 这也是主题系统唯一的注入点：其余 42 个文件、237 处 `Xc.colors.*` 一行都不用改。
 *
 * 约定：
 *  * 没被主题覆盖的令牌一律沿用默认值（[Default] 就是今天的样子）；
 *  * 背景图不进这里 —— 它走 `WallpaperStore` + `wallpaper_light/dark` 那条既有链路，
 *    这里只记住"这套主题想用哪张图"（应用主题时用）。
 */
@Immutable
data class XcThemeData(
    /** 空串 = 默认外观（不是主题）。 */
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val dark: XcColors = xcColorsFor(true),
    val light: XcColors = xcColorsFor(false),
    val amoled: XcColors? = null,
    val shapes: XcShapes = XcShapesDefault,
    val glass: XcGlass = XcGlass.Default,
    val neonDark: XcNeonColors? = null,
    val neonLight: XcNeonColors? = null,
    val backgroundDark: String? = null,
    val backgroundLight: String? = null,
) {
    fun colorsFor(isDark: Boolean, isAmoled: Boolean = false): XcColors = when {
        !isDark -> light
        isAmoled -> amoled ?: dark
        else -> dark
    }

    val isDefault: Boolean get() = id.isEmpty()

    companion object {
        val Default = XcThemeData()
    }
}

// ---------------------------------------------------------------- 覆盖映射
//
// 主题里写的是"令牌名 -> #AARRGGBB"。名字 -> 字段的映射只能手写（org.json 零反射的口径，
// 也让未知键天然被忽略）。新增令牌时记得在这里补一行，否则主题改不动它。

/** 颜色令牌名（与主题包/主题仓库文档保持一致）。 */
val XC_COLOR_TOKENS = listOf(
    "backdrop", "backdropScrim", "surface", "surfaceMuted", "glassTint", "clearGlassTint",
    "glassRim", "text", "textSecondary", "textMuted", "accent", "accentSoft", "water",
    "danger", "warning", "success",
)

fun XcColors.withOverrides(overrides: Map<String, Int>): XcColors {
    if (overrides.isEmpty()) return this
    fun pick(name: String, fallback: Color): Color =
        overrides[name]?.let { Color(it) } ?: fallback
    return copy(
        backdrop = pick("backdrop", backdrop),
        backdropScrim = pick("backdropScrim", backdropScrim),
        surface = pick("surface", surface),
        surfaceMuted = pick("surfaceMuted", surfaceMuted),
        glassTint = pick("glassTint", glassTint),
        clearGlassTint = pick("clearGlassTint", clearGlassTint),
        glassRim = pick("glassRim", glassRim),
        text = pick("text", text),
        textSecondary = pick("textSecondary", textSecondary),
        textMuted = pick("textMuted", textMuted),
        accent = pick("accent", accent),
        accentSoft = pick("accentSoft", accentSoft),
        water = pick("water", water),
        danger = pick("danger", danger),
        warning = pick("warning", warning),
        success = pick("success", success),
    )
}

/** 形状令牌名 -> 默认半径（dp）。主题只写想改的档位。 */
val XC_SHAPE_TOKENS = mapOf(
    "xs" to 8, "sm" to 12, "md" to 16, "lg" to 22, "xl" to 28, "bar" to 32, "pressRadius" to 16,
)

fun XcShapes.withOverrides(overrides: Map<String, Int>): XcShapes {
    if (overrides.isEmpty()) return this
    fun dp(name: String, fallback: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        overrides[name]?.let { it.dp } ?: fallback
    return XcShapes(
        xs = RoundedCornerShape(dp("xs", XcRadius.xs)),
        sm = RoundedCornerShape(dp("sm", XcRadius.sm)),
        md = RoundedCornerShape(dp("md", XcRadius.md)),
        lg = RoundedCornerShape(dp("lg", XcRadius.lg)),
        xl = RoundedCornerShape(dp("xl", XcRadius.xl)),
        bar = RoundedCornerShape(dp("bar", XcRadius.bar)),
        pill = RoundedCornerShape(999.dp),
        pressRadius = dp("pressRadius", pressRadius),
    )
}

/** 玻璃参数名 -> 默认倍率（1.0 = 今天的样子）。 */
val XC_GLASS_TOKENS = listOf(
    "blurRadius", "refraction", "tintAlpha", "rimAlpha", "specular", "shadeAlpha", "sheenAlpha",
)

fun XcGlass.withOverrides(overrides: Map<String, Float>): XcGlass {
    if (overrides.isEmpty()) return this
    fun pick(name: String, fallback: Float): Float = overrides[name]?.glassFactor() ?: fallback
    return copy(
        blurRadius = pick("blurRadius", blurRadius),
        refraction = pick("refraction", refraction),
        tintAlpha = pick("tintAlpha", tintAlpha),
        rimAlpha = pick("rimAlpha", rimAlpha),
        specular = pick("specular", specular),
        shadeAlpha = pick("shadeAlpha", shadeAlpha),
        sheenAlpha = pick("sheenAlpha", sheenAlpha),
    )
}

/**
 * 模块页霓虹板的覆盖。
 *
 * 这套板子当初**刻意**不进 [XcTheme]（`XcNeon.kt` 顶部注释），所以主题默认不碰它 ——
 * 只有主题包显式写了 `neon.dark/light` 才覆盖，写几个就覆盖几个。这样"模块页与全 App
 * 配色脱节"这件事由主题作者决定，而不是我们替他拍板。
 */
fun XcNeonColors.withOverrides(overrides: Map<String, Int>): XcNeonColors {
    if (overrides.isEmpty()) return this
    fun pick(name: String, fallback: Color): Color = overrides[name]?.let { Color(it) } ?: fallback
    return copy(
        cardBg = pick("cardBg", cardBg),
        cardBorder = pick("cardBorder", cardBorder),
        cardBorderPressed = pick("cardBorderPressed", cardBorderPressed),
        actionBorder = pick("actionBorder", actionBorder),
        actionBgPressed = pick("actionBgPressed", actionBgPressed),
        actionBorderPressed = pick("actionBorderPressed", actionBorderPressed),
        dangerBorderPressed = pick("dangerBorderPressed", dangerBorderPressed),
        dangerBgPressed = pick("dangerBgPressed", dangerBgPressed),
        dangerBorder = pick("dangerBorder", dangerBorder),
        accentPurple = pick("accentPurple", accentPurple),
        accentCyan = pick("accentCyan", accentCyan),
        danger = pick("danger", danger),
        textMain = pick("textMain", textMain),
        textSub = pick("textSub", textSub),
        divider = pick("divider", divider),
        metaBg = pick("metaBg", metaBg),
        metaText = pick("metaText", metaText),
        pillBg = pick("pillBg", pillBg),
        gradientStart = pick("gradientStart", gradientStart),
        gradientEnd = pick("gradientEnd", gradientEnd),
        cardShadow = pick("cardShadow", cardShadow),
        glow = pick("glow", glow),
        fabGlow = pick("fabGlow", fabGlow),
        pageGlowPurple = pick("pageGlowPurple", pageGlowPurple),
        pageGlowCyan = pick("pageGlowCyan", pageGlowCyan),
    )
}

/** 霓虹板的 25 个槽位名，给主题编辑器的"高级"页用。 */
val XC_NEON_TOKENS = listOf(
    "cardBg", "cardBorder", "cardBorderPressed", "actionBorder", "actionBgPressed",
    "actionBorderPressed", "dangerBorderPressed", "dangerBgPressed", "dangerBorder",
    "accentPurple", "accentCyan", "danger", "textMain", "textSub", "divider",
    "metaBg", "metaText", "pillBg", "gradientStart", "gradientEnd",
    "cardShadow", "glow", "fabGlow", "pageGlowPurple", "pageGlowCyan",
)

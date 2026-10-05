package me.weishu.kernelsu.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import me.weishu.kernelsu.ui.design.liquid.XcIndication
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.webui.MonetColorsProvider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun MiuixKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec
    // XEC 语义色（由外层的 XcTheme 提供，已按 isDark / isAmoled 解析完毕）。
    val xc = Xc.colors

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec.effectiveFor(colorStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    // 强调色的种子。优先级：用户显式选色 > 系统 Monet 取色 > XEC teal。
    // 这个值现在**任何档位**都会被真正消费（见下面的 accent 派生），
    // 而不是像上游那样只在 Monet 档生效。
    val accentSeed: Color = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        appSettings.colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> xc.accent
    }

    // ── 强调色系自己派生，不再依赖 miuix 的档位 ─────────────────────────────
    //
    // 这是"一眼看去还是 KernelSU"的第二个根因。miuix 的 ThemeController 只在
    // 三个 Monet 档里消费 keyColor；默认档（SYSTEM / LIGHT / DARK）走的是它
    // 内置的 lightColorScheme() / darkColorScheme()，强调色恒为上游那个蓝。
    // 后果：全 App 的按钮、开关、滑块、选中态都是 KernelSU 蓝，
    // 而且用户在调色屏选的 keyColor 在非 Monet 档下完全失效。
    //
    // miuix 自己的生成器 colorsFromSeed(...) 是 internal，跨模块拿不到；
    // 但本模块本来就在用公开的 materialkolor（调色屏的预览卡就是它生成的），
    // 于是这里用同一个种子自行派生整套强调色，再逐槽写回 Colors。
    // 对比度由 materialkolor 保证，不需要我们再算一遍。
    val accent = rememberDynamicColorScheme(
        seedColor = accentSeed,
        isDark = darkTheme,
        style = colorStyle,
        specVersion = colorSpec.effectiveFor(colorStyle),
    )
    // miuix 的禁用态强调色是"primary 38% 压在一个不透明的底上"。这里照做，
    // 底换成 XEC 的面板色——直接用半透明色会透出背景图，糊成脏色。
    val disabledAccent = accent.primary.over(xc.surface, 0.38f)

    val controller = ThemeController(
        when (appSettings.colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = accentSeed,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    MiuixTheme(
        controller = controller,
        content = {
            val scheme = MiuixTheme.colorScheme

            // XEC 接管调色板。分两层覆写，顺序不能颠倒：先换强调色系，
            // 再在其上盖中性色。
            //
            // miuix 只会算出"上游库默认皮肤"的那套灰阶，这是界面一眼看去像
            // KernelSU 的根因；而它的 primary 一族在默认档恒为上游蓝，是这个
            // 问题的第二面。两层都换掉之后才是一套完整的 XEC 皮肤。
            //
            // 第一层：强调色系，由 materialkolor 从 accentSeed 派生（见文件上方）。
            val accented = scheme.copy(
                primary = accent.primary,
                onPrimary = accent.onPrimary,
                // miuix 上游把这个槽位映射到 Material 的 primaryFixed（同族高对比
                // 变体），M3 里与之对应的是 inversePrimary。它同时还是 WebUI 的
                // --inversePrimary，所以不能不换。
                primaryVariant = accent.inversePrimary,
                primaryContainer = accent.primaryContainer,
                onPrimaryContainer = accent.onPrimaryContainer,
                disabledPrimary = disabledAccent,
                disabledPrimaryButton = disabledAccent,
                disabledPrimarySlider = disabledAccent,
                disabledOnPrimary = accent.onPrimary.over(disabledAccent, 0.38f),
                disabledOnPrimaryButton = accent.onPrimary.over(disabledAccent, 0.60f),
                secondary = accent.secondary,
                onSecondary = accent.onSecondary,
                secondaryContainer = accent.secondaryContainer,
                onSecondaryContainer = accent.onSecondaryContainer,
                // miuix 的次级槽位（secondaryVariant 一族）上游是从 surfaceContainer
                // 系派生的，不换掉会残留上游皮肤的蓝灰。
                secondaryVariant = xc.surfaceMuted.copy(alpha = 0.70f),
                onSecondaryVariant = xc.text,
                secondaryContainerVariant = xc.surfaceMuted.copy(alpha = 0.82f),
                onSecondaryContainerVariant = xc.textMuted,
                tertiaryContainer = accent.tertiaryContainer,
                onTertiaryContainer = accent.onTertiaryContainer,
                tertiaryContainerVariant = accent.onTertiaryContainer,
                error = accent.error,
                onError = accent.onError,
                errorContainer = accent.errorContainer,
                onErrorContainer = accent.onErrorContainer,
                // 滑块：圆点用强调色，滑轨用 20% 强调色压底。
                sliderKeyPoint = accent.primary,
                sliderKeyPointForeground = accent.surfaceContainerHigh,
                sliderBackground = accent.primary.over(xc.surface, 0.20f),
                // miuix 用这个槽位画 SmallTitle（设置页的分组小标题），
                // 上游映射就是 primary，不换掉会是上游蓝。
                onBackgroundVariant = accent.primary,
            )

            // 第二层：中性色。
            //
            // 「底」是全 App 唯一允许半透明的一层：它下面不是系统窗口，而是
            // MainActivity 铺满全屏的那张背景图（`bg_lkm_active` / `bg_not_patched`）。
            //
            // 这里 alpha 取 0.80 是刻意的取舍：两张背景图的平均亮度都接近纯白
            // （实测 luma 227 / 237），底全透明就变成"白底上的浅色字"，一个字都
            // 读不清；底全不透明又会把背景图彻底盖死，等于把那个功能删了。
            // 0.80 让背景图透出约两成，作为一层很淡的肌理存在，文字对比度仍然安全。
            // 想让背景图更明显，只需要调这一个数字。
            //
            // AMOLED 档位例外：纯黑底是这个档位的全部意义，直接取实色把图盖掉。
            val amoled = appSettings.colorMode.isAmoled
            val colors = if (darkTheme) {
                accented.copy(
                    // 底：XEC 深墨绿灰，整屏最底层。
                    background = if (amoled) xc.backdrop else xc.backdrop.copy(alpha = 0.80f),
                    onBackground = xc.text,
                    // 容器：各档容器全部来自 XEC 中性色，越高的档位越实。
                    surface = xc.surface.copy(alpha = 0.48f),
                    surfaceVariant = xc.surfaceMuted.copy(alpha = 0.48f),
                    surfaceContainer = xc.surfaceMuted.copy(alpha = 0.58f),
                    surfaceContainerHigh = xc.surfaceMuted.copy(alpha = 0.70f),
                    surfaceContainerHighest = xc.surfaceMuted.copy(alpha = 0.82f),
                    // 注意：miuix 的 Colors 只提供上面这三档容器槽位，
                    // **没有** Material 那套 surfaceContainerLow / surfaceContainerLowest /
                    // surfaceDim / surfaceBright / surfaceTint。写进去是硬编译错误
                    // （`No parameter with name ... found`，CI 已实测）。
                    // 比 surfaceContainer 更低的层级由 XEC 自己的 XGlassSurface
                    // 玻璃层来表达，不需要在这里补槽位。
                    // 文本：三档灰阶统一到 XEC，避免混入上游默认灰。
                    onSurface = xc.text,
                    onSurfaceSecondary = xc.textSecondary,
                    // miuix 没有单一的 onSurfaceVariant，只有下面这组细分槽位
                    // （Summary / Actions 两档），这里用它们覆盖"次级文本"。
                    onSurfaceVariantSummary = xc.textMuted,
                    onSurfaceVariantActions = xc.textMuted.copy(alpha = 0.82f),
                    onSurfaceContainer = xc.text,
                    onSurfaceContainerHigh = xc.textSecondary,
                    disabledOnSurface = xc.textMuted.copy(alpha = 0.38f),
                    // 描边与分隔：用玻璃描边色，没有模糊的设备上也能分层。
                    outline = xc.glassRim,
                    dividerLine = xc.glassRim.copy(alpha = 0.55f),
                    // 遮罩。（miuix 无 outlineVariant / inverseSurface / inverseOnSurface 槽位，
                    // 描边档位统一收在 outline + dividerLine 这两条上。）
                    windowDimming = xc.backdropScrim,
                )
            } else {
                // 浅色档同样接管中性色，不再沿用 miuix 的灰阶。
                //
                // 上游那套浅色灰阶是"一眼看去还是 KernelSU"的第三个来源：它和
                // 深色档出自同一套蓝灰偏色，只换强调色压不住——底、容器、分隔线、
                // 次级文字全是从它派生的。XEC 的浅色取值（backdrop #F2F5F6 /
                // surface 纯白 / text 近黑）与深色档共用同一套语义槽位，规则一致，
                // 只是把值换掉，所以这里是深色分支的镜像，不是第二套设计。
                //
                // alpha 与深色档取同一组：底 0.80 让背景图透出约两成，容器越高的
                // 档位越实，层次仍然靠玻璃描边说话。
                accented.copy(
                    background = xc.backdrop.copy(alpha = 0.80f),
                    onBackground = xc.text,
                    surface = xc.surface.copy(alpha = 0.62f),
                    surfaceVariant = xc.surfaceMuted.copy(alpha = 0.62f),
                    surfaceContainer = xc.surfaceMuted.copy(alpha = 0.72f),
                    surfaceContainerHigh = xc.surfaceMuted.copy(alpha = 0.82f),
                    surfaceContainerHighest = xc.surfaceMuted.copy(alpha = 0.90f),
                    onSurface = xc.text,
                    onSurfaceSecondary = xc.textSecondary,
                    onSurfaceVariantSummary = xc.textMuted,
                    onSurfaceVariantActions = xc.textMuted.copy(alpha = 0.82f),
                    onSurfaceContainer = xc.text,
                    onSurfaceContainerHigh = xc.textSecondary,
                    disabledOnSurface = xc.textMuted.copy(alpha = 0.38f),
                    outline = xc.glassRim,
                    dividerLine = xc.glassRim.copy(alpha = 0.55f),
                    windowDimming = xc.backdropScrim,
                )
            }
            MiuixTheme(
                colors = colors,
                content = {
                    LaunchedEffect(darkTheme) {
                        val window = (context as? Activity)?.window ?: return@LaunchedEffect
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                    }
                    MonetColorsProvider.UpdateCss()
                    // 用 XEC 的圆角高光顶掉 miuix 默认那个方块高光。
                    // 必须在 MiuixTheme 的 content 里下发——miuix 自己会在
                    // 主题内部重新 provide 一次 LocalIndication，在外面覆盖会被冲掉。
                    val indicationColor = Xc.colors.text
                    val pressRadius = Xc.shapes.pressRadius
                    val indication = remember(indicationColor, pressRadius) {
                        XcIndication(color = indicationColor, radius = pressRadius)
                    }
                    CompositionLocalProvider(
                        LocalIndication provides indication,
                        LocalContentColor provides MiuixTheme.colorScheme.onBackground,
                    ) {
                        content()
                    }
                }
            )
        }
    )
}

/**
 * 把 [this] 按 [alpha] 压到一个不透明的底上，返回一个本身不透明的颜色。
 *
 * 用途：miuix 的禁用态强调色（`disabledPrimary` 一族）语义上是"primary 38%
 * 压在不透明的底上"。如果直接用 `primary.copy(alpha = 0.38f)`，底色会被
 * 换成卡片自己的半透明容器，再往下还有背景图——混合出来的颜色不可控，
 * 深浅档表现还不一致。所以这里真的做一次合算，把结果钉成实色。
 */
private fun Color.over(base: Color, alpha: Float): Color = Color(
    red = red * alpha + base.red * (1f - alpha),
    green = green * alpha + base.green * (1f - alpha),
    blue = blue * alpha + base.blue * (1f - alpha),
    alpha = 1f,
)

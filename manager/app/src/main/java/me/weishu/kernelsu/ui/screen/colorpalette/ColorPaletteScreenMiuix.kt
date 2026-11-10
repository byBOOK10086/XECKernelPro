package me.weishu.kernelsu.ui.screen.colorpalette

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.delay
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.bottombar.useNavigationRail
import me.weishu.kernelsu.ui.component.miuix.ScaleDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Dropdown
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8Seekbar
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.design.panel.Xc8Value
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcRadius
import me.weishu.kernelsu.ui.design.token.xcColorsFor
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.keyColorOptions
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ColorPaletteScreenMiuix(
    state: ColorPaletteUiState,
    actions: ColorPaletteScreenActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlurState = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlurState)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val uiState = state.uiState
    val currentColorMode = state.currentColorMode
    val isDark = currentColorMode.isDark || currentColorMode.isSystem && isSystemInDarkTheme()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings_theme),
                    navigationIcon = {
                        IconButton(
                            onClick = actions.onBack
                        ) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        val showScaleDialog = rememberSaveable { mutableStateOf(false) }

        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    ThemePreviewCardMiuix(
                        keyColor = uiState.keyColor,
                        isDark = isDark,
                        miuixMonet = uiState.miuixMonet,
                        enableFloatingBottomBar = uiState.enableFloatingBottomBar,
                        enableFloatingBottomBarBlur = uiState.enableFloatingBottomBarBlur,
                        paletteStyle = state.currentPaletteStyle,
                        colorSpec = state.currentColorSpec,
                    )
                    Spacer(modifier = Modifier.height(72.dp))

                    val themeItems = listOf(
                        stringResource(id = R.string.settings_theme_mode_system),
                        stringResource(id = R.string.settings_theme_mode_light),
                        stringResource(id = R.string.settings_theme_mode_dark),
                    )
                    TabRow(
                        tabs = themeItems,
                        selectedTabIndex = (if (uiState.themeMode >= 3) uiState.themeMode - 3 else uiState.themeMode).coerceIn(0, 2),
                        onTabSelected = { index ->
                            actions.onSetThemeMode(index)
                        },
                    )

                    // 配色卡：开关是面板行，下面三个下拉（配色/风格/规范）用 Xc8Dropdown，
                    // 点开是 MIUI 8 的居中选择框（不是 miuix 那种贴着行展开的浮层）。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(id = R.string.settings_monet),
                            leading = Icons.Rounded.Wallpaper,
                            onClick = { actions.onSetMiuixMonet(!uiState.miuixMonet) },
                            trailing = {
                                Xc8Switch(
                                    checked = uiState.miuixMonet,
                                    onCheckedChange = actions.onSetMiuixMonet,
                                )
                            },
                        )

                        AnimatedVisibility(
                            visible = uiState.miuixMonet
                        ) {
                            Column {
                                val colorItems = listOf(
                                    stringResource(id = R.string.settings_key_color_default),
                                    stringResource(id = R.string.color_red),
                                    stringResource(id = R.string.color_pink),
                                    stringResource(id = R.string.color_purple),
                                    stringResource(id = R.string.color_deep_purple),
                                    stringResource(id = R.string.color_indigo),
                                    stringResource(id = R.string.color_blue),
                                    stringResource(id = R.string.color_cyan),
                                    stringResource(id = R.string.color_teal),
                                    stringResource(id = R.string.color_green),
                                    stringResource(id = R.string.color_yellow),
                                    stringResource(id = R.string.color_amber),
                                    stringResource(id = R.string.color_orange),
                                    stringResource(id = R.string.color_brown),
                                    stringResource(id = R.string.color_blue_grey),
                                    stringResource(id = R.string.color_sakura),
                                )
                                val colorValues = listOf(0) + keyColorOptions
                                Xc8Dropdown(
                                    title = stringResource(id = R.string.settings_key_color),
                                    leading = Icons.Rounded.Colorize,
                                    items = colorItems,
                                    selectedIndex = colorValues.indexOf(uiState.keyColor).takeIf { it >= 0 } ?: 0,
                                    cancelText = stringResource(android.R.string.cancel),
                                    onSelectedIndexChange = { index ->
                                        actions.onSetKeyColor(colorValues[index])
                                    }
                                )

                                AnimatedVisibility(
                                    visible = uiState.keyColor != 0
                                ) {
                                    Column {
                                        val styles = PaletteStyle.entries
                                        Xc8Dropdown(
                                            title = stringResource(R.string.settings_color_style),
                                            leading = Icons.Rounded.Style,
                                            items = styles.map { it.name },
                                            selectedIndex = styles.indexOfFirst { it.name == uiState.colorStyle }.coerceAtLeast(0),
                                            cancelText = stringResource(android.R.string.cancel),
                                            onSelectedIndexChange = { index ->
                                                actions.onSetColorStyle(styles[index].name)
                                            }
                                        )

                                        val specs = ColorSpec.SpecVersion.entries
                                        Xc8Dropdown(
                                            title = stringResource(R.string.settings_color_spec),
                                            leading = Icons.Rounded.DesignServices,
                                            items = specs.map { it.name },
                                            selectedIndex = specs.indexOfFirst { it.name == uiState.colorSpec }.coerceAtLeast(0),
                                            cancelText = stringResource(android.R.string.cancel),
                                            onSelectedIndexChange = { index ->
                                                actions.onSetColorSpec(specs[index].name)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 四个开关整卡换成一块面板；玻璃开关仍由 AnimatedVisibility 控制显隐，
                    // 只是把它的内容从 miuix 开关换成了面板行。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_enable_blur),
                                subtitle = stringResource(id = R.string.settings_enable_blur_summary),
                                leading = Icons.Rounded.BlurOn,
                                onClick = { actions.onSetEnableBlur(!uiState.enableBlur) },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.enableBlur,
                                        onCheckedChange = actions.onSetEnableBlur,
                                    )
                                },
                            )
                        }
                        Xc8Row(
                            title = stringResource(id = R.string.settings_floating_bottom_bar),
                            subtitle = stringResource(id = R.string.settings_floating_bottom_bar_summary),
                            leading = Icons.Rounded.CallToAction,
                            onClick = {
                                actions.onSetEnableFloatingBottomBar(!uiState.enableFloatingBottomBar)
                            },
                            trailing = {
                                Xc8Switch(
                                    checked = uiState.enableFloatingBottomBar,
                                    onCheckedChange = actions.onSetEnableFloatingBottomBar,
                                )
                            },
                        )
                        AnimatedVisibility(visible = uiState.enableFloatingBottomBar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_enable_glass),
                                subtitle = stringResource(id = R.string.settings_enable_glass_summary),
                                leading = Icons.Rounded.WaterDrop,
                                onClick = {
                                    actions.onSetEnableFloatingBottomBarBlur(!uiState.enableFloatingBottomBarBlur)
                                },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.enableFloatingBottomBarBlur,
                                        onCheckedChange = actions.onSetEnableFloatingBottomBarBlur,
                                    )
                                },
                            )
                        }
                        Xc8Row(
                            title = stringResource(id = R.string.settings_navigation_badge),
                            subtitle = stringResource(id = R.string.settings_navigation_badge_summary),
                            leading = Icons.Rounded.Pin,
                            onClick = {
                                actions.onSetEnableNavigationBadge(!uiState.enableNavigationBadge)
                            },
                            trailing = {
                                Xc8Switch(
                                    checked = uiState.enableNavigationBadge,
                                    onCheckedChange = actions.onSetEnableNavigationBadge,
                                )
                            },
                        )
                    }

                    // 显示类设置：预测返回 + 页面缩放。页面缩放原来是"点行才展开滑杆"
                    // （ArrowPreference.bottomAction），面板族没有展开插槽，改成同一块
                    // 面板里常驻两行：标题/百分比 + 滑杆。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_enable_predictive_back),
                                subtitle = stringResource(id = R.string.settings_enable_predictive_back_summary),
                                leading = Icons.AutoMirrored.Rounded.MenuOpen,
                                onClick = {
                                    actions.onSetEnablePredictiveBack(!uiState.enablePredictiveBack)
                                },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.enablePredictiveBack,
                                        onCheckedChange = actions.onSetEnablePredictiveBack,
                                    )
                                },
                            )
                        }

                        var sliderValue by remember(uiState.pageScale) { mutableFloatStateOf(uiState.pageScale) }
                        // 点行仍打开精确档位弹窗，滑杆负责快速调；两者写的是同一个 pageScale。
                        Xc8Row(
                            title = stringResource(id = R.string.settings_page_scale),
                            subtitle = stringResource(id = R.string.settings_page_scale_summary),
                            leading = Icons.Rounded.AspectRatio,
                            onClick = { showScaleDialog.value = !showScaleDialog.value },
                            trailing = {
                                Xc8Value(text = "${(sliderValue * 100).toInt()}%")
                            },
                        )
                        // 第二行只放滑杆。Xc8Seekbar 只认 0..1，页面缩放的 0.8..1.1
                        // 在这里做线性映射。它只有拖动回调、没有"松手"回调：拖动过程
                        // 只改本地百分比，停手 120ms 后再写回，等价于原来的
                        // Slider.onValueChangeFinished，避免整棵树按帧改 density。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Xc.surface)
                                .padding(horizontal = 15.dp, vertical = 6.dp),
                        ) {
                            Xc8Seekbar(
                                value = ((sliderValue - 0.8f) / 0.3f).coerceIn(0f, 1f),
                                onValueChange = { fraction ->
                                    sliderValue = 0.8f + fraction * 0.3f
                                },
                            )
                        }
                        LaunchedEffect(sliderValue) {
                            if (sliderValue == uiState.pageScale) return@LaunchedEffect
                            delay(120)
                            actions.onSetPageScale(sliderValue)
                        }
                    }
                    // 弹窗不是行，挪到面板外：面板会裁切内容，对话框需要自己的全屏遮罩。
                    ScaleDialog(
                        show = showScaleDialog.value,
                        onDismissRequest = { showScaleDialog.value = false },
                        volumeState = { uiState.pageScale },
                        onVolumeChange = {
                            actions.onSetPageScale(it)
                        }
                    )
                }
                item {
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                    12.dp
                        )
                    )
                }
            }
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ThemePreviewCardMiuix(
    keyColor: Int,
    isDark: Boolean,
    miuixMonet: Boolean,
    enableFloatingBottomBar: Boolean = false,
    enableFloatingBottomBarBlur: Boolean = false,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val screenRatio = screenWidth / screenHeight
    val useRail = useNavigationRail(enableFloatingBottomBar)

    val seedColor = if (keyColor == 0) colorScheme.primary else Color(keyColor)
    val effectiveStyle = if (keyColor == 0) PaletteStyle.TonalSpot else paletteStyle
    val effectiveSpec = if (keyColor == 0) ColorSpec.SpecVersion.Default else colorSpec
    val dynamicCs = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = effectiveStyle,
        specVersion = effectiveSpec,
    )

    val bgColor = if (miuixMonet) dynamicCs.background else colorScheme.surface
    val textColor = if (miuixMonet) dynamicCs.onSurface else colorScheme.onBackground
    // 这张卡模拟的是首页那张状态卡，而首页那张卡用的是**语义绿**（安装成功），
    // 不跟随强调色——所以这里也固定取语义令牌。取值走 xcColorsFor(isDark) 而不是
    // Xc.colors：后者是"当前主题"的色，预览浅色档时会串成深色。
    val accentCardColor = xcColorsFor(isDark).successTint
    val cardColor = if (miuixMonet) dynamicCs.surfaceContainerHighest else colorScheme.surfaceVariant
    val navBarColor = if (miuixMonet) dynamicCs.surfaceContainer else colorScheme.surface
    val iconColor = if (miuixMonet) dynamicCs.primary else colorScheme.primary
    val navSelectedColor = colorScheme.onSurfaceContainer
    val navUnselectedColor = colorScheme.onSurfaceContainer.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(screenRatio)
                .clip(Xc.shapes.lg)
                .background(bgColor)
                .border(1.dp, colorScheme.outline, Xc.shapes.lg)
        ) {
            val content = @Composable {
                Column {
                    Row(
                        modifier = Modifier
                            .height(if (useRail) 36.dp else 48.dp)
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = if (useRail) 12.dp else 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.app_name),
                            fontSize = 13.sp,
                            color = textColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(45.dp)
                            .padding(horizontal = 8.dp)
                            .clip(Xc.shapes.xs)
                            .background(accentCardColor)
                    )

                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        val smallCardHeight = 12.dp
                        val smallCardCount = when {
                            maxHeight >= 96.dp -> 2
                            maxHeight >= 72.dp -> 1
                            else -> 0
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(Xc.shapes.xs)
                                    .background(cardColor)
                            )
                            repeat(smallCardCount) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(smallCardHeight)
                                        .clip(Xc.shapes.xs)
                                        .background(cardColor)
                                )
                            }
                        }
                    }
                }
            }

            if (useRail) {
                Row {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(30.dp)
                            // 侧栏示意图：只圆右侧两角，避免与内容区交界处出现直角。
                            .background(navBarColor, RoundedCornerShape(topEnd = XcRadius.xs, bottomEnd = XcRadius.xs)),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) navSelectedColor else navUnselectedColor)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f))
                    )
                    Box(modifier = Modifier.weight(1f)) { content() }
                }
            } else {
                content()
            }

            if (!useRail && enableFloatingBottomBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(Xc.shapes.bar)
                            .background(
                                if (enableFloatingBottomBarBlur) navBarColor.copy(alpha = 0.5f)
                                else navBarColor
                            )
                            .border(0.5.dp, textColor.copy(alpha = 0.1f), Xc.shapes.bar)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (it == 0) iconColor else textColor)
                            )
                        }
                    }
                }
            } else if (!useRail) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f))
                    )
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .fillMaxWidth()
                            // 底栏示意图：只圆上方两角，避免与内容区交界处出现直角。
                            .background(navBarColor, RoundedCornerShape(topStart = XcRadius.xs, topEnd = XcRadius.xs))
                            .padding(top = 2.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) navSelectedColor else navUnselectedColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

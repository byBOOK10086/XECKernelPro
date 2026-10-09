package me.weishu.kernelsu.ui.screen.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.screen.settings.SettingsUiState
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.WallpaperStore
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 自定义背景页的界面层。
 *
 * 与其它设置页保持同一套壳：BlurredBar 顶栏 + 液态玻璃卡片 + 底部安全区。
 * 两档各占一张卡，卡里同时给出缩略图、"当前用的是哪一档"的一句话摘要和操作按钮——
 * 用户改完一档往往要确认另一档没被牵连，把状态直接摆在按钮上方比藏在摘要里更省事。
 */
@Composable
fun WallpaperScreenMiuix(
    uiState: SettingsUiState,
    busySlot: String?,
    onBack: () -> Unit,
    onPickLight: () -> Unit,
    onPickDark: () -> Unit,
    onSolidLight: (String) -> Unit,
    onSolidDark: (String) -> Unit,
    onClearLight: () -> Unit,
    onClearDark: () -> Unit,
    onClearAll: () -> Unit,
    onBlurChange: (Int) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val busy = busySlot != null
    val lightCustom = uiState.wallpaperLight.isNotEmpty()
    val darkCustom = uiState.wallpaperDark.isNotEmpty()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.wallpaper_title),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
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
                    Spacer(Modifier.height(12.dp))
                    WallpaperSlotCard(
                        title = stringResource(R.string.wallpaper_light_title),
                        summary = slotSummary(
                            name = uiState.wallpaperLight,
                            slotBusy = busySlot == WallpaperStore.LIGHT,
                        ),
                        name = uiState.wallpaperLight,
                        enabled = !busy,
                        onPick = onPickLight,
                        onSolid = onSolidLight,
                        onClear = onClearLight,
                        backdrop = backdrop,
                    )
                    WallpaperSlotCard(
                        title = stringResource(R.string.wallpaper_dark_title),
                        summary = slotSummary(
                            name = uiState.wallpaperDark,
                            slotBusy = busySlot == WallpaperStore.DARK,
                        ),
                        name = uiState.wallpaperDark,
                        enabled = !busy,
                        onPick = onPickDark,
                        onSolid = onSolidDark,
                        onClear = onClearDark,
                        backdrop = backdrop,
                    )

                    WallpaperBlurCard(
                        percent = uiState.wallpaperBlur,
                        enabled = true,
                        backdrop = backdrop,
                        onChange = onBlurChange,
                    )

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.wallpaper_note_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Xc.colors.text,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.wallpaper_note),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Xc.colors.textSecondary,
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        TextButton(
                            text = stringResource(R.string.wallpaper_clear_all),
                            onClick = onClearAll,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && (lightCustom || darkCustom),
                        )
                    }

                    Spacer(
                        Modifier.height(
                            12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

/**
 * 槽位摘要：空闲说"当前用的是哪一档"，处理中就把这句话换成进度提示，避免按钮看起来没反应。
 *
 * 纯色档要单独说清楚"固定"这件事——它和"选了张图"在界面上是同一行文案的位置，
 * 但语义不同：图会被内置池的抽签挤掉吗？不会；纯色同理，而且它就是用户要的"别再轮换"。
 */
@Composable
private fun slotSummary(name: String, slotBusy: Boolean): String = when {
    slotBusy -> stringResource(R.string.wallpaper_importing)
    name == WallpaperStore.SOLID_WHITE -> stringResource(R.string.wallpaper_slot_solid_white)
    name == WallpaperStore.SOLID_BLACK -> stringResource(R.string.wallpaper_slot_solid_black)
    name.isNotEmpty() -> stringResource(R.string.wallpaper_slot_custom)
    else -> stringResource(R.string.wallpaper_slot_builtin)
}

/** 一档背景的卡片：缩略图 + 摘要 + 选择/恢复。 */
@Composable
private fun WallpaperSlotCard(
    title: String,
    summary: String,
    name: String,
    enabled: Boolean,
    onPick: () -> Unit,
    onSolid: (String) -> Unit,
    onClear: () -> Unit,
    backdrop: LayerBackdrop?,
) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WallpaperThumbnail(name = name)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Xc.colors.text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        color = Xc.colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(
                        if (name.isEmpty()) R.string.wallpaper_pick else R.string.wallpaper_replace
                    ),
                    onClick = onPick,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                if (name.isNotEmpty()) {
                    TextButton(
                        text = stringResource(R.string.wallpaper_clear),
                        onClick = onClear,
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 纯色档与选图并排：两者是"这一档用什么"的两种答案，放在同一张卡里才能一眼比出来。
            // 当前生效的那个用主色按钮，未生效的保持默认样式——不用文字标注也能看出选中的是哪个。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.wallpaper_solid_white),
                    onClick = { onSolid(WallpaperStore.SOLID_WHITE) },
                    enabled = enabled,
                    colors = if (name == WallpaperStore.SOLID_WHITE) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                )
                TextButton(
                    text = stringResource(R.string.wallpaper_solid_black),
                    onClick = { onSolid(WallpaperStore.SOLID_BLACK) },
                    enabled = enabled,
                    colors = if (name == WallpaperStore.SOLID_BLACK) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                )
            }
        }
    }
}

/**
 * 缩略图：按 [WallpaperStore.PREVIEW_MAX_EDGE] 解码，绝不为一个小方块解整张图。
 *
 * 解码放在 IO 线程并由 `produceState` 承载，重组的瞬间不阻塞主线程；占位文案用的是
 * "内置"两个字，让"这一档还没自定义"这件事在缩略图位置就能读出来。
 */
@Composable
private fun WallpaperThumbnail(name: String) {
    val context = LocalContext.current
    val solidArgb = WallpaperStore.solidArgb(name)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, name) {
        value = if (name.isEmpty() || solidArgb != null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                WallpaperStore.decode(
                    WallpaperStore.fileOf(context, name),
                    WallpaperStore.PREVIEW_MAX_EDGE,
                )?.asImageBitmap()
            }
        }
    }

    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(Xc.shapes.sm)
            // 纯色档直接把缩略图本身画成那个颜色——比"写两个字"更接近用户点下去会看到的结果。
            .background(solidArgb?.let { Color(it) } ?: Xc.colors.surfaceMuted.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        // 纯色档：色块已由 Box 的 background 画好，内容一律不画（既不解码也不出占位文字）。
        // 两种情况分开写而不是空 if 分支：空分支在 Kotlin 里合法但读起来像漏写。
        if (solidArgb == null) {
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = stringResource(R.string.wallpaper_builtin_short),
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * 背景模糊卡片：一条拖动条 + 一行百分比。
 *
 * 百分比而不是 dp：用户脑子里没有"25dp 是糊到什么程度"，但"糊一半"是直觉。
 * 拖动过程中只改本地状态、松手才写设置（[onValueChangeFinished]）——写设置会触发
 * SharedPreferences 监听 → 根层重组 → 整屏重新录制 backdrop，跟着每一帧拖会明显掉帧。
 */
@Composable
private fun WallpaperBlurCard(
    percent: Int,
    enabled: Boolean,
    backdrop: LayerBackdrop?,
    onChange: (Int) -> Unit,
) {
    var local by remember(percent) { mutableFloatStateOf(percent / 100f) }

    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.wallpaper_blur_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Xc.colors.text,
                    )
                    Text(
                        text = stringResource(R.string.wallpaper_blur_summary),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Xc.colors.textSecondary,
                    )
                }
                Text(
                    text = "${(local * 100).roundToInt()}%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onChange((local * 100).roundToInt()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                valueRange = 0f..1f,
                showKeyPoints = true,
                keyPoints = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                magnetThreshold = 0.02f,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }
    }
}

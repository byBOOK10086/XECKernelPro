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
import androidx.compose.runtime.produceState
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
    onClearLight: () -> Unit,
    onClearDark: () -> Unit,
    onClearAll: () -> Unit,
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
                            custom = lightCustom,
                            slotBusy = busySlot == WallpaperStore.LIGHT,
                        ),
                        name = uiState.wallpaperLight,
                        enabled = !busy,
                        onPick = onPickLight,
                        onClear = onClearLight,
                        backdrop = backdrop,
                    )
                    WallpaperSlotCard(
                        title = stringResource(R.string.wallpaper_dark_title),
                        summary = slotSummary(
                            custom = darkCustom,
                            slotBusy = busySlot == WallpaperStore.DARK,
                        ),
                        name = uiState.wallpaperDark,
                        enabled = !busy,
                        onPick = onPickDark,
                        onClear = onClearDark,
                        backdrop = backdrop,
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

/** 槽位摘要：空闲说"当前用的是哪一档"，处理中就把这句话换成进度提示，避免按钮看起来没反应。 */
@Composable
private fun slotSummary(custom: Boolean, slotBusy: Boolean): String = when {
    slotBusy -> stringResource(R.string.wallpaper_importing)
    custom -> stringResource(R.string.wallpaper_slot_custom)
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
    val bitmap by produceState<ImageBitmap?>(initialValue = null, name) {
        value = if (name.isEmpty()) {
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
            .background(Xc.colors.surfaceMuted.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
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

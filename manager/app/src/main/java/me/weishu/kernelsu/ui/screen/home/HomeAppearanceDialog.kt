package me.weishu.kernelsu.ui.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.screen.module.ModuleBackgrounds
import me.weishu.kernelsu.ui.screen.module.rememberBackgroundBitmap
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 主页状态卡外观编辑器：卡底图（image/blur/dim）+ 模式牌（root 方式显示）自定义。
 *
 * 卡底图沿用 [ModuleBackgrounds] 的 slot/decoder/LruCache；模式牌是混搭生态的
 * 第一个元素化编辑器：前后缀词 / 底图 / 字体 / 文字颜色各自可换，**模式关键词
 * （LKM / GKI / KernelPatch）由系统生成，编辑器里根本不出现这个输入项**。
 */
@Composable
fun HomeAppearanceDialog(
    backgroundUri: String?,
    appearance: HomeCardAppearance,
    onAppearanceChange: (HomeCardAppearance) -> Unit,
    keyword: String,
    badgeStyle: ModeBadgeStyle,
    onBadgeChange: (ModeBadgeStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val image = rememberBackgroundBitmap(backgroundUri)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ModuleBackgrounds.set(context, HOME_STATUS_BACKGROUND_SLOT, uri)
            onAppearanceChange(appearance)
        }
    }
    val badgeBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ModuleBackgrounds.set(context, ModeBadgeStyleStore.MODE_BADGE_BG_SLOT, uri)
            onBadgeChange(badgeStyle.copy(bgUri = uri.toString()))
        }
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread {
                val path = ModeBadgeStyleStore.copyFont(context, uri)
                onBadgeChange(badgeStyle.copy(fontPath = path))
            }.start()
        }
    }
    var blur by remember(appearance) { mutableStateOf(appearance.blur) }
    var dim by remember(appearance) { mutableStateOf(appearance.dim) }
    // 编辑中的模式牌草稿：直接以传入值为初值，改动实时反映到预览与主页。
    var prefix by remember(badgeStyle) { mutableStateOf(badgeStyle.prefix) }
    var suffix by remember(badgeStyle) { mutableStateOf(badgeStyle.suffix) }
    val draft = badgeStyle.copy(prefix = prefix, suffix = suffix)

    // 前后缀是编辑器里的本地草稿：任何退出路径（完成/点外面/返回键）都必须先
    // 把草稿提交回 badgeStyle，否则输过的前后缀会无声丢失（主页也看不到）。
    val commitAndDismiss = {
        onBadgeChange(draft)
        onDismiss()
    }
    Dialog(onDismissRequest = commitAndDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Xc.shapes.xl)
                .background(Xc.colors.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.home_appearance_title),
                color = Xc.colors.text,
            )
            Text(
                text = stringResource(R.string.home_appearance_summary),
                color = Xc.colors.textSecondary,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(Xc.shapes.md)
                    .background(
                        if (image == null) {
                            Brush.linearGradient(
                                listOf(
                                    Xc.colors.surfaceMuted,
                                    Xc.colors.accentSoft,
                                )
                            )
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .then(
                                if (blur > 0f) {
                                    Modifier.blur(
                                        radius = blur.dp,
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.65f))),
                    )
                }
                Text(
                    text = if (image == null) {
                        stringResource(R.string.home_appearance_preview_empty)
                    } else {
                        stringResource(R.string.home_appearance_preview)
                    },
                    color = if (image == null) Xc.colors.textSecondary else Color.White,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = if (image == null) {
                        stringResource(R.string.home_appearance_pick)
                    } else {
                        stringResource(R.string.home_appearance_replace)
                    },
                    onClick = { picker.launch(arrayOf("image/*")) },
                )
                if (image != null) {
                    TextButton(
                        text = stringResource(R.string.home_appearance_clear),
                        onClick = {
                            ModuleBackgrounds.clear(context, HOME_STATUS_BACKGROUND_SLOT)
                            onAppearanceChange(HomeCardAppearance(blur = 0f, dim = 0.12f))
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.home_appearance_blur, blur.toInt()),
                color = Xc.colors.textSecondary,
            )
            Slider(
                value = blur,
                onValueChange = {
                    blur = it
                    onAppearanceChange(HomeCardAppearance(blur = it, dim = dim))
                },
                valueRange = 0f..24f,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = Xc.colors.accent,
                    activeTrackColor = Xc.colors.accent,
                ),
            )

            Text(
                text = stringResource(R.string.home_appearance_dim, (dim * 100).toInt()),
                color = Xc.colors.textSecondary,
            )
            Slider(
                value = dim,
                onValueChange = {
                    dim = it
                    onAppearanceChange(HomeCardAppearance(blur = blur, dim = it))
                },
                valueRange = 0f..0.65f,
                colors = SliderDefaults.colors(
                    thumbColor = Xc.colors.accent,
                    activeTrackColor = Xc.colors.accent,
                ),
            )

            ModeBadgeEditor(
                keyword = keyword,
                draft = draft,
                onPrefixChange = { prefix = ModeBadgeStyle.sanitize(it) },
                onSuffixChange = { suffix = ModeBadgeStyle.sanitize(it) },
                onPickBg = { badgeBgPicker.launch(arrayOf("image/*")) },
                onClearBg = {
                    ModuleBackgrounds.clear(context, ModeBadgeStyleStore.MODE_BADGE_BG_SLOT)
                    onBadgeChange(badgeStyle.copy(bgUri = null))
                },
                onPickFont = { fontPicker.launch(arrayOf("*/*")) },
                onClearFont = { onBadgeChange(draft.copy(fontPath = null)) },
                onTextColor = { onBadgeChange(draft.copy(textColor = it)) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.home_appearance_done),
                    onClick = commitAndDismiss,
                )
            }
        }
    }
}

/** 模式牌编辑区：前后缀 / 底图 / 字体 / 文字颜色 + 实时预览。 */
@Composable
private fun ModeBadgeEditor(
    keyword: String,
    draft: ModeBadgeStyle,
    onPrefixChange: (String) -> Unit,
    onSuffixChange: (String) -> Unit,
    onPickBg: () -> Unit,
    onClearBg: () -> Unit,
    onPickFont: () -> Unit,
    onClearFont: () -> Unit,
    onTextColor: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.mode_badge_section_title),
            color = Xc.colors.text,
        )
        Text(
            text = stringResource(R.string.mode_badge_section_hint),
            color = Xc.colors.textSecondary,
        )
        // 实时预览：与主页同一渲染组件（含底图/字体/颜色），所见即所得。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(Xc.shapes.md)
                .background(
                    Brush.linearGradient(listOf(Xc.colors.surfaceMuted, Xc.colors.accentSoft))
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            ModeBadge(keyword = keyword, style = draft, modifier = Modifier.padding(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = draft.prefix,
                onValueChange = onPrefixChange,
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.mode_badge_prefix),
                maxLines = 1,
            )
            TextField(
                value = draft.suffix,
                onValueChange = onSuffixChange,
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.mode_badge_suffix),
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = if (draft.bgUri == null) {
                    stringResource(R.string.mode_badge_pick_bg)
                } else {
                    stringResource(R.string.mode_badge_clear_bg)
                },
                onClick = if (draft.bgUri == null) onPickBg else onClearBg,
            )
            TextButton(
                text = if (draft.fontPath == null) {
                    stringResource(R.string.mode_badge_pick_font)
                } else {
                    stringResource(R.string.mode_badge_clear_font)
                },
                onClick = if (draft.fontPath == null) onPickFont else onClearFont,
            )
        }
        Text(
            text = stringResource(R.string.mode_badge_text_color),
            color = Xc.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val options = listOf(
                null to stringResource(R.string.mode_badge_color_auto),
                0xFFFFFFFF.toInt() to null,
                0xFF000000.toInt() to null,
                0xFFFFC94D.toInt() to null,
            )
            options.forEach { (value, label) ->
                ColorChip(
                    color = value?.let { Color(it) },
                    label = label,
                    selected = draft.textColor == value,
                    onClick = { onTextColor(value) },
                )
            }
        }
    }
}

/** 色板圆点：null = 跟随主题（画一个斜线占位）。 */
@Composable
private fun ColorChip(color: Color?, label: String?, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when (color) {
                        null -> Brush.linearGradient(
                            listOf(Xc.colors.surfaceMuted, Xc.colors.accentSoft)
                        )
                        else -> Brush.linearGradient(listOf(color, color))
                    }
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Xc.colors.accent else Xc.colors.textSecondary.copy(alpha = 0.4f),
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
        )
        if (label != null) {
            Text(text = label, fontSize = 11.sp, color = Xc.colors.textSecondary)
        }
    }
}

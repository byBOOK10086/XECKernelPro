package me.weishu.kernelsu.ui.screen.module

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.ui.design.glass.XGlassSurface
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcNeon
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.icon.extended.UploadCloud

/**
 * 模块长按后弹出的底部操作面板。
 *
 * 内容与设计稿 HTML 的「模块详情」卡片逐项对应：模块名 → 版本 → 作者 → 介绍 →
 * 更新 URL，下面是 Action / WebUi / Update / Uninstall 四枚按钮，最后一行能力提示。
 * 只是形态从整页改成贴底的玻璃面板（长按手势直接唤出，不用切页）。
 *
 * 与 [me.weishu.kernelsu.ui.design.glass.XGlassDialog] 同源：都不新开 Window，
 * 因为 `LayerBackdrop` 只认当前窗口的图层，换了窗口模糊与折射全部失效。
 *
 * 面板只由父级通过 [module] 驱动显隐（`null` = 收起）；退场动画期间靠内部缓存
 * 保住最后一帧内容，否则滑下去的过程会是一块空玻璃。
 *
 * @param module 当前要展示的模块；`null` 表示收起。
 * @param updateUrl 该模块的更新地址（`uiState.updateInfo` 里取），空串则不画这一行。
 * @param backdrop 根节点的 `rememberBlurBackdrop` 结果；`null` 时退化为不透明底 + 描边。
 * @param bottomInnerPadding 页面底部内边距（含导航栏 / 上导航占用），面板压在它之上。
 * @param onAddActionShortcut Action / WebUi 两枚按钮长按时走这里创建桌面快捷方式。
 */
@Composable
fun ModuleActionSheet(
    module: Module?,
    updateUrl: String,
    backdrop: LayerBackdrop?,
    bottomInnerPadding: Dp,
    onDismissRequest: () -> Unit,
    onExecuteAction: () -> Unit,
    onOpenWebUi: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onUndoUninstall: () -> Unit,
    onAddActionShortcut: (ShortcutType) -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = module != null

    // 退场动画跑完才整体撤掉；跑的过程中 [module] 已经是 null 了，
    // 所以内容要留一份快照，不然滑出的是空壳。
    if (!visibleState.currentState && !visibleState.targetState) return
    val cachedModule = remember { mutableStateOf(module) }
    if (module != null) cachedModule.value = module
    val data = module ?: cachedModule.value ?: return

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = true,
        onBackCompleted = onDismissRequest,
    )

    val neon = XcNeon.colors
    val canAction = data.hasActionScript && data.enabled && !data.remove
    val canWebUi = data.hasWebUi && data.enabled && !data.remove
    val canUpdate = updateUrl.isNotEmpty() && !data.remove

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val safeArea = WindowInsets.safeDrawing.asPaddingValues()
        val usableHeight = maxHeight - safeArea.calculateTopPadding() - safeArea.calculateBottomPadding()
        // 面板最多占可用高度的六成，再长的介绍也只在内部滚，不会顶到状态栏。
        val panelMaxHeight = minOf(usableHeight * 0.6f, 560.dp)

        // 遮罩铺满到屏幕边缘（含状态栏那一条），点空白处收起。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Xc.colors.backdropScrim)
                .pointerInput(Unit) {
                    detectTapGestures { onDismissRequest() }
                },
        )

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically(animationSpec = tween(durationMillis = 180), initialOffsetY = { it }) +
                fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutVertically(animationSpec = tween(durationMillis = 180), targetOffsetY = { it }) +
                fadeOut(animationSpec = tween(durationMillis = 180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            XGlassSurface(
                backdrop = backdrop,
                modifier = Modifier
                    // 设计稿 `.glass-card` 的 `box-shadow: 0 4px 24px rgba(0,0,0,0.3)`。
                    // `XGlassSurface` 内部是 `modifier.then(glassModifier)`，外部 modifier
                    // 天然排在裁剪/上色之前，所以阴影画在面板底下，不会被自己的圆角裁掉。
                    .dropShadow(
                        shape = Xc.shapes.sm,
                        shadow = Shadow(
                            radius = 24.dp,
                            color = neon.cardShadow,
                            offset = DpOffset(0.dp, 4.dp),
                        ),
                    )
                    .padding(horizontal = 12.dp)
                    .padding(bottom = bottomInnerPadding)
                    .heightIn(max = panelMaxHeight),
                shape = Xc.shapes.sm,
                tint = neon.cardBg,
                rimColor = neon.cardBorder,
                blurRadius = 12.dp,
                refraction = 26.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // 拖柄：设计稿里没有（HTML 是整页），换成贴底形态后补一个抓取暗示。
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(neon.textSub.copy(alpha = 0.40f)),
                    )
                    Spacer(Modifier.height(16.dp))

                    // 卡片正文：介绍可能很长，只有它这块滚，四枚按钮永远留在可视区。
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = data.name,
                            color = neon.textMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(16.dp))

                        DetailRow(
                            label = stringResource(R.string.module_sheet_version_label),
                            value = data.version,
                        )
                        DetailRow(
                            label = stringResource(R.string.module_author),
                            value = data.author,
                        )

                        // 介绍：`whitespace-pre-line` —— Compose 默认就保留换行。
                        Text(
                            text = stringResource(R.string.module_description),
                            color = neon.textSub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = data.description,
                            color = neon.textMain,
                            fontSize = 14.sp,
                            lineHeight = 22.sp, // leading-relaxed ≈ 1.625 × 14
                        )
                        Spacer(Modifier.height(12.dp))
                        SheetDivider()

                        // 更新 URL：`break-all` + 外链图标；为空时整块不画。
                        if (canUpdate || updateUrl.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.module_update_url),
                                color = neon.textSub,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    modifier = Modifier.size(16.dp),
                                    imageVector = Icons.Rounded.OpenInNew,
                                    tint = neon.accentCyan,
                                    contentDescription = null,
                                )
                                Text(
                                    text = updateUrl,
                                    color = neon.accentCyan,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SheetActionButton(
                            icon = Icons.Rounded.PlayArrow,
                            label = stringResource(R.string.action),
                            enabled = canAction,
                            danger = false,
                            onClick = onExecuteAction,
                            onLongClick = { onAddActionShortcut(ShortcutType.Action) },
                        )
                        SheetActionButton(
                            icon = Icons.Rounded.Language,
                            label = stringResource(R.string.webui),
                            enabled = canWebUi,
                            danger = false,
                            onClick = onOpenWebUi,
                            onLongClick = { onAddActionShortcut(ShortcutType.WebUI) },
                        )
                        SheetActionButton(
                            icon = MiuixIcons.UploadCloud,
                            label = stringResource(R.string.module_update),
                            enabled = canUpdate,
                            danger = false,
                            onClick = onUpdate,
                        )
                        SheetActionButton(
                            icon = if (data.remove) MiuixIcons.Undo else MiuixIcons.Delete,
                            label = stringResource(if (data.remove) R.string.undo else R.string.uninstall),
                            enabled = true,
                            danger = !data.remove,
                            onClick = if (data.remove) onUndoUninstall else onUninstall,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.module_sheet_capability_hint),
                        color = neon.textSub.copy(alpha = 0.50f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 版本 / 作者那一行：左标签右取值，底下压一条 `border-white/5` 分隔线。 */
@Composable
private fun DetailRow(label: String, value: String) {
    val neon = XcNeon.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = neon.textSub,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            color = neon.textMain,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    SheetDivider()
    Spacer(Modifier.height(12.dp))
}

/** `border-white/5`：设计稿里最淡的一条横线。 */
@Composable
private fun SheetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(XcNeon.colors.divider),
    )
}

/**
 * `.detail-action` 的 Compose 版本。
 *
 * 按下态是 `transform: scale(.93)` 而不是水滴扩散——设计稿这里明确写的是缩放，
 * 所以用 [combinedClickable] 自己收状态，不走 `xWaterDropClick`。
 * 用 `combinedClickable` 而非 `clickable`，是为了让 Action / WebUi 也能长按
 * 创建桌面快捷方式（沿用改造前左滑操作栏里的那套手势）。
 * 置灰走 `opacity: .35` + `grayscale(.6)`：能力不满足时连点击都不注册。
 */
@Composable
private fun RowScope.SheetActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val neon = XcNeon.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.93f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "sheetActionScale",
    )

    // `filter: grayscale(.6)`：能力不满足时整体褪色，而不是只降透明度。
    fun dim(color: Color): Color =
        if (enabled) color else lerp(color, Color.Gray, 0.6f)

    val borderColor = dim(
        when {
            danger && pressed -> neon.dangerBorderPressed
            danger -> neon.dangerBorder
            pressed -> neon.actionBorderPressed
            else -> neon.actionBorder
        }
    )
    val backgroundColor = dim(
        when {
            danger && pressed -> neon.dangerBgPressed
            pressed -> neon.actionBgPressed
            else -> neon.cardBg
        }
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .alpha(if (enabled) 1f else 0.35f)
            .scale(scale)
            .clip(Xc.shapes.sm)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = Xc.shapes.sm)
            .then(
                if (enabled) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = true,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = icon,
            tint = dim(if (danger) neon.danger else neon.accentPurple),
            contentDescription = null,
        )
        Text(
            text = label,
            color = dim(neon.textMain),
            fontSize = 11.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

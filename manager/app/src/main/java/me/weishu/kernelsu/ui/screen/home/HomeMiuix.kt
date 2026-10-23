package me.weishu.kernelsu.ui.screen.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.rebootlistpopup.RebootListPopupMiuix
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.design.glass.rememberXcBlock
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.island.IslandTopBar
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlockBackground
import me.weishu.kernelsu.ui.design.token.XcBlocks
import me.weishu.kernelsu.ui.screen.module.ModuleBackgrounds
import me.weishu.kernelsu.ui.screen.module.cardImageScrim
import me.weishu.kernelsu.ui.screen.module.rememberModuleBackground
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val context = LocalContext.current
    val cardAppearance = rememberHomeCardAppearance()
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    val score = remember(state) { XecScore.evaluate(state) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    // 首页六张卡片各自的专属背景（主题 `blocks` 里的一条；没主题就是 null = 今天的样子）。
    // 在**这里**取告警那一块、再传给七处 WarningCard：告警卡在全 App 复用（日志页也在用），
    // 区块背景是"首页那张卡"的语义，不该跟着组件跑到别的页面去。
    val warningBlock = rememberXcBlock(XcBlocks.HOME_WARNING)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopBar(
                state = state,
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
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
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.checkUpdateEnabled) {
                            UpdateCard(state = state, actions = actions)
                        }
                        if (state.showManagerPrBuildWarning) {
                            WarningCard(
                                stringResource(id = R.string.home_pr_build_warning),
                                level = WarningLevel.Notice,
                                block = warningBlock,
                            )
                        } else if (state.showKernelPrBuildWarning) {
                            WarningCard(
                                stringResource(id = R.string.home_pr_kernel_warning),
                                level = WarningLevel.Notice,
                                block = warningBlock,
                            )
                        }
                        if (state.showGkiWarning) {
                            WarningCard(
                                stringResource(id = R.string.home_gki_warning),
                                level = WarningLevel.Notice,
                                block = warningBlock,
                            )
                        }
                        if (state.requiresNewKernel) {
                            WarningCard(
                                stringResource(
                                    id = if (state.lkmMode == true) R.string.require_kernel_version else R.string.require_kernel_version_gki
                                ),
                                onClick = if (state.lkmMode == true) actions.onInstallClick else null,
                                block = warningBlock,
                            )
                        }
                        if (state.requiresNewManager) {
                            WarningCard(
                                stringResource(
                                    id = R.string.require_manager_version
                                ),
                                block = warningBlock,
                            )
                        }
                        if (state.showLkmUpdate) {
                            WarningCard(
                                message = stringResource(R.string.home_lkm_update_available),
                                level = WarningLevel.Notice,
                                onClick = actions.onInstallClick,
                                block = warningBlock,
                            )
                        }
                        if (state.showRootWarning) {
                            WarningCard(stringResource(id = R.string.grant_root_failed), block = warningBlock)
                        }
                        if (state.showDaemonMissingWarning) {
                            WarningCard(stringResource(id = R.string.home_daemon_missing), block = warningBlock)
                        }
                        AnimatedVisibility(
                            visible = revealed,
                            enter = fadeIn(tween(420)) +
                                slideInVertically(tween(420)) { it / 5 },
                        ) {
                            XecHeroCard(
                                state = state,
                                score = score,
                                onShare = { XecShareCard.share(context, state, score) },
                                onDetect = actions.onDetectClick,
                            )
                        }
                        AnimatedVisibility(
                            visible = revealed,
                            enter = fadeIn(tween(460, delayMillis = 70)) +
                                slideInVertically(tween(460, delayMillis = 70)) { it / 5 },
                        ) {
                            Box {
                            StatusCard(
                                state = state,
                                actions = actions,
                                backdrop = backdrop,
                                block = rememberXcBlock(XcBlocks.HOME_STATUS),
                                // 与模块卡同一套 DIY 背景：同一份存储 / 解码 / LruCache，
                                // 只是键固定成 HOME_STATUS_BACKGROUND_SLOT（这张卡没有模块 id）。
                                background = rememberModuleBackground(HOME_STATUS_BACKGROUND_SLOT),
                                appearance = cardAppearance.value,
                            )
                            M3IconButton(
                                onClick = { showAppearance = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.28f)),
                            ) {
                                M3Icon(
                                    imageVector = Icons.Rounded.Palette,
                                    contentDescription = stringResource(R.string.home_appearance_edit),
                                    tint = Color.White,
                                )
                            }
                        }
                        if (showAppearance) {
                            HomeAppearanceDialog(
                                backgroundUri = ModuleBackgrounds.backgroundOf(
                                    context,
                                    HOME_STATUS_BACKGROUND_SLOT,
                                ),
                                appearance = cardAppearance.value,
                                onAppearanceChange = {
                                    cardAppearance.value = it
                                },
                                onDismiss = {
                                    HomeCardAppearanceStore.save(context, cardAppearance.value)
                                    showAppearance = false
                                },
                            )
                        }
                        }
                        // 未激活界面「提权」导航：GhostLock 一键提权控制台。
                        // 已激活（ksuVersion != null）时不出现——那时提权没有意义，
                        // 更新走「修补方式 → 提权更新」。挂载态也不出现：那条路的 root
                        // 是 Magisk 的 su，GhostLock 是内核态（late-load）专有机制。
                        if (state.ksuVersion == null && !state.isMountState) {
                            PrivilegeEntryCard(
                                onClick = actions.onPrivilegeClick,
                                backdrop = backdrop,
                                block = rememberXcBlock(XcBlocks.HOME_PRIVILEGE),
                            )
                        }
                        InfoCard(
                            systemInfo = state.systemInfo,
                            downloadCount = state.latestVersionInfo.downloadCount,
                            susfsVersion = state.susfsVersion,
                            susfsVariant = state.susfsVariant,
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = backdrop,
                            block = rememberXcBlock(XcBlocks.HOME_INFO),
                        )
                        SupportLinks(
                            onOpenUrl = actions.onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = backdrop,
                            block = rememberXcBlock(XcBlocks.HOME_SUPPORT),
                        )
                        Spacer(
                            Modifier.height(
                                bottomInnerPadding + if (!Natives.isFullFeatured())
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    val newVersion = state.latestVersionInfo
    val title = stringResource(id = R.string.module_changelog)
    val updateText = stringResource(id = R.string.module_update)
    val updateDialog = rememberConfirmDialog(onConfirm = { actions.onOpenUrl(newVersion.downloadUrl) })
    // 更新卡是"有新版本"的那一张，用的是更新那一条区块背景；它下面的七处 WarningCard
    // 才是告警那一条。两者分开，用户才能只给"有更新"这张设背景。
    val updateBlock = rememberXcBlock(XcBlocks.HOME_UPDATE)

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        WarningCard(
            message = stringResource(id = R.string.new_version_available, newVersion.versionCode),
            level = WarningLevel.Notice,
            onClick = {
                if (newVersion.changelog.isEmpty()) {
                    actions.onOpenUrl(newVersion.downloadUrl)
                } else {
                    updateDialog.showConfirm(
                        title = title,
                        content = newVersion.changelog,
                        markdown = true,
                        confirm = updateText
                    )
                }
            },
            block = updateBlock,
        )
    }
}

/**
 * 首页顶栏：完全透明的毛玻璃。
 *
 * 上覆色留空（[Color.Transparent]），栏底整块交给模糊 / 折射去画：玻璃照旧采样下方
 * 内容做模糊与折射，只是不再压任何颜色，滚动时标题像是直接浮在页面上。换成透明是因为
 * 之前那层"白毛玻璃"的 `0xE6FFFFFF` 在浅色内容上会糊成一块发白的板子，和页面对不上。
 *
 * 前景跟着明暗档走（[Xc.colors.text]）：透明底下的画面就是页面本身，深色档配浅色字、
 * 浅色档配深色字，和页面正文一致；这里不能再沿用固定的近黑前景，否则深色档看不见标题。
 * 设备不支持模糊（或用户关掉）时 [BlurredBar] 会用不透明的 `surface` 兜底，`text` 同样成立。
 */
@Composable
private fun TopBar(
    state: HomeUiState,
    scrollBehavior: ScrollBehavior,
) {
    // 灵动岛：顶栏本身就是岛的展开态（无玻璃底）；上滑收缩成岛。
    IslandTopBar(
        islandTitle = stringResource(R.string.app_name),
        islandLive = when {
            state.ksuVersion != null ->
                stringResource(R.string.home_working_version, "${state.ksuVersion}-${state.kernelUAPIVersion}")
            // 挂载态也是"在跑"：路线名用 Magisk 路线，不能显示成未工作（会和状态卡自相矛盾）。
            state.isMountState -> stringResource(R.string.home_working_magisk)
            else -> stringResource(R.string.island_not_working)
        },
        statusActive = state.ksuVersion != null || state.isMountState,
    ) {
        TopAppBar(
            // 展开态没有玻璃底：透明浮在页面上。
            color = Color.Transparent,
            title = stringResource(R.string.app_name),
            titleColor = Xc.colors.text,
            largeTitleColor = Xc.colors.text,
            actions = {
                RebootListPopupMiuix(iconTint = Xc.colors.text)
            },
            scrollBehavior = scrollBehavior
        )
    }
}

/**
 * 首页状态卡的自定义背景槽位 id。
 *
 * 与模块卡的 DIY 背景共用 [ModuleBackgrounds] 那一份存储（`module_backgrounds` 里的一条），
 * 只是键固定成 `home_status` —— 它是"首页这张状态卡"这个位置，不属于任何模块。
 * 今天还没有专门给它挑图的入口；渲染与取值链路先就位，将来任意入口往同一份 prefs
 * 写这个键就即时生效（不在设置里另开一套格式）。
 */
/**
 * 首页状态卡。
 *
 * `backdrop` 由 `HomePagerMiuix` 透传（本组件是 private，自己拿不到页面级那一份）。
 * 默认 `null`，所以各处 `@Preview` 与任何旧调用点都不必改：为 `null` 时玻璃自动降级成
 * 不透明实色 + 亮边，预览里也不会去构造 AGSL 着色器。
 *
 * [block] 是这一大块（`home.status`）的专属背景，三支分支共用同一条 —— 用户设的是
 * "状态卡"这个位置，不该因为设备恰好是挂载态/未安装态就换个观感。
 *
 * [background] 是用户给这张卡单独挑的图片（按 [HOME_STATUS_BACKGROUND_SLOT] 从
 * [ModuleBackgrounds] 取，和模块卡同一套解码/缓存）。默认 `null` ⇒ 卡体与今天逐字节一致；
 * 非空 ⇒ 图铺满卡体、按卡体圆角裁切，上面只压一层 [cardImageScrim]，正文文字颜色一个
 * 都不改，靠 scrim 保对比度（每个分支都吃这一层，见 [StatusCardBackdrop]）。
 */
@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
    backdrop: LayerBackdrop? = null,
    block: XcBlockBackground? = null,
    background: ImageBitmap? = null,
    appearance: HomeCardAppearance = HomeCardAppearance(),
) {
    Column {
        when {
            state.ksuVersion != null -> {
                val workingState = buildString {
                    if (state.isSafeMode) {
                        append(" [${stringResource(id = R.string.safe_mode)}]")
                    }
                    if (state.isLateLoadMode) {
                        append(" [${stringResource(id = R.string.jailbreak_mode)}]")
                    }
                }
                // <5.10 内核走 KernelPatch 注入路线（kpimg + ftruncate supercall），
                // 状态卡模式名换成对应用法；GKI 设备维持 LKM/GKI 口径。
                // （本管理器不实现 Magisk/APatch 那套模块挂载，模块加载仍由
                //  KernelSU/KPM 侧负责。）
                // 顺序要紧：HomeViewModel 里 lkmMode 只在 GKI 设备上取值（非 GKI 恒为
                // null），非 GKI 判断必须排在 lkmMode == null 之前，否则
                // home_working_kpatch 永远显示不出来。
                val workingMode = when {
                    !getKernelVersion().isGKI() -> stringResource(R.string.home_working_kpatch)
                    state.lkmMode == true -> "LKM"
                    else -> "GKI"
                }
                val workingText = "${stringResource(id = R.string.home_working)}$workingState"

                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 语义色改从 tint 走。不能直接传不透明的 `successTint`：那是
                            // "实色压 surface" 合成出来的纯色，盖在模糊之上会把折射遮死。
                            // 传同色相的低透明度版本，有玻璃时是 22% 的绿压在模糊画面上；
                            // 没玻璃时 `compositeOver(surface)` 复合回正好是原来的 successTint。
                            .xGlassBody(
                                backdrop = backdrop,
                                shape = Xc.shapes.md,
                                tint = Xc.colors.success.copy(alpha = if (Xc.colors.isDark) 0.22f else 0.15f),
                                block = block,
                            ),
                        colors = CardDefaults.defaultColors(
                            // 卡底必须透明：miuix Card 的底色画在本 modifier 链内侧，
                            // 留着就会把玻璃与亮边一起盖住。
                            color = Color.Transparent
                        ),
                        onClick = {
                            if (!state.isLateLoadMode) {
                                actions.onInstallClick()
                            }
                        },
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                        ) {
                            // 自定义背景：图铺满卡体（玻璃卡体之上、内容之下）。为 null 时
                            // 整层跳过 —— 没设背景的卡与今天逐字节一致。
                            if (background != null) {
                                StatusCardBackdrop(
                                    background = background,
                                    appearance = appearance,
                                    modifier = Modifier.matchParentSize(),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = Icons.Rounded.CheckCircleOutline,
                                    tint = Xc.colors.success,
                                    contentDescription = null
                                )
                            }
                            if (workingMode != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp, 10.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    Text(
                                        text = workingMode,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    Text(
                                        text = workingText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(
                                                R.string.home_working_version,
                                                "${state.ksuVersion}-${state.kernelUAPIVersion}"
                                            ),
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontSize = 15.sp,
                                        )
                                        if (state.showCustomLkmBadge) {
                                            Spacer(Modifier.width(8.dp))
                                            StatusTag(
                                                label = stringResource(R.string.home_lkm_custom),
                                                // 徽章就在上面那张 successTint 的卡里，
                                                // 两者同色会互相吞掉。用前景色压一层低透明
                                                // 当底，保持原设计里那一档色调差。
                                                contentColor = Xc.colors.onSuccessTint,
                                                backgroundColor = Xc.colors.onSuccessTint.copy(alpha = 0.16f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 挂载态（唯一判据：没有 KSU 驱动应答且内核低于 5.10）：root 就是 Magisk 自己的，
            // 状态卡按"工作正常"渲染，路线行写 Magisk 路线，版本行显示正在跑的 magiskd 版本。
            // 这一支必须排在下面 isGKI()/Unsupported 之前 —— 否则 <5.10 的挂载态设备
            //（Magisk 路线的目标机型）会掉进 home_unsupported 分支。
            state.isMountState -> {
                val magiskVersionText = state.magiskVersion?.takeIf { it.isNotBlank() }
                    ?: stringResource(id = R.string.home_magisk_version_unknown)
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .xGlassBody(
                                backdrop = backdrop,
                                shape = Xc.shapes.md,
                                tint = Xc.colors.success.copy(alpha = if (Xc.colors.isDark) 0.22f else 0.15f),
                                block = block,
                            ),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                        onClick = actions.onInstallClick,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                        ) {
                            // 挂载态共用同一条背景：用户设的是"状态卡"这个位置，
                            // 不该因为路线换了（KernelSU ↔ Magisk）就换观感。
                            if (background != null) {
                                StatusCardBackdrop(
                                    background = background,
                                    appearance = appearance,
                                    modifier = Modifier.matchParentSize(),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = Icons.Rounded.CheckCircleOutline,
                                    tint = Xc.colors.success,
                                    contentDescription = null
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 10.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text = stringResource(R.string.home_working_magisk),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(id = R.string.home_working),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.home_magisk_version,
                                            magiskVersionText
                                        ),
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.kernelVersion.isGKI() -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md, block = block),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                        onClick = {
                            if (!state.isLateLoadMode) {
                                actions.onInstallClick()
                            }
                        },
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        // 这一支没有现成的 Box 可以叠背景，补一层：图铺满卡体、内容画在
                        // 它上面；background 为 null 时 Box 的宽高仍由唯一子项决定，
                        // 布局与今天一致。
                        Box {
                            if (background != null) {
                                StatusCardBackdrop(
                                    background = background,
                                    appearance = appearance,
                                    modifier = Modifier.matchParentSize(),
                                )
                            }
                            BasicComponent(
                                title = stringResource(R.string.home_not_installed),
                                summary = stringResource(R.string.home_click_to_install),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.ErrorOutline,
                                        stringResource(R.string.home_not_installed),
                                        modifier = Modifier.padding(end = 6.dp),
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                endActions = {
                                    if (state.isSELinuxPermissive) {
                                        TextButton(
                                            text = stringResource(R.string.home_jailbreak),
                                            onClick = actions.onJailbreakClick,
                                            colors = ButtonDefaults.textButtonColorsPrimary()
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            else -> {
                Card(
                    modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md, block = block),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                    onClick = {
                        if (!state.isLateLoadMode) {
                            actions.onInstallClick()
                        }
                    },
                    showIndication = !state.isLateLoadMode,
                    pressFeedbackType = PressFeedbackType.Tilt
                ) {
                    Box {
                        if (background != null) {
                            StatusCardBackdrop(
                                background = background,
                                appearance = appearance,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        BasicComponent(
                            title = stringResource(R.string.home_unsupported),
                            summary = stringResource(R.string.home_unsupported_reason),
                            startAction = {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    stringResource(R.string.home_unsupported),
                                    modifier = Modifier.padding(end = 16.dp),
                                    tint = colorScheme.onBackground,
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 状态卡的自定义背景层：图铺满卡体（中裁填满、按卡体圆角裁切），上面压一层与模块卡
 * 同款的 scrim（[cardImageScrim]）。
 *
 * scrim 的介质色取 [Xc.colors] 的 `backdropScrim` —— 那是设计令牌里"压在背景图上"的
 * 那一条（深档近黑 / 浅档近白），所以深色档的近白字、浅色档的近黑字都还站得住，
 * 正文一个颜色都不必改。
 */
@Composable
private fun StatusCardBackdrop(
    background: ImageBitmap,
    appearance: HomeCardAppearance,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(Xc.shapes.md)) {
        Image(
            bitmap = background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (appearance.blur > 0f) {
                        Modifier.blur(
                            radius = appearance.blur.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                    } else {
                        Modifier
                    }
                ),
        )
        if (appearance.dim > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = appearance.dim.coerceIn(0f, 0.65f))),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .cardImageScrim(Xc.colors.backdropScrim),
        )
    }
}

/**
 * 未激活界面「提权」入口：一键提权控制台（GhostLock，CVE-2026-43499）。
 * 与上面的未安装卡片同款玻璃语言；识别不了的机型里给手动 profile 操作空间。
 *
 * [block] = `home.privilege`（这张卡片自己的背景）。
 */
@Composable
private fun PrivilegeEntryCard(
    onClick: () -> Unit,
    backdrop: LayerBackdrop? = null,
    block: XcBlockBackground? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md, block = block),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        BasicComponent(
            title = stringResource(R.string.privilege_title),
            summary = stringResource(R.string.privilege_entry_subtitle),
            startAction = {
                Icon(
                    Icons.Rounded.Bolt,
                    stringResource(R.string.privilege_title),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.primary,
                )
            },
            endActions = {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }
}

/** 支持链接卡；[block] = `home.support`。 */
@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    block: XcBlockBackground? = null,
) {
    val learnMoreUrl = stringResource(R.string.home_learn_kernelsu_url)
    var showDevelopers by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md, block = block),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        ArrowPreference(
            title = stringResource(R.string.home_support_title),
            summary = stringResource(R.string.home_support_content),
            startAction = {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = stringResource(R.string.home_support_title),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { showDevelopers = true },
        )
        ArrowPreference(
            title = stringResource(R.string.home_learn_kernelsu),
            summary = stringResource(R.string.home_click_to_learn_kernelsu),
            startAction = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = stringResource(R.string.home_learn_kernelsu),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl(learnMoreUrl) },
        )
    }

    XDialog(
        show = showDevelopers,
        onDismissRequest = { showDevelopers = false },
    ) {
        XDialogTitle(text = stringResource(R.string.home_support_title))
        // 这里**不要**再挂 verticalScroll：XGlassDialog 的面板本身就是一个
        // 纵向滚动容器，它会把 maxHeight = Infinity 交给子项；子项再嵌一层同方向
        // 滚动时 Compose 会直接抛
        // IllegalStateException: Vertically scrollable component was measured with
        // an infinity maximum height constraints。
        // 长文案交给面板那一层滚就够了。
        Text(
            text = stringResource(R.string.home_developers_list),
            color = Xc.colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * 设备信息卡：两张叠在一起的玻璃卡（"版本"一叠 + "安全状态"一叠）。
 *
 * [block] = `home.info`，只画在**第一张**上：区块背景是"整块卡片"的观感，
 * 两张卡中间还隔着 12dp 的间距，两边各铺一张图会看起来像两张互不相干的卡。
 */
@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    downloadCount: Long = 0,
    susfsVersion: String? = null,
    susfsVariant: String? = null,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    block: XcBlockBackground? = null,
) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = content,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccompDisplay = when (systemInfo.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md, block = block),
            colors = CardDefaults.defaultColors(color = Color.Transparent),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Tag,
                    title = stringResource(R.string.home_manager_version),
                    content = systemInfo.managerVersion,
                )
                if (downloadCount > 0) {
                    InfoText(
                        icon = Icons.Filled.Download,
                        title = stringResource(R.string.home_download_count),
                        content = "%,d".format(downloadCount),
                    )
                }
                InfoText(
                    icon = Icons.Filled.DeveloperBoard,
                    title = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                )
                InfoText(
                    icon = Icons.Filled.Smartphone,
                    title = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
                InfoText(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                    bottomPadding = 0.dp,
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
            colors = CardDefaults.defaultColors(color = Color.Transparent),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
                // SUSFS 行：null = 探测不到（无 root / shell 失败）→ 整行隐藏；
                // "unsupport" = 内核没打 SUSFS 补丁，明确告知用户而不是静默。
                if (susfsVersion != null) {
                    InfoText(
                        icon = Icons.Filled.Shield,
                        title = stringResource(R.string.home_susfs_status),
                        content = if (susfsVersion == "unsupport") {
                            stringResource(R.string.home_susfs_unsupported)
                        } else if (susfsVariant != null) {
                            "$susfsVersion · $susfsVariant"
                        } else {
                            susfsVersion
                        },
                    )
                }
                InfoText(
                    icon = Icons.Filled.FilterList,
                    title = stringResource(R.string.home_seccomp_status),
                    content = seccompDisplay,
                    bottomPadding = 0.dp,
                )
            }
        }
    }
}

@Preview(name = "Activated")
@Composable
private fun StatusCardActivatedPreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Not Activated")
@Composable
private fun StatusCardNotActivatedPreview() {
    StatusCard(state = previewHomeScreenState(ksuVersion = null, lkmMode = null), actions = HomeActions({}, {}))
}

@Preview(name = "Permissive")
@Composable
private fun StatusCardPermissivePreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive"),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Jailbreak")
@Composable
private fun StatusCardJailbreakPreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true),
        actions = HomeActions({}, {})
    )
}

/**
 * 新形参 [StatusCard.background] 的预览：没有可读的图片文件，用主题强调色铺一张占位图。
 * 它同时起两个作用 —— 让编译期检查到"带背景"这条 API，以及肉眼确认 scrim 压在图的哪一段。
 */
@Preview(name = "Custom Background")
@Composable
private fun StatusCardCustomBackgroundPreview() {
    StatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true),
        actions = HomeActions({}, {}),
        background = rememberPreviewBackground(),
    )
}

/** 预览专用位图：一张纯色 ImageBitmap（颜色取当前主题令牌，不写死十六进制）。 */
@Composable
private fun rememberPreviewBackground(): ImageBitmap {
    val fill = Xc.colors.accent.toArgb()
    return remember(fill) {
        Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(fill) }
            .asImageBitmap()
    }
}

private val previewSystemInfo = SystemInfo(
    kernelVersion = "6.12.23-android16-5-g123456789000-abogki123456789-4k",
    managerVersion = "3.0.0 (30000)",
    deviceModel = "Xiaomi 17 Pro Max",
    fingerprint = "Xiaomi/popsicle/popsicle:16/BQ2A.250705.001-BP2A.250605.031.A3/OS3.0.313.0.WPBCNXM:user/release-keys",
    selinuxStatus = "Enforcing",
    seccompStatus = 2
)

private val previewUriHandler = object : UriHandler {
    override fun openUri(uri: String) {}
}

@Composable
private fun HomeScreenPreviewContent(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) {
    CompositionLocalProvider(LocalUriHandler provides previewUriHandler) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val actions = HomeActions({}, {})
            StatusCard(
                state = previewHomeScreenState(
                    ksuVersion = ksuVersion,
                    lkmMode = lkmMode,
                    isSafeMode = isSafeMode,
                    isLateLoadMode = isLateLoadMode,
                    selinuxStatus = selinuxStatus,
                ),
                actions = actions
            )
            InfoCard(
                systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
                modifier = Modifier.fillMaxWidth(),
            )
            SupportLinks(
                onOpenUrl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "Home Activated", showBackground = true)
@Composable
private fun HomeScreenActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true)
}

@Preview(name = "Home Not Activated", showBackground = true)
@Composable
private fun HomeScreenNotActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null)
}

@Preview(name = "Home Permissive", showBackground = true)
@Composable
private fun HomeScreenPermissivePreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive")
}

@Preview(name = "Home Jailbreak", showBackground = true)
@Composable
private fun HomeScreenJailbreakPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true)
}

private fun previewHomeScreenState(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) = HomeUiState(
    kernelVersion = KernelVersion(6, 1, 0),
    ksuVersion = ksuVersion,
    lkmMode = lkmMode,
    isLkmBundled = lkmMode == true,
    isManager = true,
    isManagerPrBuild = false,
    isKernelPrBuild = false,
    requiresNewKernel = false,
    requiresNewManager = false,
    isRootAvailable = ksuVersion != null,
    isSafeMode = isSafeMode,
    isLateLoadMode = isLateLoadMode,
    isDaemonPresent = ksuVersion != null,
    susfsVersion = null,
    susfsVariant = null,
    checkUpdateEnabled = false,
    latestVersionInfo = LatestVersionInfo(),
    currentManagerVersionCode = 10000,
    systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
    kernelUAPIVersion = 1,
    managerUAPIVersion = 1,
)

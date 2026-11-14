package me.weishu.kernelsu.ui.screen.home

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.rebootlistpopup.RebootListPopupMiuix
import me.weishu.kernelsu.ui.design.glass.rememberXcBlock
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8SectionLabel
import me.weishu.kernelsu.ui.design.panel.Xc8Tag
import me.weishu.kernelsu.ui.design.panel.Xc8Tone
import me.weishu.kernelsu.ui.design.panel.Xc8Value
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlockBackground
import me.weishu.kernelsu.ui.design.token.XcBlocks
import me.weishu.kernelsu.ui.design.token.XcEasing
import me.weishu.kernelsu.ui.design.token.XcMotionDefault
import me.weishu.kernelsu.ui.design.token.rememberReducedMotion
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.WallpaperStore
import me.weishu.kernelsu.ui.viewmodel.MainActivityUiState
import me.weishu.kernelsu.ui.viewmodel.MainActivityViewModel
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/**
 * 首页（扁平方案 A，2026-11-09）。
 *
 * 版式来自设计稿：**一句话结论 + 三格数据 + 设备表**。
 *
 * 与上一版（MIUI 8 六张面板）的区别：
 *  - 首屏不再让"模式卡"独占整块；状态、窗口与三格合成一张卡（[HomeHeroCard]），
 *    层级由"面 + 窗口"承担，一屏读得出"能不能用"；
 *  - 三格数据（内核 / 管理器 / 超级用户）不再自己收边，作为合并卡的下半段；
 *  - 设备信息变成"标签 + 右值"的表，刷新入口就挂在这一节的标题行上；
 *  - 更新卡从告警条变成一张实心卡：左边版本与体积、右边一个强调色药丸。
 *
 * 安全状态 / 运行概况 / 支持三块**保留**在下面（设计稿只画了首屏，功能不删），
 * 只是同样走面板 + 发丝线这套语言。
 */
@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val warningBlock = rememberXcBlock(XcBlocks.HOME_WARNING)

    // Root 授权数量：只有内核驱动应答时才问得到；driver 不在时显示 0，
    // 不去碰 allowlist 文件（那条路在无 root 时必然失败，且失败原因与用户无关）。
    val rootGrantCount by produceState(
        initialValue = 0,
        key1 = state.ksuVersion,
        key2 = state.isRootAvailable,
    ) {
        value = if (state.ksuVersion == null) {
            0
        } else {
            withContext(Dispatchers.IO) { runCatching { getSuperuserCount() }.getOrDefault(0) }
        }
    }

    // 2026-11-11 第三轮：数据面板变成用户自选，需要「模块总数 / 启用模块数」这两项。
    // 它们不在 HomeUiState 里 —— 模块列表属于 ModuleViewModel，而 MainScreen 启动时
    // 已经预载过一遍（MainActivity.kt:614-620）。这里复用**同一个** ViewModel 实例
    // （同一 NavEntry 的 ViewModelStore），所以既不起新 shell，也不会出现两份计数。
    // hasLoaded 之前一律当"未知"，不能当 0。
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleState by moduleViewModel.uiState.collectAsStateWithLifecycle()
    val moduleTotal = if (moduleState.hasLoaded) moduleState.modules.size else null
    val moduleEnabled = if (moduleState.hasLoaded) moduleState.modules.count { it.enabled } else null

    var showStatEditor by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { HomeHeader(isRefreshing = state.isRefreshing, onRefresh = actions.onRefresh) },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 2026-11-09：上滑偶发不响应，根因是这里挂着 MiuixScrollBehavior 的
        // nestedScrollConnection，却没有任何 TopAppBar 消费它（顶栏已改成自绘的
        // HomeHeader）。连接器会吞掉一部分 fling，手势就时灵时不灵。
        // 现在改由 miuix 的 PullToRefresh 接管下拉手势，滚动本身走默认实现。
        PullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 22.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 异常态永远排在最前：它们才是这一屏唯一需要立刻处理的东西。
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
                            stringResource(id = R.string.require_manager_version),
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

                    HomeHeroCard(
                        state = state,
                        inputs = HomeStatInputs(
                            state = state,
                            rootGrantCount = rootGrantCount,
                            moduleTotal = moduleTotal,
                            moduleEnabled = moduleEnabled,
                        ),
                        onInstall = actions.onInstallClick,
                        onEdit = { showStatEditor = true },
                    )

                    if (state.checkUpdateEnabled) {
                        UpdateCard(state = state, actions = actions)
                    }

                    DeviceSection(state = state, onRefresh = actions.onRefresh)

                    Xc8SectionLabel(text = stringResource(R.string.home8_section_security))
                    SecurityPanel(state = state)
                    Xc8SectionLabel(text = stringResource(R.string.home8_section_overview))
                    OverviewPanel(
                        state = state,
                        rootGrantCount = rootGrantCount,
                        actions = actions,
                    )
                    Xc8SectionLabel(text = stringResource(R.string.home8_section_support))
                    SupportPanel(
                        actions = actions,
                        block = rememberXcBlock(XcBlocks.HOME_SUPPORT),
                    )

                    Spacer(
                        Modifier.height(
                            bottomInnerPadding + 12.dp + if (!Natives.isFullFeatured())
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                        )
                    )
                }
            }
        }
        }
    }

    // 编辑页挂在 Scaffold 之外：它是一个独立的 Dialog 窗口，和滚动区域无关。
    if (showStatEditor) {
        HomeStatEditorDialog(onDismissRequest = { showStatEditor = false })
    }
}

/**
 * 顶栏：应用名 + 一行机器可读的元信息（版本号 · 机型），右侧两个图标按钮。
 *
 * 不复用 miuix `TopAppBar`：设计稿的标题下面压着一行小字，TopAppBar 的标题槽只有一行，
 * 硬塞副标题要改它的排版规则；这里就是一个 [statusBarsPadding] 包住的 Row，行为可预期。
 */
@Composable
private fun HomeHeader(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    // 刷新图标转圈：只在真的刷新时转（设计要求「加载期间显示真实刷新状态」）。
    // 之前 `HomeHeader` 根本拿不到 isRefreshing —— 状态链是通的，只是没接线，
    // 所以图标永远是静止的，用户看不出点没点上。
    val reduced = rememberReducedMotion()
    val spin = if (isRefreshing && !reduced) {
        val turn = rememberInfiniteTransition(label = "refreshSpin")
        turn.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(XcMotionDefault.refreshTurn, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refreshAngle",
        ).value
    } else {
        0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2026-11-09：标题下面那行「版本号 · 机型」按用户要求去掉 —— 机型在「设备」表里
        // 仍然有，管理器版本也在表里，顶栏不再重复这两项。
        Text(
            text = stringResource(R.string.app_name),
            style = Xc.typography.title,
            color = Xc.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RebootListPopupMiuix(iconTint = Xc.colors.text)
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(Xc.shapes.md)
                .border(1.dp, Xc.colors.glassRim, Xc.shapes.md)
                // 刷新期间禁用点击：ViewModel 那边有重入合并，但按钮自己能给回执更好
                // —— 禁用态 + 转圈 = "正在跑，别急"。
                .clickable(enabled = !isRefreshing, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.home_refresh),
                tint = if (isRefreshing) Xc.colors.accent else Xc.colors.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(spin),
            )
        }
    }
}

/**
 * 首屏结论 + 三格数据合成的一张卡（2026-11-14 用户定稿）。
 *
 * 三段：状态行 / 窗口 / 三格矩阵。窗口显示**用户自定义背景**（与整页壁纸同一张，
 * 但只按「背景模糊」的 12% 糊一层，所以在卡里还能看出细节），没选自定义图时回落到
 * 纯色档或内置底色。
 *
 * 合并的理由：原先状态与三格是两条独立排版带，中间夹着 6dp + 17dp 空白，读起来是
 * "两个东西"；合成一张面之后首屏只有一个重心，层级由面和窗口承担，不再靠字号膨胀。
 */
@Composable
private fun HomeHeroCard(
    state: HomeUiState,
    inputs: HomeStatInputs,
    onInstall: () -> Unit,
    onEdit: () -> Unit,
) {
    val running = state.ksuVersion != null
    // 审计 P1（2026-11-09）：之前只看"驱动有没有应答"，驱动在但 su 被拒时仍显示
    // ROOT READY。就绪判据改成实际的 root 可用性。
    val active = state.isRootAvailable
    // 2026-11-09 用户要求：管理器已经拿到 root、但内核模块还没装/没跑起来时，
    // 不再说"内核未运行"，而是明确告诉用户"可以直接装"，并且这块本身就是入口。
    val canInstall = !running && state.isRootAvailable
    // 冷启动/刚重启那段，root 探测还没落定：灰点 + 「正在获取 Root」，不画红点。
    val probing = !state.rootProbeSettled
    val statusLabel = when {
        active -> R.string.home_hero_ready
        probing -> R.string.home_hero_probing
        else -> R.string.home_hero_idle
    }
    val statusColor = when {
        active -> Xc.colors.success
        probing -> Xc.colors.textMuted
        else -> Xc.colors.danger
    }
    val title = when {
        running -> stringResource(R.string.home_hero_running)
        canInstall -> stringResource(R.string.home_hero_ready_title)
        else -> stringResource(R.string.home_hero_inactive)
    }
    val modeText = when {
        state.lkmMode == true -> "LKM"
        running -> stringResource(R.string.home_hero_kernel_mode)
        else -> stringResource(R.string.home_not_installed)
    }
    // 文案统一走 HomeStatItem.kt 里的 selinuxLabel()：设备表 / 安全面板 / 数据面板
    // 三处曾经各抄一份 when，删一处就会漏一处。
    val selinuxText = selinuxLabel(state.systemInfo.selinuxStatus)
    val subtitle = when {
        canInstall -> stringResource(R.string.home_hero_ready_subtitle)
        else -> "$modeText · SELinux $selinuxText"
    }

    val dark = Xc.colors.isDark
    val appUi by viewModel<MainActivityViewModel>().uiState.collectAsStateWithLifecycle()
    val windowBitmap = rememberHeroWindowBitmap(appUi)
    val windowSolid = WallpaperStore.solidArgb(if (dark) appUi.wallpaperDark else appUi.wallpaperLight)
    // 卡片里的窗口比整页壁纸清晰：壁纸层按 wallpaperBlur 全糊，窗口只跟 12% ——
    // 这样"同一张图、两个模糊档"才成立，窗口不至于和背景糊成一片。
    val windowBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appUi.wallpaperBlur / 100f * 12f).dp
    } else {
        0.dp
    }

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .background(Xc.surface)
            .border(1.dp, Xc.colors.glassRim, shape)
            .padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 14.dp),
    ) {
        // 呼吸点：只有 root 真的可用才呼吸；减少动态效果时静止。无限动画不受
        // durationScale 管辖，所以必须自己判 reduced。
        val reduced = rememberReducedMotion()
        val dotAlpha = if (active && !reduced) {
            val breath = rememberInfiniteTransition(label = "heroBreath")
            breath.animateFloat(
                initialValue = 1f,
                targetValue = 0.42f,
                animationSpec = infiniteRepeatable(
                    animation = tween(XcMotionDefault.breath / 2, easing = XcEasing.standard),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "heroDotAlpha",
            ).value
        } else {
            1f
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(statusLabel),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Xc.colors.textMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 窗口：用户自定义背景。整块可点 = 进刷入页（沿用 2026-11-09 的定案）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onInstall),
        ) {
            when {
                windowBitmap != null -> Image(
                    bitmap = windowBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(windowBlur),
                    contentScale = ContentScale.Crop,
                )

                windowSolid != null -> Box(Modifier.fillMaxSize().background(Color(windowSolid)))

                // 没有自定义图：用主题里的浅绿/浅灰做一层柔和底，窗口不至于塌成白块。
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Xc.colors.accentSoft, Xc.colors.surfaceMuted),
                            ),
                        ),
                )
            }
            // 标题区 scrim：压住底部 58%，保证 34sp 白字在任意壁纸上都过对比度。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.42f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = Xc.typography.display,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 2026-11-09：和 KernelSU 同款的"已运行"对勾，只在内核真的在跑时出现。
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HomeStats(inputs = inputs, onEdit = onEdit)
    }
}

/** 窗口里的背景：解码用户选的那张壁纸；没有自定义图时返回 null（调用方回落）。 */
@Composable
private fun rememberHeroWindowBitmap(ui: MainActivityUiState): ImageBitmap? {
    val context = LocalContext.current
    val dark = Xc.colors.isDark
    val name = if (dark) ui.wallpaperDark else ui.wallpaperLight
    // API 31 以下没有 RenderEffect：用更小的解码尺寸当"廉价模糊"，否则窗口会是一张
    // 全分辨率照片，和整页糊掉的壁纸完全对不上。
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val edge = if (canBlur) 1024 else 256
    val bitmap by produceState<ImageBitmap?>(initialValue = null, name, edge) {
        value = if (name.isEmpty() || WallpaperStore.isSolid(name)) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    WallpaperStore.decode(WallpaperStore.fileOf(context, name), edge)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

/**
 * 三格数据（第三轮：可自定义；2026-11-14 起嵌进 [HomeHeroCard] 的下半段）。
 *
 * 三列等宽、格间一条竖线，不再自己画上下两条发丝线 —— 上下边界由外层卡片承担，
 * 否则合并后会出现"卡里还有两条线"的重复收边。
 *
 * 编辑入口只有**长按矩阵段**一处：设计要求「不要让正常浏览首页时出现过多编辑按钮」，
 * 所以这里不画图标、不画「编辑」字样。长按给了震动反馈，否则手势没有任何回执。
 * 长按只挂在矩阵段、不挂整张卡：上半窗口是"点一下进刷入页"，跨整卡会和点击抢手势。
 */
@Composable
private fun HomeStats(inputs: HomeStatInputs, onEdit: () -> Unit) {
    val line = Xc.colors.glassRim
    val slots by HomeStatStore.slots.collectAsState()
    val haptic = LocalHapticFeedback.current
    // 长按是唯一的编辑入口，所以必须同时挂一条无障碍动作：读屏用户看不到"长按"
    // 这个手势，只能靠 semantics 里的 onLongClick 触发。
    val editHint = stringResource(R.string.home_stat_edit_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEdit()
                    },
                )
            }
            .semantics {
                onLongClick(label = editHint) {
                    onEdit()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.forEachIndexed { index, item ->
            if (index > 0) StatDivider(line)
            StatCell(item = item, inputs = inputs, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 一个格子。
 *
 * 值变化走 [Crossfade]（设计要求「数据项替换时采用轻微淡出和淡入」），外层固定
 * 30dp 高：数字（24sp）和文字（15sp）换档时行高不同，不固定的话整块面板会抖一下。
 *
 * 文字类项目（SELinux / SUSFS / Seccomp）不能用 24sp —— 「不适用」这种词在 24sp 下
 * 三格必然被截断。所以按 [HomeStatValue.numeric] 分两档，这本身也是一种截断策略。
 */
@Composable
private fun StatCell(item: HomeStatItem, inputs: HomeStatInputs, modifier: Modifier = Modifier) {
    val value = item.value(inputs)
    val label = stringResource(item.label)
    Column(modifier = modifier.padding(start = 2.dp)) {
        Box(
            modifier = Modifier.height(30.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Crossfade(targetState = value, animationSpec = tween(200), label = "statCellValue") { shown ->
                Text(
                    text = shown.text,
                    fontSize = if (shown.numeric) 24.sp else 15.sp,
                    lineHeight = if (shown.numeric) 30.sp else 22.sp,
                    fontWeight = if (shown.numeric) FontWeight.SemiBold else FontWeight.Medium,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = Xc.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatDivider(line: Color) {
    Box(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .width(1.dp)
            .height(32.dp)
            .background(line),
    )
}

/** 设备表：标签在左、真值在右；刷新入口挂在分组标题上（删掉「常用操作」后的手动入口）。 */
@Composable
private fun DeviceSection(state: HomeUiState, onRefresh: () -> Unit) {
    Xc8SectionLabel(
        text = stringResource(R.string.home_section_device),
        modifier = Modifier.padding(top = 22.dp),
        trailing = {
            Row(
                modifier = Modifier
                    .clip(Xc.shapes.xs)
                    .clickable(onClick = onRefresh)
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_refresh),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.accent,
                )
                Xc8Chevron()
            }
        },
    )
    Xc8Panel {
        Xc8Row(
            title = stringResource(R.string.home_kernel),
            leading = Icons.Rounded.DeveloperBoard,
            trailing = {
                // 2026-11-11 用户要求：这里只显示「6.12.23」这种三段式版本号，
                // 不再带 `-android12-9` 之类后缀。KernelVersion.toString() 正好是这个形状。
                Xc8Value(
                    if (state.kernelVersion.major >= 0) state.kernelVersion.toString() else "—",
                )
            },
        )
        // 2026-11-14 用户要求：设备区把「Zygisk 实现」换成「TEE 实现」。
        // 这一行说的是密钥证明链由谁接管，不是第三方 Zygisk 的运行状态。
        Xc8Row(
            title = stringResource(R.string.home_tee_engine),
            leading = Icons.Rounded.Security,
            trailing = { Xc8Value(stringResource(R.string.home_tee_engine_value)) },
        )
        Xc8Row(
            title = stringResource(R.string.home_device_model),
            leading = Icons.Rounded.Smartphone,
            trailing = { Xc8Value(state.systemInfo.deviceModel) },
        )
        // 2026-11-11 第二轮：删掉「管理器版本」这一行。
        // 2026-11-11 第三轮：再删掉 SELinux 这一行 —— 它在下面的「安全状态」里已经有一条，
        // 同一屏出现两次是设计要求点名的重复。设备区只留 内核版本 / 设备型号。
        // （数据源没动：SELinux 仍然在 systemInfo 里，只是不再在这里画。）
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
    val click = {
        if (newVersion.changelog.isEmpty()) {
            actions.onOpenUrl(newVersion.downloadUrl)
        } else {
            updateDialog.showConfirm(
                title = title,
                content = newVersion.changelog,
                markdown = true,
                confirm = updateText,
            )
        }
    }

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Xc8Panel(
            modifier = Modifier.padding(top = 20.dp),
            block = updateBlock,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = click)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.new_version_available,
                            newVersion.versionCode.toString(),
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Xc.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.home_update_tap_hint),
                        fontSize = 13.sp,
                        color = Xc.colors.textMuted,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(Xc.shapes.pill)
                        .background(Xc.colors.accent)
                        .clickable(onClick = click)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = updateText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** 安全状态：SELinux / SUSFS / Seccomp / 启动状态，异常档直接给标签。 */
@Composable
private fun SecurityPanel(state: HomeUiState) {
    // 文案统一走 HomeStatItem.kt 里的 selinuxLabel()：设备表 / 安全面板 / 数据面板
    // 三处曾经各抄一份 when，删一处就会漏一处。
    val selinuxText = selinuxLabel(state.systemInfo.selinuxStatus)
    val seccompText = seccompLabel(state.systemInfo.seccompStatus)
    // SUSFS 行：null = 探测不到（无 root / shell 失败）→ 整行隐藏；
    // "unsupport" = 内核没打 SUSFS 补丁，明确告知用户而不是静默。
    val susfsVersion = state.susfsVersion
    Xc8Panel {
        Xc8Row(
            title = stringResource(R.string.home_selinux_status),
            leading = Icons.Rounded.Security,
            trailing = {
                Xc8Tag(
                    text = selinuxText,
                    // "Unknown"（getenforce 失败或被拒）以前落到 else → Success，也就是画成
                    // 一片绿 —— 那是把"没问到"当成"正常"。三态各自给色。
                    tone = when (state.systemInfo.selinuxStatus) {
                        "Enforcing" -> Xc8Tone.Success
                        "Permissive" -> Xc8Tone.Warning
                        else -> Xc8Tone.Neutral
                    },
                )
            },
        )
        if (susfsVersion != null) {
            val susfsSupported = susfsVersion != "unsupport"
            val susfsText = when {
                !susfsSupported -> stringResource(R.string.home_susfs_unsupported)
                state.susfsVariant != null -> "$susfsVersion · ${state.susfsVariant}"
                else -> susfsVersion
            }
            Xc8Row(
                title = stringResource(R.string.home_susfs_status),
                subtitle = susfsText,
                leading = Icons.Rounded.Extension,
                trailing = {
                    Xc8Tag(
                        text = if (susfsSupported) {
                            stringResource(R.string.home8_tag_on)
                        } else {
                            stringResource(R.string.home8_tag_off)
                        },
                        tone = if (susfsSupported) Xc8Tone.Success else Xc8Tone.Neutral,
                    )
                },
            )
        }
        Xc8Row(
            title = stringResource(R.string.home_seccomp_status),
            leading = Icons.Rounded.FilterList,
            trailing = { Xc8Value(seccompText) },
        )
        Xc8Row(
            title = stringResource(R.string.home8_boot_state),
            leading = Icons.Rounded.PowerSettingsNew,
            trailing = {
                if (state.isSafeMode) {
                    Xc8Tag(text = stringResource(R.string.safe_mode), tone = Xc8Tone.Warning)
                } else {
                    Xc8Tag(text = stringResource(R.string.home8_tag_stable), tone = Xc8Tone.Success)
                }
            },
        )
    }
}

/** 运行概况：只放"点得动、且点进去真有东西"的两条，不造没有来源的数字。 */
@Composable
private fun OverviewPanel(
    state: HomeUiState,
    rootGrantCount: Int,
    actions: HomeActions,
) {
    Xc8Panel {
        Xc8Row(
            title = stringResource(R.string.home8_root_count),
            subtitle = stringResource(R.string.home8_root_count_sub),
            leading = Icons.Rounded.Security,
            onClick = actions.onOpenSuperuser,
            trailing = {
                Xc8Value(
                    text = if (state.ksuVersion == null) {
                        stringResource(R.string.home8_tag_off)
                    } else {
                        rootGrantCount.toString()
                    },
                    tint = Xc.colors.text,
                )
                Xc8Chevron()
            },
        )
        Xc8Row(
            title = stringResource(R.string.home8_auth_records),
            subtitle = stringResource(R.string.home8_auth_records_sub),
            leading = Icons.AutoMirrored.Rounded.MenuBook,
            onClick = actions.onOpenSulog,
            trailing = { Xc8Chevron() },
        )
    }
}

/** 支持与关于。 */
@Composable
private fun SupportPanel(
    actions: HomeActions,
    block: XcBlockBackground? = null,
) {
    val learnMoreUrl = stringResource(R.string.home_learn_kernelsu_url)
    var showDevelopers by remember { mutableStateOf(false) }

    Xc8Panel(block = block) {
        Xc8Row(
            title = stringResource(R.string.home_support_title),
            subtitle = stringResource(R.string.home_support_content),
            leading = Icons.Rounded.VolunteerActivism,
            onClick = { showDevelopers = true },
            trailing = { Xc8Chevron() },
        )
        Xc8Row(
            title = stringResource(R.string.home_learn_kernelsu),
            subtitle = stringResource(R.string.home_click_to_learn_kernelsu),
            leading = Icons.AutoMirrored.Rounded.MenuBook,
            onClick = { actions.onOpenUrl(learnMoreUrl) },
            trailing = { Xc8Chevron() },
        )
    }

    XDialog(
        show = showDevelopers,
        onDismissRequest = { showDevelopers = false },
    ) {
        XDialogTitle(text = stringResource(R.string.home_support_title))
        // 这里不要再挂 verticalScroll：XGlassDialog 的面板本身就是一个纵向滚动容器，
        // 子项再嵌一层同方向滚动时 Compose 会直接抛
        // IllegalStateException: Vertically scrollable component was measured with
        // an infinity maximum height constraints。
        Text(
            text = stringResource(R.string.home_developers_list),
            color = Xc.colors.textSecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
        )
    }
}

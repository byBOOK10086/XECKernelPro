package me.weishu.kernelsu.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import me.weishu.kernelsu.ui.util.getSuperuserCount
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
 *  - 首屏不再让"模式卡"独占整块；状态收成一行大字（[HomeHero]），一屏读得出"能不能用"；
 *  - 三格数据（内核 / 管理器 / 超级用户）是上下两条发丝线夹出来的，不是三张卡；
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { HomeHeader(onRefresh = actions.onRefresh) },
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

                    HomeHero(state = state, onInstall = actions.onInstallClick)

                    HomeStats(state = state, rootGrantCount = rootGrantCount)

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
}

/**
 * 顶栏：应用名 + 一行机器可读的元信息（版本号 · 机型），右侧两个图标按钮。
 *
 * 不复用 miuix `TopAppBar`：设计稿的标题下面压着一行小字，TopAppBar 的标题槽只有一行，
 * 硬塞副标题要改它的排版规则；这里就是一个 [statusBarsPadding] 包住的 Row，行为可预期。
 */
@Composable
private fun HomeHeader(
    onRefresh: () -> Unit,
) {
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
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.home_refresh),
                tint = Xc.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 首屏结论：一个状态点 + 一行 34sp 的大字 + 一行说明，底下一条发丝线。 */
@Composable
private fun HomeHero(state: HomeUiState, onInstall: () -> Unit) {
    val running = state.ksuVersion != null
    val mount = state.isMountState
    // 审计 P1（2026-11-09）：之前只看"驱动有没有应答"，驱动在但 su 被拒时仍显示
    // ROOT READY。就绪判据改成实际的 root 可用性。
    val active = state.isRootAvailable
    // 2026-11-09 用户要求：管理器已经拿到 root、但内核模块还没装/没跑起来时，
    // 不再说"内核未运行"，而是明确告诉用户"可以直接装"，并且这块本身就是入口。
    val canInstall = !running && !mount && state.isRootAvailable
    // 冷启动/刚重启那段，root 探测还没落定。此时 isRootAvailable 必然 false，
    // 直接画红点会得到"内核正常运行 + ROOT 未就绪"这种自相矛盾的首帧
    // （2026-11-09 用户真机反馈）。未落定 → 灰点 + 「正在获取 Root」。
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
        mount -> stringResource(R.string.home_hero_mount)
        canInstall -> stringResource(R.string.home_hero_ready_install)
        else -> stringResource(R.string.home_hero_inactive)
    }
    val modeText = when {
        mount -> stringResource(R.string.home_hero_mount_mode)
        state.lkmMode == true -> "LKM"
        running -> stringResource(R.string.home_hero_kernel_mode)
        else -> stringResource(R.string.home_not_installed)
    }
    val selinuxText = when (state.systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }

    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(statusLabel),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Xc.colors.textMuted,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 13.dp)
                .then(if (canInstall) Modifier.clickable(onClick = onInstall) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Xc.typography.display,
                color = Xc.colors.text,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 2026-11-09 用户要求：和 KernelSU 首页同款的"已运行"对勾。
            // 只在**内核真的在跑**时出现 —— 只是拿到 root 但没装内核时不画，那会变假绿。
            if (running) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Xc.colors.success,
                    modifier = Modifier.size(26.dp),
                )
            }
            // 有 root、没内核：把这块本身做成安装入口（用户反馈找不到安装入口）。
            if (canInstall) {
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(Xc.shapes.pill)
                        .background(Xc.colors.accent)
                        .clickable(onClick = onInstall)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.install),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = "$modeText · SELinux $selinuxText",
            fontSize = 12.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.padding(top = 9.dp),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Xc.colors.glassRim),
        )
    }
}

/** 三格数据：内核 / 管理器 / 超级用户，上下发丝线夹住，数值在下、标签更小。 */
@Composable
private fun HomeStats(state: HomeUiState, rootGrantCount: Int) {
    val line = Xc.colors.glassRim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(
            value = if (state.kernelVersion.major >= 0) {
                "${state.kernelVersion.major}.${state.kernelVersion.patchLevel}"
            } else {
                "—"
            },
            label = stringResource(R.string.home_stat_kernel),
            modifier = Modifier.weight(1f),
        )
        StatDivider(line)
        StatCell(
            value = state.currentManagerVersionCode.toString(),
            label = stringResource(R.string.home_stat_manager),
            modifier = Modifier.weight(1f),
        )
        StatDivider(line)
        StatCell(
            value = rootGrantCount.toString(),
            label = stringResource(R.string.home_stat_superuser),
            modifier = Modifier.weight(1f),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(line),
    )
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = 2.dp)) {
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = Xc.colors.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = Xc.colors.textMuted,
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
    val selinuxText = when (state.systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
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
                    fontSize = 12.sp,
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
            trailing = { Xc8Value(state.systemInfo.kernelVersion) },
        )
        Xc8Row(
            title = stringResource(R.string.home_device_model),
            trailing = { Xc8Value(state.systemInfo.deviceModel) },
        )
        Xc8Row(
            title = stringResource(R.string.home_selinux_status),
            trailing = {
                Xc8Tag(
                    text = selinuxText,
                    tone = if (state.isSELinuxPermissive) Xc8Tone.Warning else Xc8Tone.Success,
                )
            },
        )
        Xc8Row(
            title = stringResource(R.string.home_manager_version),
            trailing = { Xc8Value(state.systemInfo.managerVersion) },
        )
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Xc.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.home_update_tap_hint),
                        fontSize = 11.sp,
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
                        fontSize = 12.5.sp,
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
    val selinuxText = when (state.systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccompText = when (state.systemInfo.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }
    // SUSFS 行：null = 探测不到（无 root / shell 失败）→ 整行隐藏；
    // "unsupport" = 内核没打 SUSFS 补丁，明确告知用户而不是静默。
    val susfsVersion = state.susfsVersion
    Xc8Panel {
        Xc8Row(
            title = stringResource(R.string.home_selinux_status),
            leading = Icons.Filled.Security,
            trailing = {
                Xc8Tag(
                    text = selinuxText,
                    tone = if (state.isSELinuxPermissive) Xc8Tone.Warning else Xc8Tone.Success,
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
            leading = Icons.Filled.FilterList,
            trailing = { Xc8Value(seccompText) },
        )
        Xc8Row(
            title = stringResource(R.string.home8_boot_state),
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
            leading = Icons.Filled.Security,
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
            leading = Icons.AutoMirrored.Filled.MenuBook,
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
            leading = Icons.Filled.VolunteerActivism,
            onClick = { showDevelopers = true },
            trailing = { Xc8Chevron() },
        )
        Xc8Row(
            title = stringResource(R.string.home_learn_kernelsu),
            subtitle = stringResource(R.string.home_click_to_learn_kernelsu),
            leading = Icons.AutoMirrored.Filled.MenuBook,
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
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
        )
    }
}

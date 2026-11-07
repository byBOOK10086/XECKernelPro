package me.weishu.kernelsu.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.rebootlistpopup.RebootListPopupMiuix
import me.weishu.kernelsu.ui.design.glass.rememberXcBlock
import me.weishu.kernelsu.ui.design.island.IslandTopBar
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
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 首页（MIUI 8 全盘换装后的版式）。
 *
 * 版式来源：`miui8-kernelsu-prototype` 的首页 —— 模式卡是唯一焦点，
 * 下面依次是系统信息、安全状态、运行概况、常用操作、支持与关于，
 * 全部是"白色分组面板 + 1px 分隔线"。
 *
 * 与上一版的关键差异不是换皮，是换信息结构：
 *  - 删掉英雄卡（评分环 + 检测 + 分享）与卡片外观编辑：评分属于检测页，
 *    首页不该同时承担"总览"和"打分"两件事；外观编辑是上一版玻璃形态的产物，
 *    方角面板没有折射可调，留着只会是入口找不到落点。
 *  - 删掉状态卡上的大对勾与 140dp 定高：模式卡改成自适应高度，收起只留
 *    徽标 + 模式名 + 状态，展开才列出四格详情。
 *  - 每个面板的行都是"标题 + 副标题 + 右值"，信息密度靠行承载而不是靠卡片堆叠。
 *
 * 告警与更新条仍排在最前面：它们是异常态才出现的东西，正常设备上模式卡就是第一屏。
 */
@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    // 首页七处 WarningCard 共用的区块背景（告警卡在日志页也在用，所以"首页那块背景"
    // 的取值留在这一层，不跟着组件跑到别的页面去）。
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
                        modifier = Modifier.padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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

                        HomeModeCard(
                            state = state,
                            actions = actions,
                            block = rememberXcBlock(XcBlocks.HOME_STATUS),
                        )

                        // 未激活态：把「提权」单独抬成一块，而不是混进常用操作 ——
                        // 这时它是用户唯一能推进的动作，和"刷新"并列会被淹掉。
                        if (state.ksuVersion == null && !state.isMountState) {
                            Xc8Panel(block = rememberXcBlock(XcBlocks.HOME_PRIVILEGE)) {
                                Xc8Row(
                                    title = stringResource(R.string.privilege_title),
                                    subtitle = stringResource(R.string.privilege_entry_subtitle),
                                    leading = Icons.Rounded.Bolt,
                                    onClick = actions.onPrivilegeClick,
                                    trailing = { Xc8Chevron() },
                                )
                            }
                        }

                        Xc8SectionLabel(text = stringResource(R.string.home8_section_info))
                        SystemInfoPanel(state = state, block = rememberXcBlock(XcBlocks.HOME_INFO))
                        Xc8SectionLabel(text = stringResource(R.string.home8_section_security))
                        SecurityPanel(state = state)
                        Xc8SectionLabel(text = stringResource(R.string.home8_section_overview))
                        OverviewPanel(
                            state = state,
                            rootGrantCount = rootGrantCount,
                            actions = actions,
                        )
                        Xc8SectionLabel(text = stringResource(R.string.home8_section_actions))
                        ActionsPanel(state = state, actions = actions)
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
 * 内容做模糊与折射，只是不再压任何颜色，滚动时标题像是直接浮在页面上。
 *
 * 前景跟着明暗档走（[Xc.colors.text]）：透明底下的画面就是页面本身，深色档配浅色字、
 * 浅色档配深色字，和页面正文一致。
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
            // 挂载态也是"在跑"：路线名用 Magisk 路线，不能显示成未工作（会和模式卡自相矛盾）。
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

/** 系统信息：六行纯文本行，右值一律是当前读到的真实值。 */
@Composable
private fun SystemInfoPanel(
    state: HomeUiState,
    block: XcBlockBackground? = null,
) {
    val modeKeyword = homeModeKeyword(state)
    val ksuVersionText = when {
        state.ksuVersion != null ->
            listOfNotNull(state.ksuVersion.toString(), modeKeyword).joinToString(" · ")
        state.isMountState -> state.magiskVersion?.let { "Magisk $it" } ?: "Magisk"
        else -> stringResource(R.string.home_not_installed)
    }
    Xc8Panel(block = block) {
        Xc8Row(
            title = stringResource(R.string.home8_ksu_version),
            subtitle = ksuVersionText,
            leading = Icons.Filled.Tag,
        )
        Xc8Row(
            title = stringResource(R.string.home_manager_version),
            subtitle = state.systemInfo.managerVersion,
            leading = Icons.Filled.Download,
        )
        Xc8Row(
            title = stringResource(R.string.home_kernel),
            subtitle = state.systemInfo.kernelVersion,
            leading = Icons.Filled.DeveloperBoard,
        )
        Xc8Row(
            title = stringResource(R.string.home_device_model),
            subtitle = state.systemInfo.deviceModel,
            leading = Icons.Filled.Smartphone,
        )
        Xc8Row(
            title = stringResource(R.string.home_fingerprint),
            subtitle = state.systemInfo.fingerprint,
            leading = Icons.Filled.Fingerprint,
        )
        if (state.latestVersionInfo.downloadCount > 0) {
            Xc8Row(
                title = stringResource(R.string.home_download_count),
                subtitle = "%,d".format(state.latestVersionInfo.downloadCount),
            )
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

/** 常用操作：安装/更新内核、管理 Root、刷新，三条都能直接落到具体页面。 */
@Composable
private fun ActionsPanel(
    actions: HomeActions,
) {
    Xc8Panel {
        Xc8Row(
            title = stringResource(R.string.home8_install_kernel),
            subtitle = stringResource(R.string.home8_install_kernel_sub),
            onClick = actions.onInstallClick,
            trailing = { Xc8Chevron() },
        )
        Xc8Row(
            title = stringResource(R.string.home8_manage_root),
            subtitle = stringResource(R.string.home8_manage_root_sub),
            onClick = actions.onOpenSuperuser,
            trailing = { Xc8Chevron() },
        )
        Xc8Row(
            title = stringResource(R.string.home8_refresh),
            subtitle = stringResource(R.string.home8_refresh_sub),
            leading = Icons.Rounded.Refresh,
            onClick = actions.onRefresh,
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val actions = HomeActions({}, {})
            val state = previewHomeScreenState(
                ksuVersion = ksuVersion,
                lkmMode = lkmMode,
                isSafeMode = isSafeMode,
                isLateLoadMode = isLateLoadMode,
                selinuxStatus = selinuxStatus,
            )
            HomeModeCard(state = state, actions = actions)
            Xc8SectionLabel(text = stringResource(R.string.home8_section_info))
            SystemInfoPanel(state = state)
            Xc8SectionLabel(text = stringResource(R.string.home8_section_security))
            SecurityPanel(state = state)
            Xc8SectionLabel(text = stringResource(R.string.home8_section_overview))
            OverviewPanel(state = state, rootGrantCount = 0, actions = actions)
            Xc8SectionLabel(text = stringResource(R.string.home8_section_actions))
            ActionsPanel(actions = actions)
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
    baseVersionCode = 31271,
    systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
    kernelUAPIVersion = 1,
    managerUAPIVersion = 1,
)

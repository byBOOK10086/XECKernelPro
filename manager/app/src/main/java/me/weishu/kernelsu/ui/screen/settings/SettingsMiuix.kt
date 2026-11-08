package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.KsuIsValid
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.component.miuix.SendLogDialog
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.uninstalldialog.UninstallDialog
import me.weishu.kernelsu.ui.design.island.IslandTopBar
import me.weishu.kernelsu.ui.design.panel.Xc8Dropdown
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val loadingDialog = rememberLoadingDialog()
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
    val showUnloadConfirm = rememberSaveable { mutableStateOf(false) }
    var unloading by rememberSaveable { mutableStateOf(false) }

    // 零足迹卸载确认：跑 ksud unload（恢复钩子、停守护、删模块），随后 stop/start
    // 全部 Android 服务——界面会闪一下，属预期。
    val unloadConfirm = rememberConfirmDialog(
        onConfirm = {
            unloading = true
            com.topjohnwu.superuser.Shell.cmd(
                me.weishu.kernelsu.ui.util.getKsuDaemonPath() + " unload"
            ).submit { unloading = false }
        }
    )
    if (showUnloadConfirm.value) {
        unloadConfirm.showConfirm(
            title = stringResource(id = R.string.settings_unload_title),
            content = stringResource(id = R.string.settings_unload_confirm),
        )
        showUnloadConfirm.value = false
    }
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 灵动岛：顶栏本身就是岛的展开态（无玻璃底）；上滑收缩成岛。
            IslandTopBar(
                islandTitle = stringResource(R.string.settings),
                islandLive = null,
                statusActive = me.weishu.kernelsu.Natives.isFullFeatured(),
            ) {
                TopAppBar(
                    color = Color.Transparent,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
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
                    // MIUI 8 白色分组面板：整行可点等价于原来 miuix 开关的"点行即切换"，
                    // 开关本体仍单独响应，两者都走同一个 onSet 回调。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(id = R.string.settings_check_update),
                            subtitle = stringResource(id = R.string.settings_check_update_summary),
                            leading = Icons.Rounded.SystemUpdate,
                            onClick = { actions.onSetCheckUpdate(!uiState.checkUpdate) },
                            trailing = {
                                Xc8Switch(
                                    checked = uiState.checkUpdate,
                                    onCheckedChange = actions.onSetCheckUpdate,
                                )
                            },
                        )
                        KsuIsValid {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_module_check_update),
                                subtitle = stringResource(id = R.string.settings_check_update_summary),
                                leading = Icons.Rounded.SystemUpdateAlt,
                                onClick = { actions.onSetCheckModuleUpdate(!uiState.checkModuleUpdate) },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.checkModuleUpdate,
                                        onCheckedChange = actions.onSetCheckModuleUpdate,
                                    )
                                },
                            )
                        }
                    }

                    // 主题 / 主题商城 / 壁纸都是"进去改外观"的入口，收进同一块面板，
                    // 与上面的开关行保持同一种分组语言（原来是三张 miuix 卡里的箭头行）。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(id = R.string.settings_theme),
                            subtitle = stringResource(id = R.string.settings_theme_summary),
                            leading = Icons.Rounded.Palette,
                            onClick = actions.onOpenTheme,
                            trailing = { Xc8Chevron() },
                        )
                        // 主题商城与编辑器：调色屏改的是"当前外观"，这里管整套皮肤
                        // （配色 + 圆角 + 玻璃 + 背景），可以下载别人的、也可以自己做完发布。
                        val themesTitle = stringResource(id = R.string.settings_themes)
                        Xc8Row(
                            title = themesTitle,
                            subtitle = stringResource(id = R.string.settings_themes_summary),
                            leading = Icons.Rounded.Style,
                            onClick = actions.onOpenThemes,
                            trailing = { Xc8Chevron() },
                        )
                        // 自定义背景与主题是同一层的决定：主题决定玻璃令牌，背景决定玻璃折射谁。
                        // 摘要直接说清"哪几档被换掉了"，避免用户进去才发现只改了浅色档。
                        val wallpaperLightCustom = uiState.wallpaperLight.isNotEmpty()
                        val wallpaperDarkCustom = uiState.wallpaperDark.isNotEmpty()
                        val wallpaperSummary = when {
                            wallpaperLightCustom && wallpaperDarkCustom ->
                                stringResource(id = R.string.wallpaper_summary_both)

                            wallpaperLightCustom -> stringResource(id = R.string.wallpaper_summary_light)
                            wallpaperDarkCustom -> stringResource(id = R.string.wallpaper_summary_dark)
                            else -> stringResource(id = R.string.wallpaper_summary_builtin)
                        }
                        val wallpaperTitle = stringResource(id = R.string.settings_wallpaper)
                        Xc8Row(
                            title = wallpaperTitle,
                            subtitle = wallpaperSummary,
                            leading = Icons.Rounded.Wallpaper,
                            onClick = actions.onOpenWallpaper,
                            trailing = { Xc8Chevron() },
                        )
                    }

                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        val remoteAssistant = stringResource(id = R.string.remote_assistant_title)
                        Xc8Row(
                            title = remoteAssistant,
                            subtitle = stringResource(id = R.string.remote_assistant_subtitle),
                            leading = Icons.Rounded.GetApp,
                            onClick = actions.onOpenRemoteAssistant,
                            trailing = { Xc8Chevron() },
                        )
                    }

                    KsuIsValid {
                        Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                            val profileTemplate = stringResource(id = R.string.settings_profile_template)
                            Xc8Row(
                                title = profileTemplate,
                                subtitle = stringResource(id = R.string.settings_profile_template_summary),
                                leading = Icons.Rounded.Description,
                                onClick = actions.onOpenProfileTemplate,
                                trailing = { Xc8Chevron() },
                            )
                        }
                    }

                    KsuIsValid {
                        Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                            val suCompatModeItems = listOf(
                                stringResource(id = R.string.settings_mode_enable_by_default),
                                stringResource(id = R.string.settings_mode_disable_until_reboot),
                                stringResource(id = R.string.settings_mode_disable_always),
                            )

                            val suSummary = when (uiState.suCompatStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sucompat_summary)
                            }
                            Xc8Dropdown(
                                title = stringResource(id = R.string.settings_sucompat),
                                subtitle = suSummary,
                                items = suCompatModeItems,
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onSelectedIndexChange = actions.onSetSuCompatMode,
                                cancelText = stringResource(id = R.string.cancel),
                            )
                        }

                        // 六个特性开关收进一块面板：sucompat 是下拉选择，语义不同，留在卡片里。
                        Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                            val umountSupported = uiState.kernelUmountStatus == "supported"
                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            Xc8Row(
                                title = stringResource(id = R.string.settings_kernel_umount),
                                subtitle = umountSummary,
                                leading = Icons.Rounded.LayersClear,
                                onClick = if (umountSupported) {
                                    { actions.onSetKernelUmountEnabled(!uiState.isKernelUmountEnabled) }
                                } else {
                                    null
                                },
                                // 不支持时整行置灰：只把开关变浅会留下"标题还能点"的假可用。
                                enabled = umountSupported,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isKernelUmountEnabled,
                                        onCheckedChange = actions.onSetKernelUmountEnabled,
                                        enabled = umountSupported,
                                    )
                                },
                            )

                            val selinuxHideSupported = uiState.selinuxHideStatus == "supported"
                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            Xc8Row(
                                title = stringResource(id = R.string.settings_selinux_hide),
                                subtitle = selinuxHideSummary,
                                leading = Icons.Rounded.Security,
                                onClick = if (selinuxHideSupported) {
                                    { actions.onSetSelinuxHideEnabled(!uiState.isSelinuxHideEnabled) }
                                } else {
                                    null
                                },
                                // 状态不是 supported 时内核侧没有这个特性，整行同步置灰。
                                enabled = selinuxHideSupported,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isSelinuxHideEnabled,
                                        onCheckedChange = actions.onSetSelinuxHideEnabled,
                                        enabled = selinuxHideSupported,
                                    )
                                },
                            )

                            val sulogSupported = uiState.sulogStatus == "supported"
                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            Xc8Row(
                                title = stringResource(id = R.string.settings_sulog),
                                subtitle = sulogSummary,
                                leading = Icons.AutoMirrored.Rounded.Article,
                                onClick = if (sulogSupported) {
                                    { actions.onSetSulogEnabled(!uiState.isSulogEnabled) }
                                } else {
                                    null
                                },
                                // 日志特性未就绪时标题/副标题/图标一起转灰，避免误以为可开。
                                enabled = sulogSupported,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isSulogEnabled,
                                        onCheckedChange = actions.onSetSulogEnabled,
                                        enabled = sulogSupported,
                                    )
                                },
                            )

                            val adbRootSupported = uiState.adbRootStatus == "supported"
                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            Xc8Row(
                                title = stringResource(id = R.string.settings_adb_root),
                                subtitle = adbRootSummary,
                                leading = Icons.Rounded.Adb,
                                onClick = if (adbRootSupported) {
                                    { actions.onSetAdbRootEnabled(!uiState.isAdbRootEnabled) }
                                } else {
                                    null
                                },
                                // ADB root 由内核特性位决定，不支持时整行置灰。
                                enabled = adbRootSupported,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isAdbRootEnabled,
                                        onCheckedChange = actions.onSetAdbRootEnabled,
                                        enabled = adbRootSupported,
                                    )
                                },
                            )
                            // 内置 TEE/TS 引擎总开关。只关伪造的证明链，不动 root：
                            // 引擎是纯用户态注入，LKM、su、模块、隐藏全部照常。
                            Xc8Row(
                                title = stringResource(id = R.string.settings_tee_engine),
                                subtitle = stringResource(id = R.string.settings_tee_engine_summary),
                                leading = Icons.Rounded.AdminPanelSettings,
                                onClick = { actions.onSetTeeEngineEnabled(!uiState.isTeeEngineEnabled) },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isTeeEngineEnabled,
                                        onCheckedChange = actions.onSetTeeEngineEnabled,
                                    )
                                },
                            )
                            // late-load 下软重启不可用（内核还没接管），开关与整行一起禁用。
                            val softRebootEnabled = !uiState.isLateLoadMode
                            Xc8Row(
                                title = stringResource(id = R.string.settings_soft_reboot),
                                subtitle = stringResource(id = R.string.settings_soft_reboot_summary),
                                leading = Icons.Rounded.RestartAlt,
                                onClick = if (softRebootEnabled) {
                                    { actions.onSetUseSoftReboot(!(uiState.isLateLoadMode || uiState.useSoftReboot)) }
                                } else {
                                    null
                                },
                                enabled = softRebootEnabled,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                        onCheckedChange = actions.onSetUseSoftReboot,
                                        enabled = softRebootEnabled,
                                    )
                                },
                            )
                        }

                        Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                subtitle = stringResource(id = R.string.settings_umount_modules_default_summary),
                                leading = Icons.AutoMirrored.Rounded.Rule,
                                onClick = {
                                    actions.onSetDefaultUmountModules(!uiState.isDefaultUmountModules)
                                },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.isDefaultUmountModules,
                                        onCheckedChange = actions.onSetDefaultUmountModules,
                                    )
                                },
                            )
                            Xc8Row(
                                title = stringResource(id = R.string.enable_web_debugging),
                                subtitle = stringResource(id = R.string.enable_web_debugging_summary),
                                leading = Icons.Rounded.DeveloperMode,
                                onClick = { actions.onSetEnableWebDebugging(!uiState.enableWebDebugging) },
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.enableWebDebugging,
                                        onCheckedChange = actions.onSetEnableWebDebugging,
                                    )
                                },
                            )
                            // 自动越狱只在 late-load 下有意义，其余形态整行与开关都禁用。
                            val autoJailbreakEnabled = uiState.isLateLoadMode
                            Xc8Row(
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                subtitle = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                leading = Icons.Rounded.FlashOn,
                                onClick = if (autoJailbreakEnabled) {
                                    { actions.onSetAutoJailbreak(!uiState.autoJailbreak) }
                                } else {
                                    null
                                },
                                enabled = autoJailbreakEnabled,
                                trailing = {
                                    Xc8Switch(
                                        checked = uiState.autoJailbreak,
                                        onCheckedChange = actions.onSetAutoJailbreak,
                                        enabled = autoJailbreakEnabled,
                                    )
                                },
                            )
                        }
                    }

                    // 外观：组件透明。单独一块而不是塞进上面的功能开关堆里 ——
                    // 它改的是"所有面板的底色有多实"，属于外观，混在功能列表里没人会想到去找。
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(id = R.string.settings8_component_transparent),
                            subtitle = stringResource(id = R.string.settings8_component_transparent_summary),
                            leading = Icons.Rounded.Opacity,
                            onClick = {
                                actions.onSetComponentTransparent(!uiState.componentTransparent)
                            },
                            trailing = {
                                Xc8Switch(
                                    checked = uiState.componentTransparent,
                                    onCheckedChange = actions.onSetComponentTransparent,
                                )
                            },
                        )
                    }

                    if (uiState.isLkmMode) {
                        Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                            Xc8Row(
                                title = stringResource(id = R.string.settings_uninstall),
                                leading = Icons.Rounded.Delete,
                                onClick = { showUninstallDialog.value = true },
                                // late-load 下内核还没接管，卸载入口必须整行看起来不可用。
                                enabled = !uiState.isLateLoadMode,
                                trailing = { Xc8Chevron() },
                            )
                        }
                        // 对话框不是行，放在面板外：它自己就是一层浮层，塞进面板只会被裁切。
                        UninstallDialog(
                            show = showUninstallDialog.value,
                            onDismissRequest = { showUninstallDialog.value = false }
                        )
                    }

                    Xc8Panel(modifier = Modifier.padding(vertical = 12.dp)) {
                        // 工具箱：终端 / 安全锁（超级密钥）/ 隐身入口 收进一个菜单（低频工具不再占设置一整屏）。
                        Xc8Row(
                            title = stringResource(id = R.string.tools_title),
                            subtitle = stringResource(id = R.string.tools_summary),
                            leading = Icons.Rounded.Handyman,
                            onClick = actions.onOpenTools,
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.settings_unload_title),
                            subtitle = stringResource(id = R.string.settings_unload_summary),
                            leading = Icons.Rounded.Delete,
                            onClick = { showUnloadConfirm.value = true },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.send_log),
                            leading = Icons.Rounded.BugReport,
                            onClick = { showSendLogDialog.value = true },
                            trailing = { Xc8Chevron() },
                        )
                        val about = stringResource(id = R.string.about)
                        Xc8Row(
                            title = about,
                            leading = Icons.Rounded.Info,
                            onClick = actions.onOpenAbout,
                            trailing = { Xc8Chevron() },
                        )
                    }
                    // 发送日志对话框与面板同层：它需要自己的全屏遮罩，不能留在面板内容里。
                    SendLogDialog(
                        show = showSendLogDialog.value,
                        onDismissRequest = { showSendLogDialog.value = false },
                        loadingDialog = loadingDialog
                    )
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}


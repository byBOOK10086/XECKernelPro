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
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.island.IslandTopBar
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import me.weishu.kernelsu.ui.design.token.XcRadius

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

                    Card(cornerRadius = XcRadius.md, 
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_theme),
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_theme),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTheme
                        )
                        // 主题商城与编辑器：调色屏改的是"当前外观"，这里管整套皮肤
                        // （配色 + 圆角 + 玻璃 + 背景），可以下载别人的、也可以自己做完发布。
                        val themesTitle = stringResource(id = R.string.settings_themes)
                        ArrowPreference(
                            title = themesTitle,
                            summary = stringResource(id = R.string.settings_themes_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Style,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = themesTitle,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenThemes
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
                        ArrowPreference(
                            title = wallpaperTitle,
                            summary = wallpaperSummary,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Wallpaper,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = wallpaperTitle,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenWallpaper
                        )
                    }

                    Card(cornerRadius = XcRadius.md, 
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        val remoteAssistant = stringResource(id = R.string.remote_assistant_title)
                        ArrowPreference(
                            title = remoteAssistant,
                            summary = stringResource(id = R.string.remote_assistant_subtitle),
                            startAction = {
                                Icon(
                                    Icons.Rounded.GetApp,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = remoteAssistant,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenRemoteAssistant
                        )
                    }

                    KsuIsValid {
                        Card(cornerRadius = XcRadius.md, 
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                        ) {
                            val profileTemplate = stringResource(id = R.string.settings_profile_template)
                            ArrowPreference(
                                title = profileTemplate,
                                summary = stringResource(id = R.string.settings_profile_template_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Description,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = profileTemplate,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = actions.onOpenProfileTemplate
                            )
                        }
                    }

                    KsuIsValid {
                        Card(cornerRadius = XcRadius.md, 
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                        ) {
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
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.settings_sucompat),
                                summary = suSummary,
                                items = suCompatModeItems,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.AdminPanelSettings,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sucompat),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onSelectedIndexChange = actions.onSetSuCompatMode
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
                        Card(cornerRadius = XcRadius.md, 
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                        ) {
                            val uninstall = stringResource(id = R.string.settings_uninstall)
                            ArrowPreference(
                                title = uninstall,
                                enabled = !uiState.isLateLoadMode,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = uninstall,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUninstallDialog.value = true },
                            )
                            UninstallDialog(
                                show = showUninstallDialog.value,
                                onDismissRequest = { showUninstallDialog.value = false }
                            )
                        }
                    }

                    Card(cornerRadius = XcRadius.md, 
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        // 工具箱：终端 / 安全锁（超级密钥）/ 隐身入口 收进一个菜单（低频工具不再占设置一整屏）。
                        ArrowPreference(
                            title = stringResource(id = R.string.tools_title),
                            summary = stringResource(id = R.string.tools_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Handyman,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTools,
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_unload_title),
                            summary = stringResource(id = R.string.settings_unload_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showUnloadConfirm.value = true },
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.send_log),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.send_log),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = stringResource(id = R.string.about)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}


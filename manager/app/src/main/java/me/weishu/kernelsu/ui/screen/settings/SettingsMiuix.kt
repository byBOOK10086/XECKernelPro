package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.FlashOn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.SuperkeyStore
import me.weishu.kernelsu.ui.component.KsuIsValid
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.component.miuix.SendLogDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.uninstalldialog.UninstallDialog
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.island.IslandTopBar
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    val showSuperkeyDialog = rememberSaveable { mutableStateOf(false) }
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
    if (showSuperkeyDialog.value) {
        SuperkeyDialog(onDismiss = { showSuperkeyDialog.value = false })
    }
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
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.SystemUpdate,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                        KsuIsValid {
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_module_check_update),
                                summary = stringResource(id = R.string.settings_check_update_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.SystemUpdateAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_check_update),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.checkModuleUpdate,
                                onCheckedChange = actions.onSetCheckModuleUpdate
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

                    Card(
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
                        Card(
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
                        Card(
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

                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_kernel_umount),
                                summary = umountSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.LayersClear,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_kernel_umount),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.kernelUmountStatus == "supported",
                                checked = uiState.isKernelUmountEnabled,
                                onCheckedChange = actions.onSetKernelUmountEnabled
                            )

                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_selinux_hide),
                                summary = selinuxHideSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Security,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_selinux_hide),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.selinuxHideStatus == "supported",
                                checked = uiState.isSelinuxHideEnabled,
                                onCheckedChange = actions.onSetSelinuxHideEnabled
                            )

                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_sulog),
                                summary = sulogSummary,
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Article,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sulog),
                                        tint = if (uiState.sulogStatus == "supported") colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.sulogStatus == "supported",
                                checked = uiState.isSulogEnabled,
                                onCheckedChange = actions.onSetSulogEnabled
                            )

                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_adb_root),
                                summary = adbRootSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Adb,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_adb_root),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.adbRootStatus == "supported",
                                checked = uiState.isAdbRootEnabled,
                                onCheckedChange = actions.onSetAdbRootEnabled
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_soft_reboot),
                                summary = stringResource(id = R.string.settings_soft_reboot_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_soft_reboot),
                                        tint = if (uiState.isLateLoadMode) colorScheme.disabledOnSecondaryVariant else colorScheme.onBackground
                                    )
                                },
                                enabled = !uiState.isLateLoadMode,
                                checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                onCheckedChange = actions.onSetUseSoftReboot
                            )
                        }

                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                        ) {
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Rule,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_umount_modules_default),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.isDefaultUmountModules,
                                onCheckedChange = actions.onSetDefaultUmountModules
                            )

                            SwitchPreference(
                                title = stringResource(id = R.string.enable_web_debugging),
                                summary = stringResource(id = R.string.enable_web_debugging_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeveloperMode,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.enable_web_debugging),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableWebDebugging,
                                onCheckedChange = actions.onSetEnableWebDebugging
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.FlashOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_auto_jailbreak),
                                        tint = if (uiState.isLateLoadMode) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.isLateLoadMode,
                                checked = uiState.autoJailbreak,
                                onCheckedChange = actions.onSetAutoJailbreak
                            )
                        }
                    }

                    if (uiState.isLkmMode) {
                        Card(
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

                    Card(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        val terminal = stringResource(id = R.string.terminal)
                        ArrowPreference(
                            title = terminal,
                            summary = stringResource(id = R.string.settings_terminal_subtitle),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Code,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = terminal,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTerminal,
                        )
                        // 零足迹卸载：ksud unload（钩子恢复/守护停止/模块删除）。
                        // APatch 式超级密钥：策略级操作的第二因素。
                        ArrowPreference(
                            title = stringResource(id = R.string.superkey_settings_title),
                            summary = stringResource(id = R.string.superkey_settings_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Key,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSuperkeyDialog.value = true },
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

/**
 * APatch 式超级密钥设置（借鉴 bmax121/APatch 的 superkey 模型）：
 * 设置/更换/清除内核密钥 + 守卫武装开关。内核只存 SHA-256、掉电即失——
 * 重启后需重新武装（这也是忘记密钥时的恢复路径）。
 */
@Composable
private fun SuperkeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<SuperkeyStore.KernelStatus?>(null) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var oldKey by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { status = SuperkeyStore.kernelStatus() }

    XDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            XDialogTitle(text = stringResource(R.string.superkey_settings_title))
            Text(
                text = stringResource(R.string.superkey_status,
                    stringResource(if (status?.set == true) R.string.superkey_state_set else R.string.superkey_state_unset),
                    stringResource(if (status?.guardArmed == true) R.string.superkey_state_armed else R.string.superkey_state_disarmed)),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.superkey_dialog_hint),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            if (status?.set == true) {
                TextField(
                    value = oldKey,
                    onValueChange = { oldKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.superkey_old_key_label),
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
            }
            TextField(
                value = newKey,
                onValueChange = { newKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.superkey_new_key_label),
                maxLines = 1,
            )
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(
                    text = stringResource(R.string.superkey_generate),
                    onClick = { newKey = SuperkeyStore.generateKey() },
                )
                Spacer(Modifier.weight(1f))
            }
            message?.let {
                Text(it, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.superkey_arm_guard),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    enabled = newKey.isNotBlank() || status?.set == true,
                    checked = status?.guardArmed == true,
                    onCheckedChange = { arm ->
                        val ok = SuperkeyStore.armGuard(arm)
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.superkey_apply),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val old = oldKey.ifBlank { null }
                        val ok = SuperkeyStore.setKernelKey(newKey, old)
                        if (ok) {
                            SuperkeyStore.saveLocal(context, newKey)
                            newKey = ""
                            oldKey = ""
                        }
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.superkey_clear),
                    modifier = Modifier.weight(1f),
                    enabled = status?.set == true,
                    onClick = {
                        val ok = SuperkeyStore.clearKernelKey(oldKey)
                        if (ok) {
                            SuperkeyStore.clearLocal(context)
                            oldKey = ""
                        }
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
            }
        }
    }
}
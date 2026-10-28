package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            viewModel.refresh()
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        // 主题商城/编辑器是独立一屏：调色屏改的是"当前外观"，主题屏管的是"整套皮肤"。
        onOpenThemes = { navigator.push(Route.Themes) },
        onOpenWallpaper = { navigator.push(Route.Wallpaper) },
        onOpenProfileTemplate = { navigator.push(Route.AppProfileTemplate) },
        onOpenRemoteAssistant = { navigator.push(Route.RemoteAssistant) },
        onOpenTools = { navigator.push(Route.Tools) },
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetSulogEnabled = viewModel::setSulogEnabled,
        onSetAdbRootEnabled = viewModel::setAdbRootEnabled,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onSetAutoJailbreak = viewModel::setAutoJailbreak,
        onSetUseSoftReboot = viewModel::setUseSoftReboot,
        onSetCmdGuardEnabled = viewModel::setCmdGuardEnabled,
        onCmdGuardTrust = viewModel::setCmdGuardTrust,
        onOpenAbout = { navigator.push(Route.About) },
    )

    SettingPagerMiuix(uiState, actions, bottomInnerPadding)
}

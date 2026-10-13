package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.isMountState
import me.weishu.kernelsu.ui.viewmodel.MagiskSuperUserViewModel
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel

@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    // 挂载态（5.10 以下，方案甲）：设备上没有 KSU 驱动，KSU 的超级用户页在这条路线上只会空转
    // （Natives 的 JNI 全是空实现）。这条路线上的 root **就是** Magisk 的 root，所以直接显示
    // Magisk 那一份策略表（走 Magisk 官方 CLI）—— 本管理器能管 Magisk 的 root，Magisk 应用
    // 也管得到本管理器的 root，两边是同一张表。内核态一行都不变。
    if (isMountState) {
        MagiskSuperUserPager(bottomInnerPadding, isCurrentPage)
        return
    }

    val viewModel = viewModel<SuperUserViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.hasLoaded && !state.isRefreshing) {
                viewModel.initializePreferences()
                viewModel.loadAppList()
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.isRefreshing) {
                if (!state.hasLoaded) {
                    viewModel.initializePreferences()
                    viewModel.loadAppList()
                } else if (viewModel.isNeedRefresh) {
                    viewModel.loadAppList(resort = false)
                }
            }
        }
        initialResumeHandled.value = true
        onPauseOrDispose {}
    }

    val onSearchTextChange: (String) -> Unit = viewModel::updateSearchText
    val onToggleShowSystemApps: () -> Unit = {
        viewModel.toggleShowSystemApps()
    }
    val onToggleShowOnlyPrimaryUserApps: () -> Unit = {
        viewModel.toggleShowOnlyPrimaryUserApps()
    }
    val onOpenProfile: (GroupedApps) -> Unit = { group ->
        navigator.push(Route.AppProfile(group.uid))
        viewModel.markNeedRefresh()
    }
    val actions = SuperUserActions(
        onRefresh = { viewModel.loadAppList(force = true) },
        onOpenSulog = { navigator.push(Route.Sulog) },
        onSearchTextChange = onSearchTextChange,
        onSearchStatusChange = viewModel::updateSearchStatus,
        onClearSearch = { onSearchTextChange("") },
        onToggleShowSystemApps = onToggleShowSystemApps,
        onToggleShowOnlyPrimaryUserApps = onToggleShowOnlyPrimaryUserApps,
        onUpdateSortConfig = { viewModel.updateSortConfig(it) },
        onOpenProfile = onOpenProfile,
    )

    SuperUserPagerMiuix(
        uiState = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
    )
}

/** 挂载态（Magisk 路线）的超级用户页：数据源是 Magisk 的策略表，见 [MagiskSuperUserViewModel]。 */
@Composable
private fun MagiskSuperUserPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean,
) {
    val viewModel = viewModel<MagiskSuperUserViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 每次切回本页都重新读一次：Magisk 应用（以及它弹的授权框）随时可能改同一张表。
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.load()
    }

    val actions = MagiskSuperUserActions(
        onRefresh = { viewModel.load() },
        onSetPolicy = viewModel::setPolicy,
        onSetLogging = viewModel::setLogging,
        onSetNotification = viewModel::setNotification,
        onRevoke = viewModel::revoke,
        onToggleZygisk = viewModel::toggleZygisk,
        onToggleDenylist = viewModel::toggleDenylist,
    )

    MagiskSuperUserPagerMiuix(
        uiState = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
    )
}

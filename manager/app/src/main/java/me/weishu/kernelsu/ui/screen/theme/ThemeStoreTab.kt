package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.screen.theme.store.StoreActions
import me.weishu.kernelsu.ui.screen.theme.store.StoreViewModel
import me.weishu.kernelsu.ui.screen.theme.store.ThemeStoreScreen
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 商店 Tab 的接线层：**只做一件事** —— 把 [StoreViewModel] 的状态接到
 * [ThemeStoreScreen]，并把"发布主题"这个动作转给主题屏自己的 ViewModel。
 *
 * 商店自身的逻辑全在 `ui/screen/theme/store/` 与 `data/theme/Store*.kt` 里；
 * 这里保持 30 行以内，避免又长出一个上帝文件。
 */
@Composable
fun ThemeStoreTab(
    state: ThemeUiState,
    actions: ThemeActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    // 只用到外层的一个动作；其余状态自给自足，主题屏的 state/actions 不往商店里渗。
    val openPublish = actions.onOpenPublishPicker
    val storeViewModel: StoreViewModel = viewModel()
    val storeState by storeViewModel.state.collectAsStateWithLifecycle()
    val storeActions = remember(storeViewModel, openPublish) {
        StoreActions(
            open = storeViewModel::open,
            back = storeViewModel::back,
            refresh = storeViewModel::refresh,
            setSearchInput = storeViewModel::setSearchInput,
            submitSearch = storeViewModel::submitSearch,
            clearSearch = storeViewModel::clearSearch,
            clearHistory = storeViewModel::clearHistory,
            setSort = storeViewModel::setSort,
            setCategory = storeViewModel::setCategory,
            setElement = storeViewModel::setElement,
            setTag = storeViewModel::setTag,
            resetFilters = storeViewModel::resetFilters,
            setRankBoard = storeViewModel::setRankBoard,
            setLibraryTab = storeViewModel::setLibraryTab,
            download = storeViewModel::download,
            cancelDownload = storeViewModel::cancelDownload,
            retryDownload = storeViewModel::retryDownload,
            dismissDownload = storeViewModel::dismissDownload,
            clearFinishedDownloads = storeViewModel::clearFinishedDownloads,
            toggleFavorite = storeViewModel::toggleFavorite,
            applyTheme = storeViewModel::applyTheme,
            openPublish = openPublish,
            consumeNotice = storeViewModel::consumeNotice,
        )
    }
    ThemeStoreScreen(
        state = storeState,
        actions = storeActions,
        innerPadding = innerPadding,
        backdrop = backdrop,
    )
}

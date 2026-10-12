package me.weishu.kernelsu.ui.screen.modulerepo

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.RepoModule
import me.weishu.kernelsu.ui.component.SearchStatus

enum class RepoSort {
    UPDATED,
    CREATED,
    NAME,
    STARS,
}

data class ModuleRepoUiState(
    val isRefreshing: Boolean = false,
    val sortOrder: RepoSort = RepoSort.UPDATED,
    val offline: Boolean = false,
    val modules: List<RepoModule> = emptyList(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<RepoModule> = emptyList(),
    val error: Throwable? = null,
    /** 当前配置的仓库地址（主源）。 */
    val repoUrl: String = "",
    /** 最近一次实际生效的仓库地址；与 repoUrl 不同即表示已回退。 */
    val source: String? = null,
)

@Immutable
data class ModuleRepoActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onSetSortOrder: (RepoSort) -> Unit,
    val onOpenRepoDetail: (RepoModule) -> Unit,
    val onSaveRepoUrl: (String) -> Unit,
)

@Immutable
data class ModuleRepoDetailUiState(
    val module: RepoModuleArg,
    val readmeHtml: String?,
    val readmeLoaded: Boolean,
    val detailReleases: List<ReleaseArg>,
    val webUrl: String,
    val sourceUrl: String,
)

@Immutable
data class ModuleRepoDetailActions(
    val onBack: () -> Unit,
    val onOpenWebUrl: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onInstallModule: (android.net.Uri) -> Unit,
)

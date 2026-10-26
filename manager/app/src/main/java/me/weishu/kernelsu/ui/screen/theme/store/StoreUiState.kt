package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.theme.StoreCatalog
import me.weishu.kernelsu.data.theme.StoreDownloadState
import me.weishu.kernelsu.data.theme.StoreFilter
import me.weishu.kernelsu.data.theme.StoreQuery
import me.weishu.kernelsu.data.theme.StoreSort
import me.weishu.kernelsu.data.theme.StoreTheme

/**
 * 商店内部路由。
 *
 * 为什么不用 Navigation-Compose：主题屏本身已经在一个 `HorizontalPager` 的页面里，
 * 再套一层导航库会把返回键/HashMap 保存状态搞成两套体系。这里用**显式返回栈**
 * （[StoreState.backStack]）就够了，返回键和一层的"上一级"行为完全可控。
 *
 * 四个"根部"路由（[Home]/[Browse]/[Rank]/[Library]）对应底栏四个 Tab，切换时整栈替换；
 * [Search] 与 [Detail] 是压栈页，返回时弹栈。
 */
sealed interface StoreRoute {
    data object Home : StoreRoute
    data object Browse : StoreRoute
    data object Rank : StoreRoute
    data object Library : StoreRoute
    data object Search : StoreRoute
    data class Detail(val themeId: String) : StoreRoute

    val isRoot: Boolean
        get() = this is Home || this is Browse || this is Rank || this is Library
}

/** 我的页面里的两个 Tab。 */
enum class LibraryTab { DOWNLOADS, FAVORITES }

/**
 * 一次性提示的**类型**（文案在 UI 层翻译）。
 *
 * 数据层/VM 不产出人话文案：多语言、复数、标点都不该散落在业务逻辑里。
 */
sealed interface StoreNotice {
    data object Applied : StoreNotice
    data class ApplyFailed(val detail: String?) : StoreNotice
    data object Favorited : StoreNotice
    data object Unfavorited : StoreNotice
    data class DownloadQueued(val name: String) : StoreNotice
    data object AlreadyInstalled : StoreNotice
    data object HistoryCleared : StoreNotice
    data object DownloadFailed : StoreNotice
    data object SourceSaved : StoreNotice
    data object SourceReset : StoreNotice
}

/** 排行榜的三种榜（都基于索引真实字段，没有的字段不出榜）。 */
enum class RankBoard { HOT, LATEST, RATING }

/**
 * 商店界面状态（**只描述画什么**，逻辑全在 [StoreViewModel]）。
 */
@Immutable
data class StoreState(
    val backStack: List<StoreRoute> = listOf(StoreRoute.Home),
    val catalog: StoreCatalog = StoreCatalog.EMPTY,
    /** 首屏加载（没有任何内容可画）。 */
    val loading: Boolean = true,
    /** 有内容时的刷新（顶部转圈，不清空列表）。 */
    val refreshing: Boolean = false,
    /** 失败类型码（[me.weishu.kernelsu.data.theme.StoreFailure.code]）；null = 没失败。 */
    val errorCode: String? = null,
    /** 失败细节（HTTP 码、解析原因），只用于展开详情，不进主文案。 */
    val errorDetail: String? = null,
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
    val sourceLabel: String = "",
    val fellBack: Boolean = false,
    // ---- 搜索 ----
    val searchInput: String = "",
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    // ---- 浏览/排行 ----
    val sort: StoreSort = StoreSort.RECOMMENDED,
    val category: String = StoreFilter.CATEGORY_ALL,
    val element: String = "",
    val tag: String = "",
    val rankBoard: RankBoard = RankBoard.HOT,
    val libraryTab: LibraryTab = LibraryTab.DOWNLOADS,
    // ---- 本地状态 ----
    /** 最近收藏在前。 */
    val favorites: List<String> = emptyList(),
    val downloads: Map<String, StoreDownloadState> = emptyMap(),
    val currentThemeId: String? = null,
    val busy: Boolean = false,
    /** 数据源对话框是否打开。 */
    val sourceDialogOpen: Boolean = false,
    /** 当前生效的数据源地址（空 = 内置默认源）。 */
    val sourceUrl: String = "",
    /** 一次性提示（界面消费后调 [StoreActions.consumeNotice] 置空）。 */
    val notice: StoreNotice? = null,
) {
    val route: StoreRoute get() = backStack.lastOrNull() ?: StoreRoute.Home

    val canGoBack: Boolean get() = backStack.size > 1

    val isEmptyCatalog: Boolean get() = catalog.themes.isEmpty()

    val banners get() = catalog.resolvedBanners()

    val sections get() = catalog.filledSections()

    /** 推荐位：精选 → 下载量 → 更新时间（[StoreQuery] 的 RECOMMENDED 口径）。 */
    val recommended: List<StoreTheme>
        get() = StoreQuery.apply(catalog.themes, StoreSort.RECOMMENDED, StoreFilter.NONE)

    val browseItems: List<StoreTheme>
        get() = StoreQuery.apply(
            catalog.themes,
            sort,
            StoreFilter(category = category, element = element, tag = tag),
        )

    val searchResults: List<StoreTheme>
        get() = StoreQuery.search(catalog.themes, query)

    val hotWords: List<String>
        get() = catalog.tagCloud().take(HOT_WORD_LIMIT)

    fun rank(board: RankBoard): List<StoreTheme> = when (board) {
        RankBoard.HOT -> StoreQuery.apply(catalog.themes, StoreSort.HOT, StoreFilter.NONE)
        RankBoard.LATEST -> StoreQuery.apply(catalog.themes, StoreSort.LATEST, StoreFilter.NONE)
        RankBoard.RATING -> StoreQuery.apply(catalog.themes, StoreSort.RATING, StoreFilter.NONE)
            .filter { it.ratingCount > 0 || it.rating > 0f }
    }.take(RANK_LIMIT)

    /** 详情页要展示的主题（路由里只有 id，主题从索引查回来）。 */
    val detailTheme: StoreTheme?
        get() = (route as? StoreRoute.Detail)?.let { catalog.byId(it.themeId) }

    /** 相关推荐：同分类优先，其次标签重合，最后按下载量补足。 */
    fun related(theme: StoreTheme, limit: Int = 8): List<StoreTheme> {
        val others = catalog.themes.filter { it.themeId != theme.themeId }
        val sameCategory = others.filter { it.category == theme.category }
        val sameTag = others.filter { other -> other.tags.any { theme.tags.contains(it) } }
        val merged = LinkedHashMap<String, StoreTheme>()
        (sameTag + sameCategory + others).forEach { merged.putIfAbsent(it.themeId, it) }
        return merged.values.take(limit)
    }

    val favoriteThemes: List<StoreTheme>
        get() = favorites.mapNotNull { catalog.byId(it) }

    /** 下载管理：按队列里的状态过滤（成功的也在，方便"查看已装"）。 */
    val downloadEntries: List<Pair<StoreTheme, StoreDownloadState>>
        get() = downloads.entries
            .mapNotNull { entry -> catalog.byId(entry.key)?.let { it to entry.value } }
            .sortedWith(
                compareByDescending<Pair<StoreTheme, StoreDownloadState>> {
                    it.second is StoreDownloadState.Running || it.second is StoreDownloadState.Queued
                }.thenByDescending { it.first.name }
            )

    val activeDownloads: Int
        get() = downloads.values.count {
            it is StoreDownloadState.Running || it is StoreDownloadState.Queued
        }

    companion object {
        private const val HOT_WORD_LIMIT = 8
        private const val RANK_LIMIT = 30
    }
}

/**
 * 商店的动作集合（界面只发意图，不碰数据层）。
 */
@Immutable
data class StoreActions(
    val open: (StoreRoute) -> Unit,
    val back: () -> Unit,
    val refresh: () -> Unit,
    val setSearchInput: (String) -> Unit,
    val submitSearch: (String) -> Unit,
    val clearSearch: () -> Unit,
    val clearHistory: () -> Unit,
    val setSort: (StoreSort) -> Unit,
    val setCategory: (String) -> Unit,
    val setElement: (String) -> Unit,
    val setTag: (String) -> Unit,
    val resetFilters: () -> Unit,
    val setRankBoard: (RankBoard) -> Unit,
    val setLibraryTab: (LibraryTab) -> Unit,
    val download: (StoreTheme) -> Unit,
    val cancelDownload: (String) -> Unit,
    val retryDownload: (StoreTheme) -> Unit,
    val dismissDownload: (String) -> Unit,
    val clearFinishedDownloads: () -> Unit,
    val toggleFavorite: (String) -> Unit,
    val applyTheme: (String) -> Unit,
    val openPublish: () -> Unit,
    val openSourceDialog: () -> Unit,
    val dismissSourceDialog: () -> Unit,
    val saveSourceUrl: (String) -> Unit,
    val resetSourceUrl: () -> Unit,
    val consumeNotice: () -> Unit,
)

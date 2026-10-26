package me.weishu.kernelsu.ui.screen.theme.store

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.data.theme.StoreCatalogRepository
import me.weishu.kernelsu.data.theme.StoreDownloadManager
import me.weishu.kernelsu.data.theme.StoreDownloadState
import me.weishu.kernelsu.data.theme.StoreFailure
import me.weishu.kernelsu.data.theme.StoreFavorites
import me.weishu.kernelsu.data.theme.StoreFilter
import me.weishu.kernelsu.data.theme.StoreQuery
import me.weishu.kernelsu.data.theme.StoreSort
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.data.theme.ThemeApplier
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.data.theme.ThemeResolver
import me.weishu.kernelsu.data.theme.ThemeStoreSettings
import me.weishu.kernelsu.data.theme.withInstalled
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray

/**
 * 主题商店的逻辑层。
 *
 * 与 [me.weishu.kernelsu.ui.viewmodel.ThemeViewModel] 分开的理由：
 *  * 商店有自己的一整棵状态（路由栈 / 筛选 / 搜索历史 / 下载队列），塞进主题屏那个
 *    ViewModel 会变成第二个上帝对象；
 *  * 商店页要能"只刷新自己不惊动本地主题"，反过来也一样。
 *
 * 交叉点只有三个，靠仓库读解决：应用主题（[ThemeApplier]）、已安装标记（[ThemeRepository]）、
 * 发布入口（外层把动作透传进来）。
 */
class StoreViewModel : ViewModel() {

    private val context: Context get() = ksuApp

    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        observeDownloads()
        load(force = false)
        viewModelScope.launch { restoreLocalState() }
    }

    // ------------------------------------------------------------------ 加载

    /** 拉索引；[force] = 用户主动刷新（跳过缓存新鲜度，失败仍退回旧快照）。 */
    fun load(force: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            // 先给磁盘快照：商店 0ms 出内容，网络慢也不至于白屏。
            if (_state.value.isEmptyCatalog && !force) {
                val snapshot = withContext(Dispatchers.IO) {
                    runCatching { StoreCatalogRepository.cached(context) }.getOrNull()
                }
                if (snapshot != null) applySnapshot(snapshot)
            }
            _state.update { current ->
                if (current.isEmptyCatalog) {
                    current.copy(loading = true, errorCode = null, errorDetail = null)
                } else {
                    current.copy(refreshing = true, errorCode = null, errorDetail = null)
                }
            }
            val result = StoreCatalogRepository.load(context, force)
            result.fold(
                onSuccess = { snapshot -> applySnapshot(snapshot) },
                onFailure = { failure -> onLoadFailed(failure) },
            )
            refreshInstalledFlags()
        }
    }

    fun refresh() = load(force = true)

    private fun applySnapshot(snapshot: StoreCatalogRepository.Snapshot) {
        val primary = ThemeStoreSettings.primaryUrl
        _state.update { current ->
            current.copy(
                catalog = snapshot.catalog,
                loading = false,
                refreshing = false,
                errorCode = null,
                errorDetail = null,
                fromCache = snapshot.fromCache,
                fetchedAt = snapshot.fetchedAt,
                sourceLabel = ThemeStoreSettings.labelOf(snapshot.sourceUrl.ifEmpty { primary }),
                fellBack = snapshot.sourceUrl.isNotEmpty() && snapshot.sourceUrl != primary,
            )
        }
    }

    private fun onLoadFailed(failure: Throwable) {
        val typed = failure as? StoreFailure
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                errorCode = typed?.code ?: StoreFailure.CODE_UNKNOWN,
                errorDetail = when (typed) {
                    is StoreFailure.Unreachable -> "${typed.attempts} tries, last HTTP ${typed.lastCode}"
                    is StoreFailure.Parse -> typed.detail
                    is StoreFailure.Unknown -> typed.detail
                    // NoNetwork / Cancelled / Cache 这些码本身就是给人看的，不再叠一行原始 code。
                    else -> null
                },
            )
        }
    }

    /** 下载/应用之后把"已装版本"贴回索引（卡片徽标、详情按钮跟着变）。 */
    private suspend fun refreshInstalledFlags() {
        val installed = withContext(Dispatchers.IO) {
            runCatching { ThemeRepository.list(context).associate { it.spec.id to it.spec.versionCode } }
                .getOrDefault(emptyMap())
        }
        val active = withContext(Dispatchers.IO) {
            runCatching { ThemeRepository.activeId(context) }.getOrNull()
        }
        _state.update { current ->
            current.copy(catalog = current.catalog.withInstalled(installed), currentThemeId = active)
        }
    }

    private suspend fun restoreLocalState() {
        val favorites = withContext(Dispatchers.IO) { StoreFavorites.load(context) }
            .sortedByDescending { StoreFavorites.favoritedAt(it, context) }
        val history = withContext(Dispatchers.IO) { readHistory() }
        val active = withContext(Dispatchers.IO) {
            runCatching { ThemeRepository.activeId(context) }.getOrNull()
        }
        val localCount = withContext(Dispatchers.IO) {
            runCatching { ThemeRepository.list(context).size }.getOrDefault(0)
        }
        _state.update {
            it.copy(
                favorites = favorites,
                searchHistory = history,
                currentThemeId = active,
                localThemeCount = localCount,
                sourceUrl = ThemeStoreSettings.configuredUrl,
            )
        }
    }

    // ------------------------------------------------------------------ 路由

    fun open(route: StoreRoute) {
        _state.update { current ->
            when {
                route.isRoot -> current.copy(backStack = listOf(route))
                current.route == route -> current
                else -> current.copy(backStack = current.backStack + route)
            }
        }
    }

    fun back() {
        _state.update { current ->
            if (current.backStack.size > 1) {
                current.copy(backStack = current.backStack.dropLast(1))
            } else {
                current
            }
        }
    }

    // ------------------------------------------------------------------ 搜索

    fun setSearchInput(text: String) = _state.update { it.copy(searchInput = text) }

    fun submitSearch(text: String) {
        val keyword = text.trim()
        _state.update { current ->
            current.copy(
                searchInput = keyword,
                query = keyword,
                backStack = if (current.route is StoreRoute.Search) {
                    current.backStack
                } else {
                    current.backStack + StoreRoute.Search
                },
            )
        }
        if (keyword.isNotEmpty()) viewModelScope.launch { pushHistory(keyword) }
    }

    fun clearSearch() = _state.update { it.copy(query = "", searchInput = "") }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { writeHistory(emptyList()) }
            _state.update { it.copy(searchHistory = emptyList(), notice = StoreNotice.HistoryCleared) }
        }
    }

    private fun readHistory(): List<String> = runCatching {
        val raw = prefs().getString(KEY_HISTORY, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index, "").trim().takeIf { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(items: List<String>) {
        prefs().edit { putString(KEY_HISTORY, JSONArray(items).toString()) }
    }

    private suspend fun pushHistory(keyword: String) {
        val current = readHistory().toMutableList().apply { remove(keyword) }
        current.add(0, keyword)
        val trimmed = current.take(MAX_HISTORY)
        withContext(Dispatchers.IO) { writeHistory(trimmed) }
        _state.update { it.copy(searchHistory = trimmed) }
    }

    // ------------------------------------------------------------------ 浏览

    fun setSort(sort: StoreSort) = _state.update { it.copy(sort = sort) }

    fun setCategory(category: String) = _state.update { it.copy(category = category) }

    fun setElement(element: String) = _state.update { it.copy(element = element) }

    fun setTag(tag: String) = _state.update { it.copy(tag = tag) }

    fun resetFilters() = _state.update {
        it.copy(category = StoreFilter.CATEGORY_ALL, element = "", tag = "")
    }

    fun setRankBoard(board: RankBoard) = _state.update { it.copy(rankBoard = board) }

    fun setLibraryTab(tab: LibraryTab) = _state.update { it.copy(libraryTab = tab) }

    // ------------------------------------------------------------------ 下载

    private fun observeDownloads() {
        viewModelScope.launch {
            StoreDownloadManager.states.collect { downloads ->
                val previous = _state.value.downloads
                _state.update { it.copy(downloads = downloads) }
                // 有新装好的主题 → 重新贴"已安装"标记。
                val installedNow = downloads.entries.any { entry ->
                    entry.value is StoreDownloadState.Installed &&
                        previous[entry.key] !is StoreDownloadState.Installed
                }
                if (installedNow) refreshInstalledFlags()
            }
        }
    }

    fun download(theme: StoreTheme) {
        if (theme.tooOldFor(BuildConfig.VERSION_CODE)) {
            _state.update {
                it.copy(notice = StoreNotice.ApplyFailed("min-manager ${theme.minManagerVersion}"))
            }
            return
        }
        if (theme.installed && !theme.updatable) {
            _state.update { it.copy(notice = StoreNotice.AlreadyInstalled) }
            return
        }
        val queued = StoreDownloadManager.enqueue(context, theme)
        _state.update { current ->
            current.copy(
                notice = if (queued) StoreNotice.DownloadQueued(theme.name) else current.notice,
            )
        }
    }

    fun cancelDownload(themeId: String) = StoreDownloadManager.cancel(themeId)

    fun retryDownload(theme: StoreTheme) {
        StoreDownloadManager.retry(context, theme)
    }

    fun dismissDownload(themeId: String) = StoreDownloadManager.dismiss(themeId)

    fun clearFinishedDownloads() = StoreDownloadManager.clearFinished()

    // ------------------------------------------------------------------ 收藏 / 应用

    fun toggleFavorite(themeId: String) {
        if (themeId.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { StoreFavorites.toggle(context, themeId) }
            val favorites = withContext(Dispatchers.IO) { StoreFavorites.load(context) }
                .sortedByDescending { StoreFavorites.favoritedAt(it, context) }
            _state.update {
                it.copy(
                    favorites = favorites,
                    notice = if (added) StoreNotice.Favorited else StoreNotice.Unfavorited,
                )
            }
        }
    }

    fun applyTheme(themeId: String) {
        if (themeId.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = ThemeApplier.apply(context, themeId)
            ThemeResolver.invalidate()
            val active = withContext(Dispatchers.IO) {
                runCatching { ThemeRepository.activeId(context) }.getOrNull()
            }
            _state.update { current ->
                current.copy(
                    busy = false,
                    currentThemeId = active,
                    notice = if (result.isSuccess) {
                        StoreNotice.Applied
                    } else {
                        StoreNotice.ApplyFailed(result.exceptionOrNull()?.message)
                    },
                )
            }
            if (result.isSuccess) refreshInstalledFlags()
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ------------------------------------------------------------------ 数据源

    fun openSourceDialog() = _state.update { it.copy(sourceDialogOpen = true) }

    fun dismissSourceDialog() = _state.update { it.copy(sourceDialogOpen = false) }

    /**
     * 保存自定义数据源（空串 = 回到内置源）。
     *
     * 只接受 http(s)：索引解析走 OkHttp，`file://` / `content://` 之类既不被支持，
     * 也不该在这里被顺手放行。换源后必须**清掉磁盘快照**再强制联网，否则界面会拿旧源的
     * 缓存让人误以为"换了源还是那批主题"。
     */
    fun saveSourceUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() &&
            !trimmed.startsWith("http://") &&
            !trimmed.startsWith("https://")
        ) {
            return
        }
        ThemeStoreSettings.configuredUrl = trimmed
        viewModelScope.launch {
            withContext(Dispatchers.IO) { StoreCatalogRepository.clearCache(context) }
            _state.update { current ->
                current.copy(
                    sourceDialogOpen = false,
                    sourceUrl = trimmed,
                    catalog = me.weishu.kernelsu.data.theme.StoreCatalog.EMPTY,
                    loading = true,
                    fromCache = false,
                    fetchedAt = 0L,
                    notice = if (trimmed.isEmpty()) StoreNotice.SourceReset else StoreNotice.SourceSaved,
                )
            }
            load(force = true)
        }
    }

    fun resetSourceUrl() = saveSourceUrl("")

    /** 搜索建议（搜索页输入时可以用，当前只暴露给"热词"以防白算）。 */
    fun suggestions(): List<String> = StoreQuery.suggestions(
        _state.value.catalog.themes,
        _state.value.searchInput,
        limit = 6,
    ).map { it.name }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "settings"
        const val KEY_HISTORY = "theme_store_search_history"
        const val MAX_HISTORY = 10
    }
}

package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 搜索页：输入框 + 历史/热词 + 结果网格。
 *
 * 搜索是**本地打分**（[me.weishu.kernelsu.data.theme.StoreQuery.search]）：索引本来就有全量
 * 数据，为了一个搜索框去请求服务端既慢又依赖后端；本地搜索还能离线用。
 * 历史存在 SharedPreferences 里（最多 10 条，重复的提到最前）。
 */
@Composable
fun StoreSearchPage(state: StoreState, actions: StoreActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = state.searchInput,
                onValueChange = actions.setSearchInput,
                label = stringResource(R.string.themes_search_hint),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.store_search_action),
                onClick = { actions.submitSearch(state.searchInput) },
            )
        }

        when {
            state.query.isBlank() -> Column {
                if (state.searchHistory.isNotEmpty()) {
                    StoreSectionTitle(
                        title = stringResource(R.string.store_search_history),
                        actionLabel = stringResource(R.string.store_search_clear_history),
                        onAction = actions.clearHistory,
                    )
                    StoreChipsRow(
                        chips = state.searchHistory.map { StoreChip(it, it) },
                        selectedId = "",
                        onSelect = { keyword -> actions.submitSearch(keyword) },
                    )
                }
                if (state.hotWords.isNotEmpty()) {
                    StoreSectionTitle(title = stringResource(R.string.store_search_hot))
                    StoreChipsRow(
                        chips = state.hotWords.map { StoreChip(it, it) },
                        selectedId = "",
                        onSelect = { keyword -> actions.submitSearch(keyword) },
                    )
                }
                StoreEmptyState(
                    title = stringResource(R.string.store_search_empty_input),
                    subtitle = stringResource(R.string.store_search_empty_hint),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.searchResults.isEmpty() -> StoreEmptyState(
                title = stringResource(R.string.store_search_no_result, state.query),
                actionLabel = stringResource(R.string.store_search_clear),
                onAction = actions.clearSearch,
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                item(key = "search-count") {
                    Text(
                        text = stringResource(R.string.store_result_count, state.searchResults.size),
                        fontSize = 11.sp,
                        color = Xc.colors.textMuted,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                val rows = state.searchResults.chunked(2)
                itemsIndexed(
                    rows,
                    key = { _, row -> "search-${row.first().themeId}" },
                    contentType = { _, _ -> "grid-row" },
                ) { _, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { theme ->
                            StoreThemeCard(
                                theme = theme,
                                downloadState = state.downloads[theme.themeId],
                                isFavorite = state.favorites.contains(theme.themeId),
                                onOpen = { actions.open(StoreRoute.Detail(theme.themeId)) },
                                onAction = { quickAction(theme, state, actions) },
                                backdrop = null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

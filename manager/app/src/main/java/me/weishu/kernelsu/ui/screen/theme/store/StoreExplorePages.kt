package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import me.weishu.kernelsu.data.theme.Element
import me.weishu.kernelsu.data.theme.StoreFilter
import me.weishu.kernelsu.data.theme.StoreSort
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text

/**
 * 分类页：分类 chips + 元素 chips + 排序 chips + 双列网格。
 *
 * chips 的数据都来自索引本身：分类用 [me.weishu.kernelsu.data.theme.StoreCatalog.presentCategories]
 * （实际出现过的才列），元素用当前结果里出现过的，标签取 tagCloud —— 不硬编码死分类，
 * 运营加一个新分类不用改客户端。
 */
@Composable
fun StoreBrowsePage(state: StoreState, actions: StoreActions) {
    val categories = state.catalog.presentCategories()
    Column(modifier = Modifier.fillMaxSize()) {
        StoreChipsRow(
            chips = buildList {
                add(StoreChip(StoreFilter.CATEGORY_ALL, stringResource(R.string.store_filters_all)))
                categories.forEach { add(StoreChip(it, categoryLabel(it))) }
            },
            selectedId = state.category,
            onSelect = actions.setCategory,
        )
        StoreChipsRow(
            chips = buildList {
                add(StoreChip("", stringResource(R.string.store_elements_all)))
                Element.ALL.forEach { add(StoreChip(it, elementLabel(it))) }
            },
            selectedId = state.element,
            onSelect = actions.setElement,
        )
        StoreChipsRow(
            chips = buildList {
                add(StoreChip(StoreSort.RECOMMENDED.name, stringResource(R.string.store_sort_recommend)))
                add(StoreChip(StoreSort.HOT.name, stringResource(R.string.store_sort_hot)))
                add(StoreChip(StoreSort.LATEST.name, stringResource(R.string.store_sort_latest)))
                add(StoreChip(StoreSort.RATING.name, stringResource(R.string.store_sort_rating)))
            },
            selectedId = state.sort.name,
            onSelect = { id ->
                runCatching { StoreSort.valueOf(id) }.getOrNull()?.let(actions.setSort)
            },
        )
        val tags = state.catalog.tagCloud().take(TAG_LIMIT)
        if (tags.isNotEmpty()) {
            StoreChipsRow(
                chips = buildList {
                    add(StoreChip("", stringResource(R.string.store_tags_all)))
                    tags.forEach { add(StoreChip(it, it)) }
                },
                selectedId = state.tag,
                onSelect = actions.setTag,
            )
        }

        val items = state.browseItems
        Text(
            text = stringResource(R.string.store_result_count, items.size),
            fontSize = 11.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        )

        when {
            state.isEmptyCatalog && state.loading -> StoreSkeletonGrid(rows = 3)
            state.isEmptyCatalog && state.errorCode != null -> StoreErrorState(
                title = storeErrorTitle(state.errorCode),
                detail = state.errorDetail,
                actionLabel = stringResource(R.string.store_retry),
                onAction = actions.refresh,
                modifier = Modifier.weight(1f),
            )
            items.isEmpty() -> StoreEmptyState(
                title = stringResource(R.string.store_no_filter_result),
                actionLabel = stringResource(R.string.store_reset_filters),
                onAction = actions.resetFilters,
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                val rows = items.chunked(2)
                itemsIndexed(
                    rows,
                    key = { _, row -> "browse-${row.first().themeId}" },
                    contentType = { _, _ -> "grid-row" },
                ) { _, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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

/**
 * 排行页：三种榜（下载/新品/好评）。
 *
 * 「好评榜」在没有任何评分数据时是**空**的（不拿下载量冒充评分），
 * 空态直接说明"还没有评分数据"——这比画一个假榜诚实。
 */
@Composable
fun StoreRankPage(state: StoreState, actions: StoreActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        StoreChipsRow(
            chips = listOf(
                StoreChip(RankBoard.HOT.name, stringResource(R.string.store_rank_hot)),
                StoreChip(RankBoard.LATEST.name, stringResource(R.string.store_rank_latest)),
                StoreChip(RankBoard.RATING.name, stringResource(R.string.store_rank_rating)),
            ),
            selectedId = state.rankBoard.name,
            onSelect = { id ->
                runCatching { RankBoard.valueOf(id) }.getOrNull()?.let(actions.setRankBoard)
            },
        )
        val board = state.rank(state.rankBoard)
        when {
            state.isEmptyCatalog && state.loading -> StoreSkeletonGrid(rows = 3)
            board.isEmpty() -> StoreEmptyState(
                title = if (state.rankBoard == RankBoard.RATING) {
                    stringResource(R.string.store_rank_no_rating)
                } else {
                    stringResource(R.string.store_rank_empty)
                },
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                itemsIndexed(board, key = { _, theme -> "rank-${theme.themeId}" }) { index, theme ->
                    StoreRankRow(
                        rank = index + 1,
                        theme = theme,
                        onOpen = { actions.open(StoreRoute.Detail(theme.themeId)) },
                    )
                }
                item(key = "rank-footer") { StoreFooter(state = state, onRefresh = actions.refresh) }
            }
        }
    }
}

private const val TAG_LIMIT = 8

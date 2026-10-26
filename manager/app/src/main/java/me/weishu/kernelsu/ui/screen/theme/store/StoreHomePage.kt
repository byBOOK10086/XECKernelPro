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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.StoreDownloadState
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 推荐页（商店首页）。
 *
 * 结构照小米主题商店的推荐页排：金刚区 → 精选轮播 → 运营分区（横排货架）→
 * 双列网格。**所有分区都来自索引**：索引没写 sections 就不硬造，
 * 只保留"为你推荐"这一块真实排序的结果，页脚再把数据源/上次更新时间摊开。
 */
@Composable
fun StoreHomePage(
    state: StoreState,
    actions: StoreActions,
    backdrop: LayerBackdrop?,
) {
    when {
        state.isEmptyCatalog && state.loading -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            ) {
                item { StoreSkeletonBlock(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(172.dp)) }
                item { Spacer(Modifier.height(10.dp)) }
                item { StoreSkeletonGrid(rows = 2) }
            }
        }

        state.isEmptyCatalog && state.errorCode != null -> StoreErrorState(
            title = storeErrorTitle(state.errorCode),
            detail = state.errorDetail,
            actionLabel = stringResource(R.string.store_retry),
            onAction = actions.refresh,
            modifier = Modifier.fillMaxSize(),
        )

        state.isEmptyCatalog -> StoreEmptyState(
            title = stringResource(R.string.store_empty_catalog),
            subtitle = stringResource(R.string.store_empty_catalog_hint),
            actionLabel = stringResource(R.string.store_retry),
            onAction = actions.refresh,
            modifier = Modifier.fillMaxSize(),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 14.dp),
        ) {
            item(key = "shortcuts", contentType = "shortcuts") {
                StoreShortcutRow(
                    entries = listOf(
                        StoreShortcut(
                            label = stringResource(R.string.store_tab_rank),
                            route = StoreRoute.Rank,
                            icon = Icons.Rounded.Star,
                        ),
                        StoreShortcut(
                            label = stringResource(R.string.store_tab_browse),
                            route = StoreRoute.Browse,
                            icon = Icons.Rounded.Palette,
                        ),
                        StoreShortcut(
                            label = stringResource(R.string.store_tab_mine),
                            route = StoreRoute.Library,
                            icon = Icons.Rounded.GetApp,
                        ),
                    ),
                    onOpen = actions.open,
                )
            }

            val banners = state.banners
            if (banners.isNotEmpty()) {
                item(key = "banners", contentType = "banners") {
                    StoreBannerCarousel(
                        banners = banners,
                        onOpenTheme = { id -> actions.open(StoreRoute.Detail(id)) },
                    )
                }
            }

            val sections = state.sections
            sections.forEach { (section, themes) ->
                item(key = "section-${section.id}", contentType = "section-header") {
                    StoreSectionTitle(
                        title = section.title,
                        actionLabel = stringResource(R.string.store_view_all),
                        onAction = { actions.open(StoreRoute.Browse) },
                    )
                }
                item(key = "section-shelf-${section.id}", contentType = "section-shelf") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    ) {
                        itemsIndexed(themes, key = { _, theme -> theme.themeId }) { _, theme ->
                            StoreShelfCard(
                                theme = theme,
                                onOpen = { actions.open(StoreRoute.Detail(theme.themeId)) },
                            )
                        }
                    }
                }
            }

            item(key = "recommend-header", contentType = "header") {
                StoreSectionTitle(title = stringResource(R.string.store_section_recommend))
            }
            val recommended = state.recommended
            val rows = recommended.chunked(2)
            itemsIndexed(
                rows,
                key = { _, row -> "recommend-${row.first().themeId}" },
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
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item(key = "footer", contentType = "footer") {
                StoreFooter(state = state, onRefresh = actions.refresh)
            }
        }
    }
}

/** 页脚：数据源、上次更新时间、缓存提示 —— 商店的"透明度"放这里。 */
@Composable
internal fun StoreFooter(state: StoreState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val source = state.sourceLabel
        if (source.isNotEmpty()) {
            Text(
                text = if (state.fellBack) {
                    stringResource(
                        R.string.themes_source_fallback,
                        me.weishu.kernelsu.data.theme.ThemeStoreSettings.labelOf(
                            me.weishu.kernelsu.data.theme.ThemeStoreSettings.primaryUrl
                        ),
                        source,
                    )
                } else {
                    stringResource(R.string.themes_source, source)
                },
                fontSize = 11.sp,
                color = Xc.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val stamp = formatEpoch(state.fetchedAt)
        if (stamp.isNotEmpty()) {
            Text(
                text = stringResource(R.string.store_updated_at, stamp),
                fontSize = 11.sp,
                color = Xc.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        if (state.fromCache) {
            Text(
                text = stringResource(R.string.themes_offline),
                fontSize = 11.sp,
                color = Xc.colors.warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            StoreActionButton(
                label = stringResource(R.string.themes_refresh),
                primary = false,
                enabled = !state.loading && !state.refreshing,
                onClick = onRefresh,
            )
        }
    }
}

/**
 * 卡片右下角的快捷动作：已装 → 应用；否则 → 下载。
 *
 * 单独抽出来是因为首页网格、分类网格、收藏页三处都要用同一套判断，
 * 复制三份迟早会出现"某处点了没反应"。
 */
internal fun quickAction(theme: StoreTheme, state: StoreState, actions: StoreActions) {
    val current = state.downloads[theme.themeId]
    when {
        current is StoreDownloadState.Running || current is StoreDownloadState.Queued ->
            actions.cancelDownload(theme.themeId)

        theme.installed && !theme.updatable -> actions.applyTheme(theme.themeId)
        else -> actions.download(theme)
    }
}

package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeStoreSettings
import me.weishu.kernelsu.ui.component.RemotePreview
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 主题商城 Tab —— 小米主题商店式布局：
 * 自动轮播的精选横幅（页点指示）→ 分类 chips → 「最新上架」横排货架 →
 * 双列竖版卡网格（图上文字下），点卡片弹详情玻璃对话框。
 *
 * 数据诚实原则：索引里没有下载量/评分就不画假的排行 —— 「最新上架」用
 * updatedAt 真排序，排行位永远展示真实数据。
 */
@Composable
fun ThemeStoreTab(
    state: ThemeUiState,
    actions: ThemeActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    val layoutDirection = LocalLayoutDirection.current
    val store = state.filteredStore
    // 先取出局部 val：when 分支里才能安全地做非空判断（不依赖跨模块属性的智能转换）。
    val storeError = state.storeError
    // 分类是本组件的本地状态（搜索在 ViewModel，分类在这里过滤就够）。
    var category by rememberSaveable { mutableStateOf("all") }
    val visible = remember(store, category) {
        if (category == "all") store else store.filter { it.category == category }
    }
    val featured = remember(store) {
        store.filter { it.featured && (it.previews.isNotEmpty() || it.previewUrl.isNotEmpty()) }
    }
    val latest = remember(store) {
        store.sortedByDescending { it.updatedAt }.take(4)
    }
    // 详情对话框：存 themeId（rememberSaveable 可保存），用的时候再查回对象。
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailTheme = detailId?.let { id -> store.firstOrNull { it.themeId == id } }

    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
        ) {
            StoreHeader(
                query = state.query,
                sourceLabel = state.sourceLabel,
                fellBack = state.fellBack,
                loading = state.storeLoading,
                onSearch = actions.onSearch,
                onRefresh = actions.onRefreshStore,
            )

            // 上传入口放在列表上方、空态与失败态都看得见的地方：
            // 以前"发布"只藏在「我的主题」的卡片按钮里，用户找不到（上报的原话就是
            // "主题商店无法上传"）。入口必须长在商城里，而不是在自己的主题里。
            PublishEntryCard(
                themeCount = state.mine.size,
                enabled = !state.busy,
                onClick = actions.onOpenPublishPicker,
                backdrop = backdrop,
            )

            when {
                storeError != null -> StoreHint(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    text = stringResource(R.string.themes_load_failed, storeError),
                    onRetry = actions.onRefreshStore,
                )
                // 老数据还在时刷新不换成转圈：列表先清空再长回来，比转圈更像"出错了"。
                store.isEmpty() && state.storeLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator()
                }
                store.isEmpty() -> StoreHint(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    text = stringResource(R.string.themes_empty),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .scrollEndHaptic()
                        .overScrollVertical(),
                    contentPadding = PaddingValues(
                        top = 6.dp,
                        bottom = innerPadding.calculateBottomPadding() + 12.dp,
                    ),
                    overscrollEffect = null,
                ) {
                    // 精选轮播（索引 v2 的 featured + 预览图）：小米主题商店的门面位。
                    if (featured.isNotEmpty()) {
                        item(key = "featured", contentType = "featured") {
                            FeaturedBanner(
                                themes = featured,
                                onOpen = { detailId = it },
                            )
                        }
                    }
                    item(key = "categories", contentType = "categories") {
                        CategoryChips(
                            selected = category,
                            onSelect = { category = it },
                        )
                    }
                    if (category == "all" && latest.size > 1) {
                        item(key = "latest_header", contentType = "header") {
                            SectionHeader(stringResource(R.string.themes_section_latest))
                        }
                        item(key = "latest_shelf", contentType = "shelf") {
                            LatestShelf(
                                themes = latest,
                                onOpen = { detailId = it },
                            )
                        }
                    }
                    item(key = "all_header", contentType = "header") {
                        SectionHeader(
                            if (category == "all") stringResource(R.string.themes_section_all)
                            else stringResource(R.string.themes_category_all),
                        )
                    }
                    val rows = visible.chunked(2)
                    itemsIndexed(rows, key = { _, row -> row.first().themeId }, contentType = { _, _ -> "theme_row" }) { _, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StoreThemeCard(
                                theme = row[0],
                                state = state,
                                actions = actions,
                                onOpen = { detailId = row[0].themeId },
                                backdrop = backdrop,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.size > 1) {
                                StoreThemeCard(
                                    theme = row[1],
                                    state = state,
                                    actions = actions,
                                    onOpen = { detailId = row[1].themeId },
                                    backdrop = backdrop,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        detailTheme?.let { theme ->
            ThemeDetailDialog(
                theme = theme,
                state = state,
                actions = actions,
                onDismiss = { detailId = null },
            )
        }
    }
}

/** 搜索框 + 刷新按钮 + 一行"现在用的是哪个源"。 */
@Composable
private fun StoreHeader(
    query: String,
    sourceLabel: String,
    fellBack: Boolean,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onSearch,
            label = stringResource(R.string.themes_search_hint),
            modifier = Modifier.weight(1f),
        )
        // 刷新中不再响应点击：再点一次只是把同一个请求排队，界面会看起来"卡住"。
        IconButton(onClick = { if (!loading) onRefresh() }) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                tint = if (loading) Xc.colors.textMuted else Xc.colors.textSecondary,
                contentDescription = stringResource(R.string.themes_refresh),
            )
        }
    }
    if (sourceLabel.isNotEmpty()) {
        Text(
            text = if (fellBack) {
                // 回退必须说清"谁不可达、现在用的是谁"：主源的标签 state 里没有，
                // 从 ThemeStoreSettings 取（与 ViewModel 同一个来源，不是另起一份配置）。
                stringResource(
                    R.string.themes_source_fallback,
                    ThemeStoreSettings.labelOf(ThemeStoreSettings.primaryUrl),
                    sourceLabel,
                )
            } else {
                stringResource(R.string.themes_source, sourceLabel)
            },
            fontSize = 12.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp),
        )
    }
}

/**
 * 商城顶部的「发布我的主题」入口。
 *
 * 存在的理由就是上报的那句话："主题商店无法上传" —— 发布按钮原先只在
 * 「我的主题」每张卡片里，商城里没有任何入口。入口长在商城里才符合直觉：
 * 用户看到商城 → 想让自己的主题也出现在这里 → 这里就该有一个按钮。
 */
@Composable
private fun PublishEntryCard(
    themeCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    backdrop: LayerBackdrop?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Upload,
                tint = Xc.colors.accent,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.themes_publish_entry_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                )
                Text(
                    text = if (themeCount > 0) {
                        stringResource(R.string.themes_publish_entry_summary, themeCount)
                    } else {
                        stringResource(R.string.themes_publish_entry_empty)
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                tint = Xc.colors.textMuted,
                contentDescription = null,
            )
        }
    }
}

/** 空态与失败态：居中一句话；失败时多给一个"刷新"当重试。 */
@Composable
private fun StoreHint(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Xc.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(
                    text = stringResource(R.string.themes_refresh),
                    onClick = onRetry,
                )
            }
        }
    }
}

/** 小节标题：左侧粗体、右侧留白（小米主题商店的分区样式）。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = Xc.colors.text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 精选横幅：自动轮播的大图卡（4s 一换、手动滑动后从当前页继续计时），
 * 底部中央页点，名字/作者/精选徽章压在渐变纱上。点开进详情。
 */
@Composable
private fun FeaturedBanner(
    themes: List<StoreTheme>,
    onOpen: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { themes.size })
    LaunchedEffect(pagerState, themes.size) {
        while (themes.size > 1) {
            delay(4000)
            if (!pagerState.isScrollInProgress && pagerState.pageCount > 0) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }
    Column {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 10.dp,
        ) { page ->
            val theme = themes[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(Xc.shapes.lg)
                    .clickable { onOpen(theme.themeId) },
            ) {
                RemotePreview(
                    url = theme.previews.firstOrNull() ?: theme.previewUrl,
                    contentDescription = theme.name,
                    modifier = Modifier.matchParentSize(),
                    placeholder = Xc.colors.surfaceMuted,
                )
                // 底部渐变纱：保证白字在任何预览图上可读。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.35f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            )
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = theme.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.themes_featured),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(Xc.shapes.pill)
                                .background(Xc.colors.accent)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (theme.author.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.themes_by, theme.author),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // 页点：当前页放大拉长成胶囊（小米横幅的指示样式）。
        if (themes.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(themes.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(if (active) 16.dp else 6.dp)
                            .height(6.dp)
                            .clip(Xc.shapes.pill)
                            .background(if (active) Xc.colors.accent else Xc.colors.surfaceMuted),
                    )
                }
            }
        }
    }
}

/** 「最新上架」横排货架：紧凑竖版卡（小米的横向专题架）。 */
@Composable
private fun LatestShelf(
    themes: List<StoreTheme>,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    ) {
        itemsIndexed(themes, key = { _, t -> t.themeId }) { _, theme ->
            Column(
                modifier = Modifier
                    .width(148.dp)
                    .clickable { onOpen(theme.themeId) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(Xc.shapes.md),
                ) {
                    RemotePreview(
                        url = theme.previews.firstOrNull() ?: theme.previewUrl,
                        contentDescription = theme.name,
                        modifier = Modifier.matchParentSize(),
                        placeholder = Xc.colors.surfaceMuted,
                    )
                    if (theme.installed) {
                        Text(
                            text = stringResource(R.string.themes_installed),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Xc.colors.onSuccessTint,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(Xc.shapes.pill)
                                .background(Xc.colors.successTint)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = theme.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = if (theme.author.isNotEmpty()) {
                        stringResource(R.string.themes_by, theme.author)
                    } else {
                        theme.updatedAt.substringBefore("T")
                    },
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 网格里的竖版主题卡（图上、文字下）：小米商店的标准卡片形态。
 * 下载中把底部行换成进度；点卡片本体进详情，按钮仍然直接可用。
 */
@Composable
private fun StoreThemeCard(
    theme: StoreTheme,
    state: ThemeUiState,
    actions: ThemeActions,
    onOpen: () -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val downloading = state.downloadingId == theme.themeId
    val active = state.activeId == theme.themeId

    Card(
        modifier = modifier
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md)
            .clickable(onClick = onOpen),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                RemotePreview(
                    url = theme.previews.firstOrNull() ?: theme.previewUrl,
                    contentDescription = theme.name,
                    modifier = Modifier.matchParentSize(),
                    placeholder = Xc.colors.surfaceMuted,
                )
                if (theme.updatable) {
                    Text(
                        text = stringResource(R.string.themes_updatable),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Xc.colors.onWarningTint,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(Xc.shapes.pill)
                            .background(Xc.colors.warningTint)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                } else if (theme.installed) {
                    Text(
                        text = stringResource(R.string.themes_installed),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Xc.colors.onSuccessTint,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(Xc.shapes.pill)
                            .background(Xc.colors.successTint)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = theme.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (theme.author.isNotEmpty()) {
                            stringResource(R.string.themes_by, theme.author)
                        } else {
                            "v" + theme.version
                        },
                        fontSize = 11.sp,
                        color = Xc.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (theme.size > 0L) {
                        Text(
                            text = stringResource(R.string.themes_size, (theme.size / 1024L).toString()),
                            fontSize = 10.sp,
                            color = Xc.colors.textMuted,
                        )
                    }
                }
                if (downloading) {
                    Text(
                        text = stringResource(R.string.themes_downloading, state.downloadProgress),
                        fontSize = 10.sp,
                        color = Xc.colors.textSecondary,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    ThemeProgressBar(progress = state.downloadProgress)
                } else if (!theme.installed || theme.updatable) {
                    TextButton(
                        text = stringResource(R.string.themes_download),
                        onClick = { actions.onDownload(theme) },
                        enabled = !state.busy,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                    )
                } else if (!active) {
                    TextButton(
                        text = stringResource(R.string.themes_apply),
                        onClick = { actions.onApply(theme.themeId) },
                        enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 详情玻璃对话框：大图轮播 + 元信息 + 操作行（小米主题商店的详情页浓缩）。 */
@Composable
private fun ThemeDetailDialog(
    theme: StoreTheme,
    state: ThemeUiState,
    actions: ThemeActions,
    onDismiss: () -> Unit,
) {
    val pages = remember(theme.themeId) {
        if (theme.previews.isNotEmpty()) theme.previews else listOfNotNull(theme.previewUrl.takeIf { it.isNotEmpty() })
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val downloading = state.downloadingId == theme.themeId
    val active = state.activeId == theme.themeId
    val swatches = remember(theme.accentHex, theme.backdropDarkHex, theme.backdropLightHex) {
        listOf(theme.accentHex, theme.backdropDarkHex, theme.backdropLightHex)
            .mapNotNull { ThemeCodec.parseColor(it) }
            .map { Color(it) }
    }

    XDialog(show = true, onDismissRequest = onDismiss, maxWidth = 460.dp) {
        if (pages.size > 1) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 8.dp,
            ) { page ->
                RemotePreview(
                    url = pages[page],
                    contentDescription = theme.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(Xc.shapes.md),
                    placeholder = Xc.colors.surfaceMuted,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pages.size) { i ->
                    val activePage = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(if (activePage) 14.dp else 5.dp)
                            .height(5.dp)
                            .clip(Xc.shapes.pill)
                            .background(if (activePage) Xc.colors.accent else Xc.colors.surfaceMuted),
                    )
                }
            }
        } else if (pages.size == 1) {
            RemotePreview(
                url = pages[0],
                contentDescription = theme.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(Xc.shapes.md),
                placeholder = Xc.colors.surfaceMuted,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = theme.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (theme.featured) {
                Text(
                    text = stringResource(R.string.themes_featured),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(Xc.shapes.pill)
                        .background(Xc.colors.accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (theme.installed) {
                ThemeTag(
                    text = stringResource(R.string.themes_installed),
                    containerColor = Xc.colors.successTint,
                    contentColor = Xc.colors.onSuccessTint,
                )
            }
            if (theme.updatable) {
                ThemeTag(
                    text = stringResource(R.string.themes_updatable),
                    containerColor = Xc.colors.warningTint,
                    contentColor = Xc.colors.onWarningTint,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (theme.author.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.themes_by, theme.author),
                    fontSize = 12.sp,
                    color = Xc.colors.textMuted,
                )
            }
            if (theme.size > 0L) {
                Text(
                    text = stringResource(R.string.themes_size, (theme.size / 1024L).toString()),
                    fontSize = 12.sp,
                    color = Xc.colors.textMuted,
                )
            }
            if (theme.updatedAt.isNotEmpty()) {
                Text(
                    text = theme.updatedAt.substringBefore("T"),
                    fontSize = 12.sp,
                    color = Xc.colors.textMuted,
                )
            }
        }

        if (swatches.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemeSwatches(swatches)
            }
        }

        if (theme.summary.isNotEmpty() || theme.description.isNotEmpty()) {
            Text(
                text = if (theme.description.isNotEmpty()) theme.description else theme.summary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Xc.colors.textSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            thickness = 0.5.dp,
            color = Xc.colors.glassRim.copy(alpha = 0.5f),
        )

        if (downloading) {
            Text(
                text = stringResource(R.string.themes_downloading, state.downloadProgress),
                fontSize = 12.sp,
                color = Xc.colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            ThemeProgressBar(progress = state.downloadProgress)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!theme.installed || theme.updatable) {
                    TextButton(
                        text = stringResource(R.string.themes_download),
                        onClick = { actions.onDownload(theme) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (theme.installed) {
                    if (active) {
                        TextButton(
                            text = stringResource(R.string.themes_installed),
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.themes_apply),
                            onClick = { actions.onApply(theme.themeId) },
                            enabled = !state.busy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 分类 chips：全部 / 配色 / 模式牌。 */
@Composable
private fun CategoryChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        itemsIndexed(
            listOf(
                Triple("all", R.string.themes_category_all, false),
                Triple(StoreTheme.TYPE_COLORS, R.string.themes_category_colors, false),
                Triple(StoreTheme.TYPE_MODE_BADGE, R.string.themes_category_mode_badge, true),
            ),
            key = { _, it -> it.first },
        ) { _, item ->
            val (id, labelRes, _) = item
            val isSelected = selected == id
            Row(
                modifier = Modifier
                    .clip(Xc.shapes.pill)
                    .background(
                        if (isSelected) Xc.colors.accentSoft else Xc.colors.surfaceMuted
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(labelRes),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) Xc.colors.accent else Xc.colors.textSecondary,
                )
            }
        }
    }
}

/** 小药丸：版本号与"已安装 / 可更新"。 */
@Composable
private fun ThemeTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
        modifier = Modifier
            .clip(Xc.shapes.xs)
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 主题自带的三色预览：强调色 / 深色背景 / 浅色背景，解析不出来的不占位。 */
@Composable
private fun ThemeSwatches(colors: List<Color>) {
    if (colors.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(Xc.shapes.xs)
                    .background(color)
                    // 描边是必需的：主题可能给纯白或纯黑色块，没有边就与卡片糊在一起。
                    .border(width = 0.5.dp, color = Xc.colors.glassRim, shape = Xc.shapes.xs),
            )
        }
    }
}

/**
 * 下载进度条。
 *
 * 没用 miuix 的进度组件：仓库里目前只有 `InfiniteProgressIndicator`（转圈、表达不了百分比），
 * 自绘一个按比例填充的 Box 更省事，也和后端给的 0..100 一一对应。
 */
@Composable
private fun ThemeProgressBar(progress: Int) {
    val fraction = progress.coerceIn(0, 100) / 100f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(Xc.shapes.pill)
            .background(Xc.colors.surfaceMuted),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(Xc.shapes.pill)
                .background(Xc.colors.accent),
        )
    }
}

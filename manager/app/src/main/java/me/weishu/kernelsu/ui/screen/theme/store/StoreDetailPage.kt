package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.StoreDownloadState
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.ui.component.RemotePreview
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 详情页（**整页**，不是弹窗）：大图横滑 → 标题/作者/角标 → 元信息 → 介绍/更新说明 →
 * 相关推荐，底部固定操作栏（收藏 + 下载/应用/取消）。
 *
 * 为什么从弹窗改成整页：详情里要放多图预览、标签、相关推荐和两段长文本，
 * 弹窗在手机上会被键盘/手势区挤成一条缝；小米主题商店的详情也是整页沉浸式的。
 */
@Composable
fun StoreDetailPage(state: StoreState, actions: StoreActions, themeId: String) {
    val theme = state.catalog.byId(themeId)
    if (theme == null) {
        StoreEmptyState(
            title = stringResource(R.string.store_detail_missing),
            actionLabel = stringResource(R.string.store_retry),
            onAction = actions.refresh,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    var expanded by rememberSaveable(theme.themeId) { mutableStateOf(false) }
    var previewAt by remember(theme.themeId) { mutableStateOf<Int?>(null) }
    val images = remember(theme.themeId) { theme.allImages }
    val download = state.downloads[theme.themeId]
    val isFavorite = state.favorites.contains(theme.themeId)
    val isCurrent = state.currentThemeId == theme.themeId

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 14.dp),
        ) {
            item(key = "hero") {
                DetailHero(
                    theme = theme,
                    images = images,
                    onOpenPreview = { index -> previewAt = index },
                )
            }
            item(key = "head") { DetailHeader(theme = theme) }
            if (theme.tags.isNotEmpty()) {
                item(key = "tags") {
                    StoreChipsRow(
                        chips = listOf(StoreChip("", stringResource(R.string.store_tags_all))) +
                            theme.tags.map { StoreChip(it, it) },
                        selectedId = "",
                        onSelect = { tag -> actions.setTag(tag) },
                    )
                }
            }
            item(key = "meta") { DetailMeta(theme = theme) }
            if (theme.summary.isNotEmpty() || theme.description.isNotEmpty()) {
                item(key = "about") {
                    DetailText(
                        title = stringResource(R.string.store_detail_about),
                        body = theme.description.ifEmpty { theme.summary },
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    )
                }
            }
            if (theme.changelog.isNotEmpty()) {
                item(key = "changelog") {
                    DetailText(
                        title = stringResource(R.string.store_detail_changelog),
                        body = theme.changelog,
                        expanded = true,
                        onToggle = null,
                    )
                }
            }
            val related = state.related(theme)
            if (related.isNotEmpty()) {
                item(key = "related-title") {
                    StoreSectionTitle(title = stringResource(R.string.store_detail_related))
                }
                item(key = "related-shelf") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(related, key = { _, item -> "rel-${item.themeId}" }) { _, item ->
                            StoreShelfCard(
                                theme = item,
                                onOpen = { actions.open(StoreRoute.Detail(item.themeId)) },
                            )
                        }
                    }
                }
            }
            item(key = "meta-footer") {
                Text(
                    text = stringResource(R.string.store_detail_checksum, theme.sha256.take(16)),
                    fontSize = 10.sp,
                    color = Xc.colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        DetailActionBar(
            theme = theme,
            download = download,
            isFavorite = isFavorite,
            isCurrent = isCurrent,
            busy = state.busy,
            actions = actions,
        )
    }

    val index = previewAt
    if (index != null && images.isNotEmpty()) {
        XDialog(show = true, onDismissRequest = { previewAt = null }, maxWidth = 560.dp) {
            val pagerState = rememberPagerState(initialPage = index.coerceIn(0, images.lastIndex)) {
                images.size
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                RemotePreview(
                    url = images[page],
                    contentDescription = theme.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(Xc.shapes.md),
                    placeholder = Xc.colors.surfaceMuted,
                )
            }
            Text(
                text = stringResource(
                    R.string.store_preview_position,
                    pagerState.currentPage + 1,
                    images.size,
                ),
                fontSize = 11.sp,
                color = Xc.colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/** 顶部大图：多图横滑 + 页点，点一下进全屏预览。 */
@Composable
private fun DetailHero(
    theme: StoreTheme,
    images: List<String>,
    onOpenPreview: (Int) -> Unit,
) {
    if (images.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Xc.colors.surfaceMuted),
        )
        return
    }
    val pagerState = rememberPagerState(pageCount = { images.size })
    Column {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
        ) { page ->
            RemotePreview(
                url = images[page],
                contentDescription = theme.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(Xc.shapes.lg)
                    .clickable { onOpenPreview(page) },
                placeholder = Xc.colors.surfaceMuted,
            )
        }
        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(images.size) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(if (active) 14.dp else 5.dp)
                            .height(5.dp)
                            .clip(Xc.shapes.pill)
                            .background(if (active) Xc.colors.accent else Xc.colors.surfaceMuted),
                    )
                }
            }
        }
    }
}

/** 标题 + 角标 + 作者 + 时间。 */
@Composable
private fun DetailHeader(theme: StoreTheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = theme.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                theme.updatable -> StoreBadge(
                    text = stringResource(R.string.themes_updatable),
                    background = Xc.colors.warningTint,
                    contentColor = Xc.colors.onWarningTint,
                )

                theme.installed -> StoreBadge(
                    text = stringResource(R.string.themes_installed),
                    background = Xc.colors.successTint,
                    contentColor = Xc.colors.onSuccessTint,
                )

                theme.isFree -> StoreBadge(
                    text = stringResource(R.string.store_badge_free),
                    background = Xc.colors.successTint,
                    contentColor = Xc.colors.onSuccessTint,
                )

                else -> StoreBadge(
                    text = priceText(theme.priceCents),
                    background = Xc.colors.accent,
                    contentColor = Xc.colors.onSemanticSolid,
                )
            }
        }
        val authorText = if (theme.author.isNotEmpty()) {
            stringResource(R.string.themes_by, theme.author)
        } else {
            ""
        }
        val dateText = if (theme.updatedAt.isNotEmpty()) formatDate(theme.updatedAt) else ""
        val subtitle = listOf(authorText, dateText).filter { it.isNotEmpty() }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Xc.colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (theme.downloads > 0 || theme.ratingCount > 0) {
            val downloadText = if (theme.downloads > 0) {
                stringResource(R.string.store_downloads_count, formatCount(theme.downloads))
            } else {
                ""
            }
            val ratingText = if (theme.rating > 0f) {
                stringResource(
                    R.string.store_rating_value,
                    String.format(java.util.Locale.US, "%.1f", theme.rating),
                )
            } else {
                ""
            }
            Text(
                text = listOf(downloadText, ratingText).filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 12.sp,
                color = Xc.colors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 元信息行：版本 / 大小 / 更新时间 / 包含元素。 */
@Composable
private fun DetailMeta(theme: StoreTheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(Xc.shapes.md)
            .background(Xc.colors.surfaceMuted)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (theme.version.isNotEmpty()) {
            StoreMetaRow(
                label = stringResource(R.string.store_detail_version),
                value = theme.version,
            )
        }
        val size = formatBytes(theme.size)
        if (size.isNotEmpty()) {
            StoreMetaRow(label = stringResource(R.string.store_detail_size), value = size)
        }
        if (theme.updatedAt.isNotEmpty()) {
            StoreMetaRow(
                label = stringResource(R.string.store_detail_updated),
                value = formatDate(theme.updatedAt),
            )
        }
        val elements = theme.effectiveElements.map { elementLabel(it) }
        if (elements.isNotEmpty()) {
            StoreMetaRow(
                label = stringResource(R.string.store_detail_elements),
                value = elements.joinToString(" · "),
            )
        }
        if (theme.minManagerVersion > 0) {
            StoreMetaRow(
                label = stringResource(R.string.store_detail_requires),
                value = theme.minManagerVersion.toString(),
            )
        }
    }
}

/** 可展开的长文本（介绍/更新说明）。 */
@Composable
private fun DetailText(
    title: String,
    body: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.text,
        )
        Text(
            text = body,
            fontSize = 12.sp,
            color = Xc.colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (onToggle != null) {
            Text(
                text = stringResource(
                    if (expanded) R.string.store_detail_collapse else R.string.store_detail_expand
                ),
                fontSize = 12.sp,
                color = Xc.colors.accent,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(Xc.shapes.pill)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 底部操作栏：收藏 + 主按钮（下载/更新/应用/取消/重试）。 */
@Composable
private fun DetailActionBar(
    theme: StoreTheme,
    download: StoreDownloadState?,
    isFavorite: Boolean,
    isCurrent: Boolean,
    busy: Boolean,
    actions: StoreActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Xc.colors.surface.copy(alpha = 0.94f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StoreFavoriteButton(
            isFavorite = isFavorite,
            onClick = { actions.toggleFavorite(theme.themeId) },
        )
        Spacer(Modifier.weight(1f))
        when (download) {
            is StoreDownloadState.Running -> StoreActionButton(
                label = stringResource(R.string.themes_downloading, download.percent),
                primary = false,
                enabled = true,
                onClick = { actions.cancelDownload(theme.themeId) },
            )

            is StoreDownloadState.Queued -> StoreActionButton(
                label = stringResource(R.string.store_action_cancel),
                primary = false,
                enabled = true,
                onClick = { actions.cancelDownload(theme.themeId) },
            )

            is StoreDownloadState.Failed, is StoreDownloadState.Cancelled -> StoreActionButton(
                label = stringResource(R.string.store_action_retry),
                primary = true,
                enabled = true,
                icon = Icons.Rounded.GetApp,
                onClick = { actions.retryDownload(theme) },
            )

            else -> when {
                isCurrent -> StoreActionButton(
                    label = stringResource(R.string.store_action_in_use),
                    primary = false,
                    enabled = false,
                    onClick = {},
                )

                theme.installed && !theme.updatable -> StoreActionButton(
                    label = stringResource(R.string.themes_apply),
                    primary = true,
                    enabled = !busy,
                    onClick = { actions.applyTheme(theme.themeId) },
                )

                else -> StoreActionButton(
                    label = if (theme.updatable) {
                        stringResource(R.string.themes_updatable)
                    } else {
                        stringResource(R.string.themes_download)
                    },
                    primary = true,
                    enabled = !busy,
                    icon = Icons.Rounded.GetApp,
                    onClick = { actions.download(theme) },
                )
            }
        }
    }
}

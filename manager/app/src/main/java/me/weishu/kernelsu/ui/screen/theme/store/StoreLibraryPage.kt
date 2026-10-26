package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 「我的」页：下载管理 + 收藏 + 发布入口。
 *
 * 下载管理不做「暂停/继续」：主题包只有几 MB，暂停再续的收益远小于实现成本
 * （还要断点续传、临时文件目录、跨进程恢复）。这里给的是**取消/重试**两个动作，
 * 语义清楚、状态机也简单。
 */
@Composable
fun StoreLibraryPage(state: StoreState, actions: StoreActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        PublishEntry(themeCount = state.localThemeCount, onClick = actions.openPublish)
        SourceEntry(state = state, onClick = actions.openSourceDialog)
        StoreChipsRow(
            chips = listOf(
                StoreChip(LibraryTab.DOWNLOADS.name, stringResource(R.string.store_library_downloads)),
                StoreChip(LibraryTab.FAVORITES.name, stringResource(R.string.store_library_favorites)),
            ),
            selectedId = state.libraryTab.name,
            onSelect = { id ->
                runCatching { LibraryTab.valueOf(id) }.getOrNull()?.let(actions.setLibraryTab)
            },
        )
        when (state.libraryTab) {
            LibraryTab.DOWNLOADS -> DownloadsSection(state = state, actions = actions)
            LibraryTab.FAVORITES -> FavoritesSection(state = state, actions = actions)
        }
    }
    if (state.sourceDialogOpen) {
        SourceDialog(state = state, actions = actions)
    }
}

/**
 * 数据源入口：显示"现在用的是哪个索引"。
 *
 * 为什么商店需要这个：自建/镜像源是真实需求（GitHub 在国内常年抽风，用户也可能想指向
 * 自己的主题仓库）。入口放在「我的」而不是推荐页，避免普通用户误触。
 */
@Composable
private fun SourceEntry(state: StoreState, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(color = Xc.colors.surfaceMuted),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.store_source_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                )
                Text(
                    text = if (state.sourceUrl.isEmpty()) {
                        stringResource(R.string.store_source_builtin)
                    } else {
                        state.sourceUrl
                    },
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Xc.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 数据源编辑对话框。 */
@Composable
private fun SourceDialog(state: StoreState, actions: StoreActions) {
    var draft by rememberSaveable(state.sourceUrl) { mutableStateOf(state.sourceUrl) }
    XDialog(
        show = true,
        onDismissRequest = actions.dismissSourceDialog,
        maxWidth = 460.dp,
    ) {
        Text(
            text = stringResource(R.string.store_source_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Xc.colors.text,
        )
        Text(
            text = stringResource(R.string.store_source_hint),
            fontSize = 11.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        TextField(
            value = draft,
            onValueChange = { draft = it },
            label = stringResource(R.string.store_source_input),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StoreActionButton(
                label = stringResource(R.string.store_source_save),
                primary = true,
                enabled = true,
                onClick = { actions.saveSourceUrl(draft) },
            )
            StoreActionButton(
                label = stringResource(R.string.store_source_reset),
                primary = false,
                enabled = state.sourceUrl.isNotEmpty(),
                onClick = actions.resetSourceUrl,
            )
        }
    }
}

/** 发布入口：入口必须长在商店里（用户原话是"根本没找到发布按钮"）。 */
@Composable
private fun PublishEntry(themeCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(color = Xc.colors.surfaceMuted),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.themes_publish_entry_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                )
                Text(
                    text = stringResource(R.string.themes_publish_entry_summary, themeCount),
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Xc.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DownloadsSection(state: StoreState, actions: StoreActions) {
    val entries = state.downloadEntries
    if (entries.isEmpty()) {
        StoreEmptyState(
            title = stringResource(R.string.store_library_empty_downloads),
            subtitle = stringResource(R.string.store_library_empty_downloads_hint),
            actionLabel = stringResource(R.string.store_tab_home),
            onAction = { actions.open(StoreRoute.Home) },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 14.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> "dl-${entry.first.themeId}" }) { _, entry ->
            DownloadRow(
                theme = entry.first,
                download = entry.second,
                actions = actions,
                isCurrent = state.currentThemeId == entry.first.themeId,
            )
        }
        item(key = "clear-finished") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                StoreActionButton(
                    label = stringResource(R.string.store_download_clear_finished),
                    primary = false,
                    enabled = true,
                    onClick = actions.clearFinishedDownloads,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    theme: StoreTheme,
    download: StoreDownloadState,
    actions: StoreActions,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(Xc.shapes.sm)
                .background(Xc.colors.surfaceMuted),
        ) {
            RemotePreview(
                url = theme.coverUrl.takeIf { it.isNotEmpty() },
                contentDescription = theme.name,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = theme.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Xc.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = downloadStateLabel(download),
                fontSize = 11.sp,
                color = when (download) {
                    is StoreDownloadState.Failed -> Xc.colors.danger
                    is StoreDownloadState.Installed -> Xc.colors.success
                    else -> Xc.colors.textMuted
                },
            )
            if (download is StoreDownloadState.Running) {
                Spacer(Modifier.height(6.dp))
                StoreProgressBar(progress = download.percent)
            }
        }
        when (download) {
            is StoreDownloadState.Running, is StoreDownloadState.Queued -> StoreActionButton(
                label = stringResource(R.string.store_action_cancel),
                primary = false,
                enabled = true,
                onClick = { actions.cancelDownload(theme.themeId) },
            )

            is StoreDownloadState.Failed, is StoreDownloadState.Cancelled -> StoreActionButton(
                label = stringResource(R.string.store_action_retry),
                primary = false,
                enabled = true,
                onClick = { actions.retryDownload(theme) },
            )

            is StoreDownloadState.Installed -> StoreActionButton(
                label = if (isCurrent) {
                    stringResource(R.string.store_action_in_use)
                } else {
                    stringResource(R.string.themes_apply)
                },
                primary = !isCurrent,
                enabled = !isCurrent,
                onClick = { actions.applyTheme(theme.themeId) },
            )
        }
    }
}

@Composable
private fun FavoritesSection(state: StoreState, actions: StoreActions) {
    val themes = state.favoriteThemes
    if (themes.isEmpty()) {
        StoreEmptyState(
            title = stringResource(R.string.store_library_empty_favorites),
            subtitle = stringResource(R.string.store_library_empty_favorites_hint),
            actionLabel = stringResource(R.string.store_tab_home),
            onAction = { actions.open(StoreRoute.Home) },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 14.dp),
    ) {
        val rows = themes.chunked(2)
        itemsIndexed(
            rows,
            key = { _, row -> "fav-${row.first().themeId}" },
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
                        isFavorite = true,
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

package me.weishu.kernelsu.ui.screen.theme.store

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 主题商店的**壳**：顶栏（搜索/返回）+ 路由分发 + 底栏。
 *
 * 页面级布局（推荐/分类/排行/搜索/详情/我的）各自成文件，这里只管"画哪一页"。
 * 路由是显式栈（见 [StoreState.backStack]）：根路由替换整栈，搜索/详情压栈，
 * 返回键与左上角返回走同一个 [StoreActions.back]。
 */
@Composable
fun ThemeStoreScreen(
    state: StoreState,
    actions: StoreActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    val context = LocalContext.current
    val notice = state.notice
    val noticeText = notice?.let { noticeText(it) }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            Toast.makeText(context, noticeText, Toast.LENGTH_SHORT).show()
            actions.consumeNotice()
        }
    }
    BackHandler(enabled = state.canGoBack) { actions.back() }

    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = innerPadding.calculateTopPadding(),
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
            ),
    ) {
        StoreTopBar(state = state, actions = actions)
        Box(modifier = Modifier.weight(1f)) {
            when (val route = state.route) {
                StoreRoute.Home -> StoreHomePage(state = state, actions = actions, backdrop = backdrop)
                StoreRoute.Browse -> StoreBrowsePage(state = state, actions = actions)
                StoreRoute.Rank -> StoreRankPage(state = state, actions = actions)
                StoreRoute.Search -> StoreSearchPage(state = state, actions = actions)
                StoreRoute.Library -> StoreLibraryPage(state = state, actions = actions)
                is StoreRoute.Detail -> StoreDetailPage(state = state, actions = actions, themeId = route.themeId)
            }
        }
        StoreBottomBar(state = state, actions = actions)
    }
}

/** 顶栏：根路由显示搜索药丸（+ 刷新），压栈路由显示返回 + 标题。 */
@Composable
private fun StoreTopBar(state: StoreState, actions: StoreActions) {
    val route = state.route
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (route) {
            StoreRoute.Search -> {
                StoreIconAction(
                    icon = MiuixIcons.Back,
                    description = stringResource(R.string.themes_title),
                    onClick = actions.back,
                )
                Text(
                    text = stringResource(R.string.store_search_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                    modifier = Modifier.weight(1f),
                )
            }

            is StoreRoute.Detail -> {
                StoreIconAction(
                    icon = MiuixIcons.Back,
                    description = stringResource(R.string.themes_title),
                    onClick = actions.back,
                )
                Text(
                    text = state.detailTheme?.name ?: stringResource(R.string.store_detail_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            StoreRoute.Home -> {
                StoreSearchPill(
                    text = state.query,
                    hint = stringResource(R.string.themes_search_hint),
                    onClick = { actions.open(StoreRoute.Search) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.refresh) {
                    Icon(
                        imageVector = Icons.Rounded.GetApp,
                        contentDescription = stringResource(R.string.themes_refresh),
                        tint = if (state.loading || state.refreshing) {
                            Xc.colors.textMuted
                        } else {
                            Xc.colors.textSecondary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            else -> {
                StoreSearchPill(
                    text = state.query,
                    hint = stringResource(R.string.themes_search_hint),
                    onClick = { actions.open(StoreRoute.Search) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 底栏：推荐 / 分类 / 排行 / 我的（下载与收藏都归在「我的」）。 */
@Composable
private fun StoreBottomBar(state: StoreState, actions: StoreActions) {
    val route = state.route
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Xc.colors.surface.copy(alpha = 0.92f))
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreNavItem(
            label = stringResource(R.string.store_tab_home),
            icon = Icons.Rounded.Star,
            selected = route == StoreRoute.Home,
            onClick = { actions.open(StoreRoute.Home) },
        )
        StoreNavItem(
            label = stringResource(R.string.store_tab_browse),
            icon = Icons.Rounded.Palette,
            selected = route == StoreRoute.Browse,
            onClick = { actions.open(StoreRoute.Browse) },
        )
        StoreNavItem(
            label = stringResource(R.string.store_tab_rank),
            icon = Icons.Rounded.GetApp,
            selected = route == StoreRoute.Rank,
            onClick = { actions.open(StoreRoute.Rank) },
        )
        StoreNavItem(
            label = stringResource(R.string.store_tab_mine),
            icon = Icons.Rounded.BookmarkBorder,
            selected = route == StoreRoute.Library,
            badge = state.activeDownloads,
            onClick = { actions.open(StoreRoute.Library) },
        )
    }
}

@Composable
private fun StoreNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    val tint = if (selected) Xc.colors.accent else Xc.colors.textMuted
    Column(
        modifier = Modifier
            .clip(Xc.shapes.md)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(Xc.shapes.pill)
                        .background(Xc.colors.accent),
                )
            }
        }
        Text(
            text = label,
            fontSize = 10.sp,
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 一次性提示的文案（类型 → 本地化串）。 */
@Composable
private fun noticeText(notice: StoreNotice): String = when (notice) {
    StoreNotice.Applied -> stringResource(R.string.themes_applied)
    is StoreNotice.ApplyFailed -> stringResource(R.string.themes_apply_failed)
    StoreNotice.Favorited -> stringResource(R.string.store_notice_favorited)
    StoreNotice.Unfavorited -> stringResource(R.string.store_notice_unfavorited)
    is StoreNotice.DownloadQueued -> stringResource(R.string.store_notice_queued, notice.name)
    StoreNotice.AlreadyInstalled -> stringResource(R.string.store_notice_already_installed)
    StoreNotice.HistoryCleared -> stringResource(R.string.store_notice_history_cleared)
    StoreNotice.DownloadFailed -> stringResource(R.string.store_download_failed)
}

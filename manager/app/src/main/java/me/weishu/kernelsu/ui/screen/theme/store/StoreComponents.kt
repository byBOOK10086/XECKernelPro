package me.weishu.kernelsu.ui.screen.theme.store

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.Element
import me.weishu.kernelsu.data.theme.StoreBanner
import me.weishu.kernelsu.data.theme.StoreDownloadState
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.ui.component.RemotePreview
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop

// --------------------------------------------------------------------------- 基础件

/** 搜索药丸：点一下进搜索页（小米主题商店顶部那条）。 */
@Composable
fun StoreSearchPill(
    text: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Xc.shapes.pill)
            .background(Xc.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Xc.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.ifEmpty { hint },
            fontSize = 13.sp,
            color = if (text.isEmpty()) Xc.colors.textMuted else Xc.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 分区标题：左标题 + 右「查看全部/更多」。 */
@Composable
fun StoreSectionTitle(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Xc.colors.text,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(Xc.shapes.pill)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = actionLabel, fontSize = 12.sp, color = Xc.colors.textSecondary)
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Xc.colors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** 小胶囊标签（免费/已装/可更新/精选）。 */
@Composable
fun StoreBadge(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .clip(Xc.shapes.pill)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 一个 chips 选项。 */
data class StoreChip(val id: String, val label: String)

/**
 * 横向 chips 筛选条。
 *
 * 选中态用主色实心，未选中用 `surfaceMuted` 描边 —— 与设置页那两个 chips 行同一套观感。
 */
@Composable
fun StoreChipsRow(
    chips: List<StoreChip>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(chips, key = { _, chip -> chip.id }) { _, chip ->
            val selected = chip.id == selectedId
            Text(
                text = chip.label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) Xc.colors.onSemanticSolid else Xc.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(Xc.shapes.pill)
                    .background(if (selected) Xc.colors.accent else Xc.colors.surfaceMuted)
                    .clickable { onSelect(chip.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 主题色板（索引里给了 accent/backdrop 才画）。 */
@Composable
fun StoreSwatches(colors: List<Color>, modifier: Modifier = Modifier) {
    if (colors.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.take(4).forEach { color ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(Xc.shapes.pill)
                    .background(color)
                    .border(1.dp, Xc.colors.glassRim, Xc.shapes.pill),
            )
        }
    }
}

// --------------------------------------------------------------------------- 横幅

/**
 * 精选轮播（自动 4.5s 一页，手动滑动后从当前页继续计时）。
 *
 * 小米主题商店的门面位：大图 + 底部渐变纱 + 标题/副标题 + 页点。
 */
@Composable
fun StoreBannerCarousel(
    banners: List<Pair<StoreBanner, StoreTheme?>>,
    onOpenTheme: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (banners.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { banners.size })
    LaunchedEffect(banners.size) {
        if (banners.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4500)
            if (!pagerState.isScrollInProgress && pagerState.pageCount > 1) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }
    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 10.dp,
        ) { page ->
            val (banner, theme) = banners[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(Xc.shapes.lg)
                    .clickable(enabled = banner.hasTarget) {
                        banner.themeId.takeIf { it.isNotEmpty() }?.let(onOpenTheme)
                    },
            ) {
                RemotePreview(
                    url = banner.imageUrl,
                    contentDescription = banner.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = Xc.colors.surfaceMuted,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.66f),
                            )
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = banner.title.ifEmpty { theme?.name.orEmpty() },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (theme?.featured == true) {
                            StoreBadge(
                                text = stringResource(R.string.themes_featured),
                                background = Xc.colors.accent,
                                contentColor = Xc.colors.onSemanticSolid,
                            )
                        }
                    }
                    val subtitle = banner.subtitle.ifEmpty { theme?.author.orEmpty() }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(banners.size) { index ->
                    val active = pagerState.currentPage == index
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

// --------------------------------------------------------------------------- 金刚区

/** 首页快捷入口（排行/分类/我的），小米的"金刚区"。 */
@Composable
fun StoreShortcutRow(
    entries: List<StoreShortcut>,
    onOpen: (StoreRoute) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(Xc.shapes.md)
                    .background(Xc.colors.surfaceMuted)
                    .clickable { onOpen(entry.route) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(Xc.shapes.pill)
                        .background(Xc.colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = entry.label,
                        tint = Xc.colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(text = entry.label, fontSize = 11.sp, color = Xc.colors.textSecondary)
            }
        }
    }
}

/** 一个快捷入口。[icon] 用 `Icons.Rounded.*`。 */
data class StoreShortcut(
    val label: String,
    val route: StoreRoute,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

// --------------------------------------------------------------------------- 卡片

/**
 * 网格卡（图上、文字下）—— 小米商店的标准卡片。
 *
 * 右上角角标优先级：可更新 > 已安装 > 免费/价格；下载中改为整块进度遮罩。
 */
@Composable
fun StoreThemeCard(
    theme: StoreTheme,
    downloadState: StoreDownloadState?,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val running = downloadState as? StoreDownloadState.Running
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
                    .aspectRatio(3f / 4f),
            ) {
                RemotePreview(
                    url = theme.coverUrl.takeIf { it.isNotEmpty() },
                    contentDescription = theme.name,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = Xc.colors.surfaceMuted,
                )
                // 底部渐变纱：保证角标与名字在亮图上也能读。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.62f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            )
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                            background = Color.Black.copy(alpha = 0.42f),
                            contentColor = Color.White,
                        )

                        else -> StoreBadge(
                            text = priceText(theme.priceCents),
                            background = Xc.colors.accent,
                            contentColor = Xc.colors.onSemanticSolid,
                        )
                    }
                }
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmark,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(14.dp),
                    )
                }
                if (running != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StoreProgressRing(progress = running.percent, diameter = 46.dp)
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = theme.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = theme.author.ifEmpty { theme.updatedAt.substringBefore('T') }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoreSwatches(
                    colors = theme.swatchColors(),
                    modifier = Modifier.weight(1f),
                )
                when (downloadState) {
                    is StoreDownloadState.Queued -> Text(
                        text = stringResource(R.string.store_download_queued),
                        fontSize = 10.sp,
                        color = Xc.colors.textMuted,
                    )

                    is StoreDownloadState.Failed -> Text(
                        text = stringResource(R.string.store_download_failed),
                        fontSize = 10.sp,
                        color = Xc.colors.danger,
                    )

                    is StoreDownloadState.Cancelled -> Text(
                        text = stringResource(R.string.store_download_cancelled),
                        fontSize = 10.sp,
                        color = Xc.colors.textMuted,
                    )

                    else -> Text(
                        text = if (theme.installed && !theme.updatable) {
                            stringResource(R.string.store_action_open)
                        } else {
                            stringResource(R.string.themes_download)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Xc.colors.accent,
                        modifier = Modifier
                            .clip(Xc.shapes.pill)
                            .clickable(onClick = onAction)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 横排货架卡（宽 148dp，4:3 图 + 一行标题）。 */
@Composable
fun StoreShelfCard(
    theme: StoreTheme,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(146.dp)
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(Xc.shapes.md),
        ) {
            RemotePreview(
                url = theme.coverUrl.takeIf { it.isNotEmpty() },
                contentDescription = theme.name,
                modifier = Modifier.fillMaxSize(),
                placeholder = Xc.colors.surfaceMuted,
            )
            if (theme.installed) {
                StoreBadge(
                    text = stringResource(R.string.themes_installed),
                    background = Xc.colors.successTint,
                    contentColor = Xc.colors.onSuccessTint,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
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
            text = listOfNotNull(
                theme.author.takeIf { it.isNotEmpty() },
                stringResource(R.string.store_downloads_count, formatCount(theme.downloads))
                    .takeIf { theme.downloads > 0 },
            ).joinToString(" · "),
            fontSize = 11.sp,
            color = Xc.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 排行榜一行：名次徽章 + 缩略图 + 标题/作者 + 右侧指标。 */
@Composable
fun StoreRankRow(
    rank: Int,
    theme: StoreTheme,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Xc.shapes.md)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (rank <= 3) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(Xc.shapes.pill)
                        .background(
                            when (rank) {
                                1 -> Color(0xFFFFB300)
                                2 -> Color(0xFF9E9E9E)
                                else -> Color(0xFFB07A4F)
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rank.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            } else {
                Text(
                    text = rank.toString(),
                    fontSize = 12.sp,
                    color = Xc.colors.textMuted,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 52.dp)
                .clip(Xc.shapes.sm),
        ) {
            RemotePreview(
                url = theme.coverUrl.takeIf { it.isNotEmpty() },
                contentDescription = theme.name,
                modifier = Modifier.fillMaxSize(),
                placeholder = Xc.colors.surfaceMuted,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
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
                text = listOfNotNull(
                    theme.author.takeIf { it.isNotEmpty() },
                    theme.tags.firstOrNull(),
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = Xc.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (theme.rating > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", theme.rating),
                        fontSize = 11.sp,
                        color = Xc.colors.textSecondary,
                    )
                }
            }
            if (theme.downloads > 0) {
                Text(
                    text = stringResource(
                        R.string.store_downloads_count,
                        formatCount(theme.downloads),
                    ),
                    fontSize = 10.sp,
                    color = Xc.colors.textMuted,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- 反馈态

/** 圆形进度（下载中盖在封面中央）。 */
@Composable
fun StoreProgressRing(progress: Int, diameter: Dp = 44.dp) {
    val clamped = progress.coerceIn(0, 100)
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.10f
            val inset = stroke / 2f
            drawArc(
                color = Color.White.copy(alpha = 0.28f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * clamped / 100f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = "$clamped%",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

/** 细进度条（列表里用）。 */
@Composable
fun StoreProgressBar(progress: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(Xc.shapes.pill)
            .background(Xc.colors.surfaceMuted),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                .height(4.dp)
                .clip(Xc.shapes.pill)
                .background(Xc.colors.accent),
        )
    }
}

/** 骨架屏块：横向扫光的灰条。 */
@Composable
fun StoreSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = Xc.shapes.md,
) {
    val transition = rememberInfiniteTransition(label = "store-skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "store-skeleton-shift",
    )
    val base = Xc.colors.surfaceMuted
    val highlight = Xc.colors.surface
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(shift * 900f - 300f, 0f),
                    end = Offset(shift * 900f, 260f),
                )
            ),
    )
}

/** 商店骨架屏：两列卡片占位（首屏/刷新时用，比转圈更像"正在长出来"）。 */
@Composable
fun StoreSkeletonGrid(rows: Int = 3) {
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(2) {
                    Column(modifier = Modifier.weight(1f)) {
                        StoreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f),
                        )
                        Spacer(Modifier.height(8.dp))
                        StoreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(12.dp),
                            shape = Xc.shapes.xs,
                        )
                        Spacer(Modifier.height(6.dp))
                        StoreSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.45f)
                                .height(10.dp),
                            shape = Xc.shapes.xs,
                        )
                    }
                }
            }
        }
    }
}

/** 空态（图标 + 一句话 + 可选动作）。 */
@Composable
fun StoreEmptyState(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = Xc.colors.textMuted,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Xc.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = actionLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Xc.colors.onSemanticSolid,
                modifier = Modifier
                    .clip(Xc.shapes.pill)
                    .background(Xc.colors.accent)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** 错误态（错误图标 + 文案 + 重试）。 */
@Composable
fun StoreErrorState(
    title: String,
    detail: String?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = Xc.colors.danger,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                fontSize = 11.sp,
                color = Xc.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(Xc.shapes.pill)
                .background(Xc.colors.surfaceMuted)
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = Xc.colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(text = actionLabel, fontSize = 13.sp, color = Xc.colors.textSecondary)
        }
    }
}

/** 详情/列表里的一行元信息（左标签右值）。 */
@Composable
fun StoreMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 12.sp, color = Xc.colors.textMuted, modifier = Modifier.width(84.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = Xc.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 收藏按钮（详情页底部栏用）。 */
@Composable
fun StoreFavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(Xc.shapes.pill)
            .background(Xc.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            contentDescription = null,
            tint = if (isFavorite) Xc.colors.accent else Xc.colors.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(
                if (isFavorite) R.string.store_action_saved else R.string.store_action_save
            ),
            fontSize = 12.sp,
            color = if (isFavorite) Xc.colors.accent else Xc.colors.textSecondary,
        )
    }
}

/** 主按钮（下载/应用）。[primary] = false 时是次要按钮（取消/已安装）。 */
@Composable
fun StoreActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(Xc.shapes.pill)
            .background(if (primary) Xc.colors.accent else Xc.colors.surfaceMuted)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (primary) Xc.colors.onSemanticSolid else Xc.colors.textSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) {
                Xc.colors.onSemanticSolid
            } else {
                if (enabled) Xc.colors.textSecondary else Xc.colors.textMuted
            },
        )
    }
}

/** 下载图标（详情页/下载管理行复用）。 */
@Composable
fun StoreDownloadIcon(tint: Color = Xc.colors.accent) {
    Icon(
        imageVector = Icons.Rounded.GetApp,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(15.dp),
    )
}

/** 关闭按钮（搜索框右侧/提示条右侧）。 */
@Composable
fun StoreIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Xc.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// --------------------------------------------------------------------------- 纯函数

/** 价格展示：0 = 免费，其余按「分 → 元」显示（索引里就是分，避免浮点误差）。 */
fun priceText(priceCents: Int): String = if (priceCents <= 0) {
    ""
} else {
    val yuan = priceCents / 100
    val cents = priceCents % 100
    if (cents == 0) "¥$yuan" else "¥$yuan.${cents.toString().padStart(2, '0')}"
}

/** 下载量/评分人数的短写：1234 → 1.2k，1200000 → 1.2m。 */
fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/** 字节数 → 人类可读（卡片空间小，保留 1 位小数）。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 索引里的时间串 → 只留日期（详情页「更新于」用）。 */
fun formatDate(raw: String): String = raw.take(10)

/** 主题的三个令牌色，给卡片色板用。 */
fun StoreTheme.swatchColors(): List<Color> = buildList {
    me.weishu.kernelsu.data.theme.ThemeCodec.parseColor(accentHex)?.let { add(Color(it)) }
    me.weishu.kernelsu.data.theme.ThemeCodec.parseColor(backdropDarkHex)?.let { add(Color(it)) }
    me.weishu.kernelsu.data.theme.ThemeCodec.parseColor(backdropLightHex)?.let { add(Color(it)) }
}

/** 元素 id → 本地化文案（分类页 chips / 详情页「包含」）。 */
@Composable
fun elementLabel(element: String): String = when (element) {
    Element.COLORS -> stringResource(R.string.themes_category_colors)
    Element.MODE_BADGE -> stringResource(R.string.themes_category_mode_badge)
    Element.WALLPAPER -> stringResource(R.string.store_element_wallpaper)
    Element.FONT -> stringResource(R.string.store_element_font)
    else -> element
}

/** 分类 id → 本地化文案。 */
@Composable
fun categoryLabel(category: String): String = when (category) {
    StoreTheme.CATEGORY_THEME -> stringResource(R.string.store_category_theme)
    StoreTheme.CATEGORY_COLORS -> stringResource(R.string.themes_category_colors)
    StoreTheme.CATEGORY_BADGE -> stringResource(R.string.themes_category_mode_badge)
    StoreTheme.CATEGORY_FONT -> stringResource(R.string.store_element_font)
    StoreTheme.CATEGORY_WALLPAPER -> stringResource(R.string.store_element_wallpaper)
    else -> category
}

/** 下载状态 → 一句话（下载管理页）。 */
@Composable
fun downloadStateLabel(state: StoreDownloadState?): String = when (state) {
    null -> ""
    is StoreDownloadState.Queued -> stringResource(R.string.store_download_queued)
    is StoreDownloadState.Running -> stringResource(R.string.themes_downloading, state.percent)
    is StoreDownloadState.Installed -> stringResource(R.string.themes_installed)
    is StoreDownloadState.Failed -> stringResource(R.string.store_download_failed)
    is StoreDownloadState.Cancelled -> stringResource(R.string.store_download_cancelled)
}

/** 失败码 → 主文案（数据层只给码，这里才翻译）。 */
@Composable
fun storeErrorTitle(code: String?): String = when (code) {
    me.weishu.kernelsu.data.theme.StoreFailure.CODE_NO_NETWORK ->
        stringResource(R.string.store_error_no_network)

    me.weishu.kernelsu.data.theme.StoreFailure.CODE_UNREACHABLE ->
        stringResource(R.string.store_error_unreachable)

    me.weishu.kernelsu.data.theme.StoreFailure.CODE_PARSE ->
        stringResource(R.string.store_error_parse)

    me.weishu.kernelsu.data.theme.StoreFailure.CODE_CACHE ->
        stringResource(R.string.store_error_cache)

    else -> stringResource(R.string.store_error_unknown)
}

/** 时间戳 → `MM-dd HH:mm`（列表/页脚里只展示到分钟）。 */
fun formatEpoch(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
}

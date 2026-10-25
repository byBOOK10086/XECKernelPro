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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
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
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeStoreSettings
import me.weishu.kernelsu.ui.component.RemotePreview
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
 * 主题商城 Tab：搜索 + 刷新 + 一条主题一张玻璃卡。
 *
 * 分工与模块仓库（[me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoMiuix]）一致：
 * 状态与动作都在 [ThemeUiState] / [ThemeActions] 上，这里只负责画，
 * 唯一自己算的东西是"索引里那三个颜色十六进制能不能解析成色块"。
 *
 * [innerPadding] 由外层 `ThemeScreenMiuix` 的 Scaffold 给出（顶栏 + 系统栏），
 * 这里把 top/start/end 交给整体内边距、bottom 交给列表尾部，
 * 于是内容既不会钻到顶栏下面，也不会被导航栏压住。
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
                    // 精选横幅（索引 v2 的 featured + 预览图）：小米主题商店式的门面。
                    if (featured.isNotEmpty()) {
                        item(key = "featured", contentType = "featured") {
                            FeaturedRow(featured)
                        }
                    }
                    item(key = "categories", contentType = "categories") {
                        CategoryChips(
                            selected = category,
                            onSelect = { category = it },
                        )
                    }
                    items(items = visible, key = { it.themeId }, contentType = { "theme" }) { theme ->
                        StoreThemeCard(
                            theme = theme,
                            state = state,
                            actions = actions,
                            backdrop = backdrop,
                        )
                    }
                }
            }
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

/** 商城里的一条主题：色卡 + 版本/作者/体积 + 右对齐的操作按钮。 */
@Composable
private fun StoreThemeCard(
    theme: StoreTheme,
    state: ThemeUiState,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    val downloading = state.downloadingId == theme.themeId
    val active = state.activeId == theme.themeId
    // 索引里的颜色是用户提供的输入：解析不了的直接跳过，不画灰块占位。
    val swatches = remember(theme.accentHex, theme.backdropDarkHex, theme.backdropLightHex) {
        listOf(theme.accentHex, theme.backdropDarkHex, theme.backdropLightHex)
            .mapNotNull { ThemeCodec.parseColor(it) }
            .map { Color(it) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 预览缩略图（索引带 previewUrl 时）：有图优先于色卡，更接近商店的"看图挑"。
                if (theme.previewUrl.isNotEmpty()) {
                    RemotePreview(
                        url = theme.previewUrl,
                        contentDescription = theme.name,
                        modifier = Modifier
                            .size(width = 64.dp, height = 64.dp)
                            .clip(Xc.shapes.md),
                        placeholder = Xc.colors.surfaceMuted,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = theme.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (theme.version.isNotEmpty()) {
                    ThemeTag(
                        text = "v" + theme.version,
                        containerColor = Xc.colors.surfaceMuted,
                        contentColor = Xc.colors.textSecondary,
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

            if (theme.author.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.themes_by, theme.author),
                    fontSize = 12.sp,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (theme.summary.isNotEmpty()) {
                Text(
                    text = theme.summary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Xc.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (swatches.isNotEmpty() || theme.size > 0L) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeSwatches(swatches)
                    if (theme.size > 0L) {
                        Text(
                            // 索引里的 size 是字节，文案自己带 "KB"，所以这里先换算好整数。
                            text = stringResource(R.string.themes_size, (theme.size / 1024L).toString()),
                            fontSize = 12.sp,
                            color = Xc.colors.textMuted,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = Xc.colors.glassRim.copy(alpha = 0.5f),
            )

            if (downloading) {
                // 下载中把按钮整行换掉：按钮留在那儿只会被反复点，进度才是这时候唯一有用的信息。
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.themes_downloading, state.downloadProgress),
                        fontSize = 12.sp,
                        color = Xc.colors.textSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    ThemeProgressBar(progress = state.downloadProgress)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    if (!theme.installed || theme.updatable) {
                        TextButton(
                            text = stringResource(R.string.themes_download),
                            onClick = { actions.onDownload(theme) },
                            enabled = !state.busy,
                        )
                    }
                    if (theme.installed) {
                        if (active) {
                            // 已经生效的主题不给"应用"：点了也不会变，给个禁用标签说明现状。
                            TextButton(
                                text = stringResource(R.string.themes_installed),
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            TextButton(
                                text = stringResource(R.string.themes_apply),
                                onClick = { actions.onApply(theme.themeId) },
                                enabled = !state.busy,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 精选横幅：一张带预览大图的玻璃卡，名字/作者压在底部渐变上（小米主题商店的门面位）。 */
@Composable
private fun FeaturedRow(themes: List<StoreTheme>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items = themes, key = { _, t -> t.themeId }) { _, theme ->
            Box(
                modifier = Modifier
                    .width(248.dp)
                    .height(148.dp)
                    .clip(Xc.shapes.lg),
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
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            )
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = theme.name,
                            fontSize = 16.sp,
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

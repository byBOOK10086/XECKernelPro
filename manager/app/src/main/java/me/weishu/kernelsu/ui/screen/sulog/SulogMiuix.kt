package me.weishu.kernelsu.ui.screen.sulog

import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.ListPopupDefaults
import me.weishu.kernelsu.ui.component.ScrollToTopOnChange
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.miuix.SearchBarFake
import me.weishu.kernelsu.ui.component.miuix.SearchBox
import me.weishu.kernelsu.ui.component.miuix.SearchPager
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.panel.Xc8Dropdown
import me.weishu.kernelsu.ui.design.token.LocalXcReducedMotion
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcMotionDefault
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.SulogEntry
import me.weishu.kernelsu.ui.util.SulogEventFilter
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import me.weishu.kernelsu.ui.design.token.XcRadius

@Composable
fun SulogScreenMiuix(
    state: SulogScreenState,
    actions: SulogActions,
) {
    val enableBlur = LocalEnableBlur.current
    val density = LocalDensity.current
    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val fileSelector = buildSulogFileSelector(state.files, state.selectedFilePath)
    val refreshTexts = listOf(
        stringResource(R.string.refresh_pulling),
        stringResource(R.string.refresh_release),
        stringResource(R.string.refresh_refresh),
        stringResource(R.string.refresh_complete),
    )
    var searchStatus by remember { mutableStateOf(SearchStatus("")) }
    var selectedEntry by remember { mutableStateOf<SulogEntry?>(null) }

    LaunchedEffect(state.searchText, state.visibleEntries) {
        searchStatus = searchStatus.copy(
            searchText = state.searchText,
            resultStatus = if (state.searchText.isBlank()) SearchStatus.ResultStatus.DEFAULT else SearchStatus.ResultStatus.SHOW,
        )
    }

    fun onSearchStatusChange(nextStatus: SearchStatus) {
        searchStatus = nextStatus.copy(
            resultStatus = if (nextStatus.searchText.isBlank()) SearchStatus.ResultStatus.DEFAULT else SearchStatus.ResultStatus.SHOW,
        )
        actions.onSearchTextChange(nextStatus.searchText)
    }

    SulogDetailDialog(
        show = selectedEntry != null,
        entry = selectedEntry,
        onDismiss = { selectedEntry = null },
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.settings_sulog),
                        navigationIcon = {
                            IconButton(
                                onClick = actions.onBack,
                            ) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = null,
                                    tint = colorScheme.onSurface,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                modifier = Modifier.padding(end = 8.dp),
                                onClick = actions.onCleanFile,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    tint = colorScheme.onSurface,
                                    contentDescription = stringResource(R.string.sulog_clean_title),
                                )
                            }

                            Box {
                                val showFilterPopup = remember { mutableStateOf(false) }
                                OverlayListPopup(
                                    show = showFilterPopup.value,
                                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = {
                                        showFilterPopup.value = false
                                    },
                                    content = {
                                        ListPopupColumn {
                                            SulogEventFilter.entries.forEachIndexed { index, filter ->
                                                DropdownImpl(
                                                    text = sulogFilterLabel(filter),
                                                    isSelected = filter in state.selectedFilters,
                                                    optionSize = SulogEventFilter.entries.size,
                                                    onSelectedIndexChange = {
                                                        actions.onToggleFilter(filter)
                                                    },
                                                    index = index,
                                                )
                                            }
                                        }
                                    },
                                )
                                IconButton(
                                    onClick = { showFilterPopup.value = true },
                                    holdDownState = showFilterPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Filter,
                                        tint = colorScheme.onSurface,
                                        contentDescription = stringResource(R.string.sulog_filter_title),
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            val newOffsetY = coordinates.positionInWindow().y.toDp()
                                            if (searchStatus.offsetY != newOffsetY) {
                                                onSearchStatusChange(searchStatus.copy(offsetY = newOffsetY))
                                            }
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    onSearchStatusChange(searchStatus.copy(current = SearchStatus.Status.EXPANDING))
                                                }
                                            }
                                        } else Modifier,
                                    ),
                            ) {
                                SearchBarFake(searchStatus.label, dynamicTopPadding)
                            }
                        }
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = ::onSearchStatusChange,
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
            ) {
                val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                ) {
                    item {
                        Spacer(Modifier.height(6.dp))
                    }
                    sulogEntriesSection(
                        entries = state.visibleEntries,
                        errorMessage = state.errorMessage,
                        onEntryClick = { selectedEntry = it },
                    )
                    item {
                        Spacer(Modifier.height(imeBottomPadding))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        searchStatus.SearchBox {
            PullToRefresh(
                isRefreshing = state.isLoading || state.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = actions.onRefresh,
                refreshTexts = refreshTexts,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
            ) {
                val latestVisibleEntries = rememberUpdatedState(state.visibleEntries)
                ScrollToTopOnChange(
                    listState,
                    state.searchText,
                    state.selectedFilters,
                    state.selectedFilePath,
                ) { latestVisibleEntries.value }
                Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 6.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                        ),
                        overscrollEffect = null,
                    ) {
                        item {
                            SulogStatusSection(state, actions)
                        }

                        item {
                            Card(cornerRadius = XcRadius.md, 
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                                colors = CardDefaults.defaultColors(color = Color.Transparent),
                            ) {
                                Xc8Dropdown(
                                    title = stringResource(R.string.sulog_log_files),
                                    items = fileSelector.items,
                                    enabled = fileSelector.items.isNotEmpty(),
                                    selectedIndex = fileSelector.selectedIndex,
                                    cancelText = stringResource(android.R.string.cancel),
                                    onSelectedIndexChange = { index ->
                                        state.files.getOrNull(index)?.let { file ->
                                            actions.onSelectFile(file.path)
                                        }
                                    },
                                )
                            }
                        }

                        sulogEntriesSection(
                            entries = state.visibleEntries,
                            errorMessage = state.errorMessage,
                            backdrop = backdrop,
                            onEntryClick = { selectedEntry = it },
                        )

                        item {
                            Spacer(
                                Modifier.height(
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SulogStatusSection(
    state: SulogScreenState,
    actions: SulogActions,
) {
    // Skip entirely when no banner is shown; an empty Row would still take up
    // its bottom padding above the file selector.
    val showBanner = when (state.sulogStatus) {
        "unsupported", "managed" -> true
        "supported" -> !state.isSulogEnabled
        else -> false
    }
    if (!showBanner) return

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        when (state.sulogStatus) {
            "unsupported" -> {
                WarningCard(
                    message = stringResource(R.string.sulog_unsupported_title),
                )
            }

            "managed" -> {
                WarningCard(
                    message = stringResource(R.string.feature_status_managed_summary),
                )
            }

            "supported" if !state.isSulogEnabled -> {
                WarningCard(
                    message = stringResource(R.string.sulog_disabled_title),
                    action = {
                        TextButton(
                            text = stringResource(R.string.sulog_enable_action),
                            onClick = actions.onEnableSulog,
                            minHeight = 28.dp,
                            // MIUI 8 的按钮是方角（4dp），50dp 那种胶囊是上一版玻璃形态的遗留。
                            cornerRadius = XcRadius.sm,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.textButtonColors(
                                // 这张告警卡的底已经是 dangerTint，按钮再用同色就糊成
                                // 一整块，所以这里走实心危险色 + 白字，保住行动号召的分量。
                                color = Xc.colors.danger,
                                textColor = Xc.colors.onSemanticSolid,
                            ),
                        )
                    },
                )
            }

            else -> Unit
        }
    }
}

private fun LazyListScope.sulogEntriesSection(
    entries: List<SulogEntry>,
    errorMessage: String?,
    backdrop: LayerBackdrop? = null,
    onEntryClick: (SulogEntry) -> Unit,
) {
    // 与 entries 一一对应的稳定 key。放在 when 之前算一次，两个分支都不重复计算。
    val keys = stableEntryKeys(entries)
    when {
        errorMessage != null -> item {
            SulogMessageCard(
                modifier = Modifier.fillParentMaxSize(),
                title = stringResource(R.string.sulog_failed_to_load),
                summary = errorMessage,
            )
        }

        // 2026-11-11 第三轮：key 去掉 index（否则新日志插到最前时所有既有项的
        // 下标 +1、key 全变，animateItem 会把整表当新项重放），并补入场动画。
        // 重复 key 会让 LazyColumn 直接抛异常，所以用 [stableEntryKeys] 做一次
        // 「只在真重复时加后缀」的去重 —— 后缀与出现次序绑定，前缀项仍然稳定。
        else -> itemsIndexed(entries, key = { index, _ -> keys[index] }) { index, entry ->
            val reduced = LocalXcReducedMotion.current
            val fade = if (reduced) null else tween<Float>(XcMotionDefault.listIn)
            val place = if (reduced) null else tween<IntOffset>(XcMotionDefault.listIn)
            SulogEntryCard(
                entry = entry,
                backdrop = backdrop,
                onClick = { onEntryClick(entry) },
                modifier = Modifier.animateItem(
                    fadeInSpec = fade,
                    placementSpec = place,
                    fadeOutSpec = fade,
                ),
            )
        }
    }
}

/**
 * 给一批日志算稳定 key。
 *
 * [SulogEntry.key] 本身是 `seq`（或原始行），正常情况下唯一；但 `dropped` 这类
 * 没有 seq 的条目可能撞车，而 LazyColumn 对重复 key 是**直接抛异常**的。这里只在
 * 真的撞车时追加 `#n`，保证既稳定又唯一。
 */
private fun stableEntryKeys(entries: List<SulogEntry>): List<String> {
    val seen = HashMap<String, Int>(entries.size)
    return entries.map { entry ->
        val n = seen.getOrDefault(entry.key, 0)
        seen[entry.key] = n + 1
        if (n == 0) entry.key else "${entry.key}#$n"
    }
}

@Composable
private fun SulogEntryCard(
    entry: SulogEntry,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    onClick: () -> Unit,
) {
    Card(cornerRadius = XcRadius.md, 
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = onClick,
        showIndication = true,
        insideMargin = PaddingValues(16.dp),
    ) {
        val layoutDirection = LocalLayoutDirection.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sulogEntryTitle(entry),
                    modifier = Modifier.basicMarquee(),
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                sulogEntryDescription(entry)?.let {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                entry.timestampText?.let {
                    Text(
                        text = it,
                        modifier = Modifier.basicMarquee(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val colors = listOf(
                        colorScheme.primary to colorScheme.onPrimary,
                        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer,
                        colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer,
                    )
                    sulogEntrySummaryTags(entry).forEachIndexed { index, tag ->
                        val (bg, fg) = colors.getOrElse(index) { colors.last() }
                        StatusTag(label = tag, backgroundColor = bg, contentColor = fg)
                    }
                }
            }
            sulogEntryStatus(entry)?.let {
                Text(
                    text = it,
                    color = colorScheme.onSurfaceVariantActions,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Image(
                modifier = Modifier
                    .graphicsLayer {
                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                    }
                    .padding(start = 8.dp)
                    .size(width = 10.dp, height = 16.dp),
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
            )
        }
    }
}

@Composable
private fun SulogMessageCard(
    modifier: Modifier,
    title: String,
    summary: String?,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight(550),
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SulogDetailDialog(
    show: Boolean,
    entry: SulogEntry?,
    onDismiss: () -> Unit,
) {
    var lastEntry by remember { mutableStateOf(entry) }
    if (entry != null) lastEntry = entry
    val displayEntry = lastEntry ?: return
    XDialog(
        show = show,
        onDismissRequest = onDismiss,
    ) {
        XDialogTitle(text = sulogEntryTitle(displayEntry))
        Column {
            SelectionContainer(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = sulogEntryDetailText(displayEntry),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(android.R.string.ok),
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

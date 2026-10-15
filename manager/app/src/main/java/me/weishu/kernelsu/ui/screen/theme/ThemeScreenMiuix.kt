package me.weishu.kernelsu.ui.screen.theme

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 主题仓库的提交表单：发布后打开它，把直链填进去。 */
private const val SUBMIT_FORM_URL =
    "https://github.com/byBOOK10086/XECKernelPro-Themes/issues/new?template=upload-theme.yml"

/**
 * 主题屏的界面壳：顶栏 + 三个 Tab（商城 / 我的 / 编辑器）+ 发布成功后的对话框。
 *
 * 三个 Tab 各自实现在 [ThemeStoreTab] / [MyThemesTab] / [ThemeEditorTab] 里，
 * 这里只管"选哪个 Tab"和"发布成功后把直链交给用户"。
 *
 * 顶栏与 TabRow 的写法照抄 `ColorPaletteScreenMiuix`（同一个设计体系里已经跑通的形态），
 * 不自己发明控件。
 */
@Composable
fun ThemeScreenMiuix(
    state: ThemeUiState,
    actions: ThemeActions,
    publishLink: String?,
    onDismissPublish: () -> Unit,
    publishPicker: Boolean = false,
    onDismissPublishPicker: () -> Unit = {},
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val scrollBehavior = MiuixScrollBehavior()

    val tabs = listOf(
        stringResource(R.string.themes_tab_store),
        stringResource(R.string.themes_tab_mine),
        stringResource(R.string.themes_tab_editor),
    )
    val pagerState = rememberPagerState(initialPage = state.tab.coerceIn(0, 2), pageCount = { tabs.size })

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.themes_title),
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.close),
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            Column(Modifier.fillMaxSize()) {
                TabRow(
                    tabs = tabs,
                    selectedTabIndex = pagerState.currentPage,
                    onTabSelected = { index ->
                        actions.onSelectTab(index)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                )
                // 外部（例如「我的」里点编辑）切 Tab 时把 pager 同步过去。用 LaunchedEffect
                // 而不是在组合期直接 launch：组合可能被丢弃/重放，副作用必须挂在 effect 上。
                LaunchedEffect(state.tab) {
                    val target = state.tab.coerceIn(0, tabs.lastIndex)
                    if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true,
                ) { page ->
                    val padding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 4.dp,
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        bottom = innerPadding.calculateBottomPadding() +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    )
                    when (page) {
                        0 -> ThemeStoreTab(
                            state = state,
                            actions = actions,
                            innerPadding = padding,
                            backdrop = backdrop,
                        )

                        1 -> MyThemesTab(
                            state = state,
                            actions = actions,
                            innerPadding = padding,
                            backdrop = backdrop,
                        )

                        else -> ThemeEditorTab(
                            state = state,
                            actions = actions,
                            innerPadding = padding,
                            backdrop = backdrop,
                        )
                    }
                }
            }
        }
    }

    // 发布前的"选一个主题"对话框：入口在商城页顶部（也仍然在「我的」每张卡片上）。
    XDialog(show = publishPicker, onDismissRequest = onDismissPublishPicker) {
        XDialogTitle(
            text = stringResource(R.string.themes_publish_picker_title),
            summary = stringResource(R.string.themes_publish_picker_hint),
        )
        if (state.mine.isEmpty()) {
            Text(
                text = stringResource(R.string.themes_publish_picker_empty),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Xc.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                state.mine.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.spec.name,
                                fontSize = 15.sp,
                                color = Xc.colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // 版本与 id 一起显示：重名主题靠 id 区分，用户提交 issue 时也要填它。
                                text = "v" + entry.spec.version + "  ·  " + entry.spec.id,
                                fontSize = 12.sp,
                                color = Xc.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.themes_publish),
                            onClick = {
                                onDismissPublishPicker()
                                actions.onPublish(entry.spec.id)
                            },
                            enabled = !state.busy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.close),
                onClick = onDismissPublishPicker,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }

    XDialog(show = publishLink != null, onDismissRequest = onDismissPublish) {
        XDialogTitle(
            text = stringResource(R.string.themes_publish),
            summary = stringResource(R.string.themes_publish_pending),
        )
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            Text(
                text = publishLink.orEmpty(),
                fontSize = 12.sp,
                color = Xc.colors.textSecondary,
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.themes_open_form),
                onClick = {
                    openUrl(context, SUBMIT_FORM_URL)
                    onDismissPublish()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = stringResource(R.string.close),
                onClick = onDismissPublish,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}

/** 打开外部链接（提交表单）：失败就静默——用户还能自己复制那条直链。 */
internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

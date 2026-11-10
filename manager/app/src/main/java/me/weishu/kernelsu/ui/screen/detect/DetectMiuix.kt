package me.weishu.kernelsu.ui.screen.detect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.kit.XcCard
import me.weishu.kernelsu.ui.design.kit.XcHero
import me.weishu.kernelsu.ui.design.kit.XcListRow
import me.weishu.kernelsu.ui.design.kit.XcSectionTitle
import me.weishu.kernelsu.ui.design.kit.XcTextAction
import me.weishu.kernelsu.ui.design.kit.XcTone
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcSpace
import me.weishu.kernelsu.ui.design.token.XcType
import me.weishu.kernelsu.ui.screen.home.XecScore
import me.weishu.kernelsu.ui.screen.home.XecScoreItemId
import me.weishu.kernelsu.ui.screen.home.XecScoreResult
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 检测页（2026-11-08 按 MIUI 8 原型重新立起来）。
 *
 * 这一页在 2026-10-26 的重构里被下线过，当时把它的能力并进了工具箱；现在按原型恢复成
 * 独立一页，内容**全部来自已有的真实探针**，不是新写的假检查：
 * `XecScore.evaluate(state)` 的九项就是 Root / SELinux / SUSFS / TEE 注入 / 守护进程 /
 * LKM / UAPI 兼容 / 构建渠道 / 运行模式，每一项的判据都在 [XecScore] 里可读。
 *
 * 页面刻意不引入"重新检测"以外的新语义：刷新调的就是首页那一个 `HomeViewModel.refresh()`，
 * 两页读同一份状态，不会出现"首页说正常、检测页说异常"。
 */
@Composable
fun DetectPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<HomeViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 与首页一致：第一次滑到这一页时刷新一次，避免拿着旧的探测结果给人下结论。
    var hasActivated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasActivated) {
            hasActivated = true
            viewModel.refresh()
        }
    }
    val result = remember(state) { XecScore.evaluate(state) }

    DetectContent(
        result = result,
        onRefresh = { viewModel.refresh() },
        bottomInnerPadding = bottomInnerPadding,
    )
}

@Composable
private fun DetectContent(
    result: XecScoreResult,
    onRefresh: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.home8_detect),
                titleColor = Xc.colors.text,
                largeTitleColor = Xc.colors.text,
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = XcSpace.pageHorizontal),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // 「减量 XEC」：hero 用左文 + 右状态药丸，不再用自绘的 page heading。
                    XcHero(
                        kicker = stringResource(R.string.detect_kicker),
                        title = stringResource(R.string.home8_detect),
                        subtitle = stringResource(R.string.detect_desc),
                        status = result.rank.name,
                        statusTone = if (result.isHealthy) XcTone.Success else XcTone.Amber,
                    )

                    ScorePanel(result = result)

                    XcSectionTitle(
                        title = stringResource(R.string.detect_checks),
                        action = {
                            XcTextAction(
                                text = stringResource(R.string.detect_run),
                                onClick = onRefresh,
                            )
                        },
                    )

                    XcCard(contentPadding = 0.dp) {
                        Column {
                            result.items.forEachIndexed { index, item ->
                                if (index > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Xc.colors.glassRim),
                                    )
                                }
                                XcListRow(
                                    title = stringResource(checkLabel(item.id)),
                                    subtitle = stringResource(
                                        R.string.detect_item_weight,
                                        item.earned,
                                        item.max,
                                    ),
                                    symbol = if (item.ok) "✓" else "!",
                                    tone = if (item.ok) XcTone.Accent else XcTone.Danger,
                                    value = if (item.ok) {
                                        stringResource(R.string.detect_tag_pass)
                                    } else {
                                        stringResource(R.string.detect_tag_fail)
                                    },
                                    valueTone = if (item.ok) XcTone.Success else XcTone.Danger,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                        }
                    }

                    Spacer(
                        Modifier.height(
                            bottomInnerPadding + 12.dp + if (!Natives.isFullFeatured()) {
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            } else {
                                0.dp
                            }
                        )
                    )
                }
            }
        }
    }
}

/**
 * 结果面板：左边大号分数，右边一句结论。
 *
 * 分数用强调色而不是语义色：它不是"错误提示"，语义色留给下面的检查项标签，
 * 否则整块面板会同时出现橙和绿两种强调，反而看不出重点。
 */
@Composable
private fun ScorePanel(result: XecScoreResult) {
    XcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.total.toString(),
                fontSize = 38.sp,
                fontWeight = FontWeight(650),
                color = Xc.colors.accent,
            )
            Text(
                text = " / 100",
                fontSize = 13.sp,
                color = Xc.colors.textMuted,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        if (result.isHealthy) R.string.detect_all_ok else R.string.detect_some_failed
                    ),
                    fontSize = XcType.body,
                    fontWeight = FontWeight(620),
                    color = Xc.colors.text,
                )
                Text(
                    text = stringResource(
                        R.string.detect_detail,
                        result.passed,
                        result.items.size,
                        result.rank.name,
                    ),
                    fontSize = XcType.caption,
                    color = Xc.colors.textSecondary,
                )
            }
        }
    }
}

private fun checkLabel(id: XecScoreItemId): Int = when (id) {
    XecScoreItemId.ROOT -> R.string.xec_score_check_root
    XecScoreItemId.SELINUX -> R.string.xec_score_check_selinux
    XecScoreItemId.SUSFS -> R.string.xec_score_check_susfs
    XecScoreItemId.TEE -> R.string.xec_score_check_tee
    XecScoreItemId.DAEMON -> R.string.xec_score_check_daemon
    XecScoreItemId.LKM -> R.string.xec_score_check_lkm
    XecScoreItemId.COMPAT -> R.string.xec_score_check_compat
    XecScoreItemId.STABLE -> R.string.xec_score_check_stable
    XecScoreItemId.MODE -> R.string.xec_score_check_mode
}

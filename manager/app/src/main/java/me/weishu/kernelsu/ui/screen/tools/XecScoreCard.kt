package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.motion.XcMotion
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.XecScore
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

/**
 * XEC SCORE 卡（扁平方案 A，2026-11-09）。
 *
 * 雷达图换成"大数字 + 九格"：雷达在扁平语言里是纯装饰，而九格能一眼数出
 * 九项里过了几项（格子的颜色只分"过 / 没过"两档，不引入第二种语义）。
 *
 * 右侧三行仍是真实探针（Root / 运行模式 / 守护进程），不是设计稿里的占位数字。
 * 数据仍然只来自 [XecScore] 一处定义，这里不新造评分逻辑。
 */
@Composable
fun XecScoreCard(
    state: HomeUiState,
    onClick: () -> Unit,
) {
    val result = XecScore.evaluate(state)
    // 整块仪表加入场动画：数字从 0 数上去、九格逐格点亮。
    val reveal = remember { Animatable(0f) }
    // Xc.colors 是 @Composable，只能在这里读一次；LaunchedEffect 的 lambda 不是
    // composable 上下文，在里面读会直接编译失败（2026-11-09 CI 抓到过一次）。
    val dark = Xc.colors.isDark
    LaunchedEffect(result.total) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, XcMotion.reveal(dark))
    }
    val shownScore by animateIntAsState(
        targetValue = (result.total * reveal.value).roundToInt(),
        animationSpec = XcMotion.trace(),
        label = "score-count",
    )
    val passed = result.passed
    val total = result.items.size
    val healthy = result.isHealthy

    Xc8Panel {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 17.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "XEC SCORE",
                    style = Xc.typography.micro,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                RankBadge(rank = result.rank.name, healthy = healthy)
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = shownScore.toString(),
                    fontSize = 32.sp,
                    lineHeight = 62.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-3).sp,
                    color = Xc.colors.text,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "/ 100",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
            }
            Text(
                // 审计 P1（2026-11-09）：原来 `healthy` 只要 ≥75 就写成"九项全部通过"，
                // 而且分母错用项数（显示成 100 / 9）。改成按实际通过项说话。
                text = if (total > 0 && passed == total) {
                    "全部 $total 项通过"
                } else {
                    "$passed / $total 项通过，点开看明细"
                },
                fontSize = 13.sp,
                color = Xc.colors.textSecondary,
                modifier = Modifier.padding(top = 9.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(1.dp)
                    .background(Xc.colors.glassRim),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 九格：每格对应一项，过的用墨色、没过的留描边色，一眼数得清。
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(3) { col ->
                                val index = row * 3 + col
                                val item = result.items.getOrNull(index)
                                val ok = item != null && item.max > 0 && item.earned >= item.max
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(
                                            color = when {
                                                item == null -> Xc.colors.glassRim
                                                ok -> Xc.colors.text
                                                else -> Xc.colors.glassRim
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ScoreMetric(
                        label = "Root",
                        value = if (state.isRootAvailable) "可用" else "未启用",
                    )
                    ScoreMetric(
                        label = "运行模式",
                        value = when {
                            state.lkmMode == true -> "LKM"
                            state.ksuVersion != null -> "内核态"
                            else -> "未安装"
                        },
                    )
                    ScoreMetric(
                        label = "守护进程",
                        value = if (state.isDaemonPresent) "在线" else "离线",
                    )
                }
            }
        }
    }
}

@Composable
private fun RankBadge(rank: String, healthy: Boolean) {
    val tint = if (healthy) Xc.colors.success else Xc.colors.warning
    Box(
        modifier = Modifier
            .border(1.dp, tint, Xc.shapes.pill)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = "$rank 级",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = tint,
        )
    }
}

@Composable
private fun ScoreMetric(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Xc.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Xc.colors.text,
        )
    }
}

package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
import me.weishu.kernelsu.ui.design.panel.Xc8Radar
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.XecScore
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

/**
 * XEC SCORE 卡（2026-11-09 从首页搬到工具箱）。
 *
 * 版式不变：左边雷达（九项各占一个方向）、右边一句话结论 + 三格关键指标。
 * 搬家的理由：首页是"我现在能不能用"的结论页，评分是"我该修哪一项"的诊断入口，
 * 两者放同一屏会让首页变成体检报告；诊断动作属于工具箱。
 *
 * 数据仍然只来自 [XecScore] 一处定义（`ui/screen/home/XecScore.kt`），这里不新造探针。
 * 点击行为由调用方决定：工具箱里是"关掉工具箱 → 跳到检测页看九项明细"。
 */
@Composable
fun XecScoreCard(
    state: HomeUiState,
    onClick: () -> Unit,
) {
    val result = XecScore.evaluate(state)
    // 整块仪表加入场动画：雷达从圆心长出来，分数从 0 数上去。
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
    Xc8Panel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Xc8Radar(
                values = result.items.map { if (it.max <= 0) 0f else it.earned.toFloat() / it.max },
                size = 92.dp,
                reveal = reveal.value,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = shownScore.toString(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Xc.colors.text,
                    )
                    Text(
                        text = result.rank.name,
                        fontSize = 9.sp,
                        color = Xc.colors.textMuted,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "XEC SCORE",
                    fontSize = 10.sp,
                    color = Xc.colors.textMuted,
                )
                Text(
                    text = "${result.rank.name} · ${result.passed}/${result.items.size} 项通过",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                )
                Text(
                    text = if (result.isHealthy) "环境完整，可以正常使用" else "有项目需要处理，点开看明细",
                    fontSize = 11.sp,
                    color = Xc.colors.textSecondary,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Xc.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 三格关键指标：与面板族同一套分隔语言（1px 线 + surface 底）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Xc.colors.surface),
        ) {
            XecScoreMetric(
                label = "Root",
                value = if (state.isRootAvailable) "可用" else "未启用",
                modifier = Modifier.weight(1f),
            )
            XecScoreMetric(
                label = "运行模式",
                value = when {
                    state.isMountState -> "挂载态"
                    state.lkmMode == true -> "LKM"
                    state.ksuVersion != null -> "内核态"
                    else -> "未安装"
                },
                modifier = Modifier.weight(1f),
            )
            XecScoreMetric(
                label = "守护进程",
                value = if (state.isDaemonPresent) "在线" else "离线",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun XecScoreMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(text = label, fontSize = 10.sp, color = Xc.colors.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.text,
        )
    }
}

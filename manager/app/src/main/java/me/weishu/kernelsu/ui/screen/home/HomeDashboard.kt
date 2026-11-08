package me.weishu.kernelsu.ui.screen.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Radar
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * XEC Console · 首页仪表台（2026-11-08）。
 *
 * 原版首页是「版本卡 + 一串信息行」竖着排，那是 KernelSU / Magisk 的形状。
 * 这里把**结论**提到最上面：左边一个评分环，右边一句话结论 + 三格关键指标，
 * 整块可点 → 进 XEC SCORE 页看九项明细。
 *
 * 数据全部来自已有的 [XecScore] 与 [HomeUiState]，不新造探针：
 * 分数怎么算的、九项是什么，仍然只在 [XecScore] 一处定义。
 */
@Composable
fun HomeDashboard(state: HomeUiState) {
    val result = XecScore.evaluate(state)
    val ringColor = when {
        result.total >= XecRank.A.min -> Xc.colors.accent
        result.total >= XecRank.B.min -> Xc.colors.warning
        else -> Xc.colors.danger
    }
    val track = Xc.colors.glassRim
    val pager = LocalMainPagerState.current
    Xc8Panel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { pager.animateToPage(3) }
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 2026-11-09：环 → **雷达**。九项各占一个方向，缺的那一角一眼可见，
            // 比"一个百分比环 + 一串行"更能表达"哪里掉了"。
            Xc8Radar(
                values = result.items.map { if (it.max <= 0) 0f else it.earned.toFloat() / it.max },
                size = 92.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.total.toString(),
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
        // 三格关键指标：一条 1px 线把它和上面的结论分开，与面板族同一套分隔语言。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Xc.colors.surface),
        ) {
            MetricCell(
                label = "Root",
                value = if (state.isRootAvailable) "可用" else "未启用",
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                label = "运行模式",
                value = when {
                    state.isMountState -> "挂载态"
                    state.lkmMode == true -> "LKM"
                    state.ksuVersion != null -> "内核态"
                    else -> "未安装"
                },
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                label = "守护进程",
                value = if (state.isDaemonPresent) "在线" else "离线",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 评分环。颜色由调用方算好再传进来：`Xc.colors` 是 @Composable，进不了 DrawScope。 */
@Composable
private fun ScoreRing(score: Int, ringColor: Color, trackColor: Color) {
    Box(
        modifier = Modifier.size(54.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(54.dp)) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * (score.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = score.toString(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Xc.colors.text,
        )
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
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

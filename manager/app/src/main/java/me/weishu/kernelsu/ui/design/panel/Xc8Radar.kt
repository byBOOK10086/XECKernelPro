package me.weishu.kernelsu.ui.design.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.design.token.Xc
import kotlin.math.cos
import kotlin.math.sin

/**
 * XEC SCORE 的**雷达视图**（2026-11-09）。
 *
 * 为什么做这个而不是又一个进度条：九项检查是一条一条的线性列表，看完要往下滚、
 * 还要自己在脑子里比对"哪一项拖了后腿"。雷达把九项摆成同一个圆上的九个方向，
 * 缺的那一角一眼就能看出来 —— 这是"仪表"该有的信息密度，也是这套界面里最该
 * 创新的一处：**用形状表达状态，而不是用文字和数字堆叠**。
 *
 * 实现约束（踩过的坑都写在这里）：
 *  - 所有颜色/尺寸在进入 `Canvas` 之前算好：`Xc.colors` 是 `@Composable`，
 *    在 DrawScope 里调用会直接编译失败。
 *  - 轴长按 `earned / max` 取比例，但保底 18%，否则没拿分的那一项会缩到圆心、
 *    整个图形塌掉，反而看不出"缺了哪一角"。
 */
@Composable
fun Xc8Radar(
    values: List<Float>,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    centerContent: @Composable () -> Unit = {},
) {
    val gridColor = Xc.colors.glassRim
    val spokeColor = Xc.colors.glassRim
    val fillColor = Xc.colors.accent.copy(alpha = if (Xc.colors.isDark) 0.30f else 0.18f)
    val strokeColor = Xc.colors.accent
    val safe = values.takeIf { it.size >= 3 } ?: listOf(0f, 0f, 0f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val radius = minOf(cx, cy) - 6.dp.toPx()
            drawRadarGrid(cx, cy, radius, safe.size, gridColor)
            drawRadarShape(cx, cy, radius, safe, fillColor, strokeColor)
        }
        centerContent()
    }
}

/** 三层同心多边形 + 从圆心射出的辐条。 */
private fun DrawScope.drawRadarGrid(
    cx: Float,
    cy: Float,
    radius: Float,
    count: Int,
    color: Color,
) {
    val step = (Math.PI * 2) / count
    // 网格：25% / 50% / 75% / 100%
    for (ring in 1..4) {
        val r = radius * (ring / 4f)
        val path = Path()
        for (i in 0 until count) {
            val a = -Math.PI / 2 + step * i
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = color, style = Stroke(width = 1f))
    }
    // 辐条
    for (i in 0 until count) {
        val a = -Math.PI / 2 + step * i
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat()),
            strokeWidth = 1f,
        )
    }
}

/** 数据多边形：按比例取长度，最低 18% 保底。 */
private fun DrawScope.drawRadarShape(
    cx: Float,
    cy: Float,
    radius: Float,
    values: List<Float>,
    fill: Color,
    stroke: Color,
) {
    val count = values.size
    val step = (Math.PI * 2) / count
    val path = Path()
    for (i in 0 until count) {
        val ratio = values[i].coerceIn(0f, 1f).coerceAtLeast(0.18f)
        val a = -Math.PI / 2 + step * i
        val x = cx + (radius * ratio * cos(a)).toFloat()
        val y = cy + (radius * ratio * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = fill)
    drawPath(path, color = stroke, style = Stroke(width = 2.dp.toPx()))
}

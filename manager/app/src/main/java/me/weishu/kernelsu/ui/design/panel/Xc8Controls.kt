package me.weishu.kernelsu.ui.design.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * MIUI 8 控件还原（2026-11-08）。
 *
 * 一套控件的样子不是"圆角多大"决定的，是**选中/未选中两档的对比方式**决定的。
 * MIUI 8 的规则很统一，这一层就是把它固定下来：
 *  * 未选中 = 1.5dp 灰色描边 + 透明填充；
 *  * 选中   = 12dp 方角/圆形实橙 + 白色对钩/白点（没有勾选动画，只有 120ms 换色）；
 *  * 轨道   = 3dp 圆头，未选中 `#E5E5E5`，选中实橙；
 *  * 文本域 = 1px 描边 + 3dp 圆角 + 8dp 内边距，光标是橙色；
 *  * 标签页 = 12sp 文字 + 选中橙色 + 2dp 橙色下划线，整条带 1px 底边。
 *
 * 底色一律走 `Xc.surface`：主题把"组件透明度"调低时，这些控件跟着一起变透明，
 * 不需要各自再写一遍透明度判断。
 */

/** MIUI 8 复选框：18dp 方角，选中实橙 + 白钩。 */
@Composable
fun Xc8Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill by animateColorAsState(
        targetValue = if (checked) Xc.colors.accent else Color.Transparent,
        animationSpec = tween(Xc8Motion.press),
        label = "xc8CheckboxFill",
    )
    val rim by animateColorAsState(
        targetValue = if (checked) Xc.colors.accent else Xc.colors.textMuted.copy(alpha = 0.55f),
        animationSpec = tween(Xc8Motion.press),
        label = "xc8CheckboxRim",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(Xc.shapes.xs)
            .background(fill)
            .border(1.5.dp, rim, Xc.shapes.xs)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** MIUI 8 单选框：18dp 圆环，选中实橙 + 白点。 */
@Composable
fun Xc8Radio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill by animateColorAsState(
        targetValue = if (selected) Xc.colors.accent else Color.Transparent,
        animationSpec = tween(Xc8Motion.press),
        label = "xc8RadioFill",
    )
    val rim by animateColorAsState(
        targetValue = if (selected) Xc.colors.accent else Xc.colors.textMuted.copy(alpha = 0.55f),
        animationSpec = tween(Xc8Motion.press),
        label = "xc8RadioRim",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, rim, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * MIUI 8 拖动条：3dp 圆头轨道 + 14dp 橙色圆钮。
 *
 * 没有用 Material 的 `Slider`：它的高光、轨道厚度、thumb 尺寸是一整套 Material 语言，
 * 塞进 MIUI 8 的面板里会明显是外来件，而可定制的槽位在不同 compose 版本上不一致。
 * 这里自己算比例，行为等价（拖动中实时回调，松手不再补发）。
 */
@Composable
fun Xc8Seekbar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val clamped = value.coerceIn(0f, 1f)
    val thumbRadius = with(density) { 7.dp.toPx() }
    val trackHeight = with(density) { 3.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    if (widthPx > 0) onValueChange((offset.x / widthPx).coerceIn(0f, 1f))
                }
            }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(enabled, widthPx) {
                        detectDragGestures { change, _ ->
                            if (widthPx > 0) {
                                onValueChange((change.position.x / widthPx).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            val centerY = size.height / 2f
            drawLine(
                color = if (Xc.colors.isDark) Color(0xFF3A3A41) else Color(0xFFE5E5E5),
                start = Offset(thumbRadius, centerY),
                end = Offset(size.width - thumbRadius, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round,
            )
            val activeEnd = thumbRadius + (size.width - thumbRadius * 2f) * clamped
            drawLine(
                color = if (enabled) Xc.colors.accent else Xc.colors.accent.copy(alpha = 0.4f),
                start = Offset(thumbRadius, centerY),
                end = Offset(activeEnd, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(activeEnd, centerY),
            )
            drawCircle(
                color = if (enabled) Xc.colors.accent else Xc.colors.accent.copy(alpha = 0.4f),
                radius = thumbRadius,
                center = Offset(activeEnd, centerY),
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )
        }
    }
}

/** MIUI 8 进度条：3dp 圆头。 */
@Composable
fun Xc8Progress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(3.dp)) {
        drawLine(
            color = if (Xc.colors.isDark) Color(0xFF3A3A41) else Color(0xFFE5E5E5),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        if (clamped > 0f) {
            drawLine(
                color = Xc.colors.accent,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width * clamped, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * MIUI 8 加载环：一段 300° 的橙色圆弧匀速旋转。
 *
 * 用 `Canvas` 而不是 `CircularProgressIndicator`：后者的转速、线宽、留白都是 Material
 * 的节奏，和这套控件放一起会"转得不像同一个系统"。
 */
@Composable
fun Xc8Loading(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val transition = rememberInfiniteTransition(label = "xc8Loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "xc8LoadingAngle",
    )
    Canvas(
        modifier = modifier
            .size(size)
            .rotate(angle),
    ) {
        val stroke = this.size.minDimension * 0.12f
        drawArc(
            color = Xc.colors.accent,
            startAngle = 0f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** MIUI 8 标签页：文字 + 选中下划线，整条 1px 底边。 */
@Composable
fun Xc8Tabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rim = Xc.colors.glassRim
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = rim,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val color by animateColorAsState(
                targetValue = if (selected) Xc.colors.accent else Xc.colors.textSecondary,
                animationSpec = tween(Xc8Motion.control),
                label = "xc8Tab$index",
            )
            val interaction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = label, fontSize = 13.sp, color = color, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(if (selected) Xc.colors.accent else Color.Transparent),
                )
            }
        }
    }
}

/** MIUI 8 计数角标。 */
@Composable
fun Xc8Badge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(Xc.shapes.pill)
            .background(Xc.colors.accent)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** MIUI 8 文本域：1px 描边 + 3dp 圆角 + 橙色光标。 */
@Composable
fun Xc8TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = Xc.shapes.sm
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(shape)
            .background(Xc.surface)
            .border(1.dp, Xc.colors.glassRim, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = Xc.colors.text,
            fontSize = 14.sp,
        ),
        cursorBrush = SolidColor(Xc.colors.accent),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    fontSize = 14.sp,
                    color = Xc.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            inner()
        },
    )
}

/** MIUI 8 搜索框：灰底胶囊 + 放大镜。 */
@Composable
fun Xc8SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Xc.shapes.pill)
            .background(if (Xc.colors.isDark) Color(0xFF26262B) else Color(0xFFEFEFEF))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Xc.colors.textMuted,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Xc.colors.text, fontSize = 13.5.sp),
            cursorBrush = SolidColor(Xc.colors.accent),
            decorationBox = { inner ->
                if (query.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        fontSize = 13.5.sp,
                        color = Xc.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
    }
}

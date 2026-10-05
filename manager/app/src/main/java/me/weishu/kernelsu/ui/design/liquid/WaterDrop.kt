package me.weishu.kernelsu.ui.design.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.design.token.Xc

/**
 * 全局水滴开关。调试或低性能设备可以整体关掉，
 * 关掉后只剩按下缩放，不再有任何额外绘制。
 */
val LocalXWaterDropEnabled = staticCompositionLocalOf { true }

/**
 * XEC Fluid Glass · 水滴点击宿主。
 *
 * 把整个应用的内容包进来，就能让**所有**可点击元素自动带上水滴扩散，
 * 不需要去改任何一个既有控件——这是"全局所有可点击元素"这条要求
 * 唯一可行的落地方式：应用里有几百个可点区域，逐个加修饰符既改不完、
 * 也一定会漏。
 *
 * 实现上有两个必须守住的点：
 *
 * 1. **只读不吞**：挂在 `PointerEventPass.Initial` 上按下去就记录坐标，
 *    但从不 `consume()`。Initial 阶段是自顶向下派发，所以宿主一定先看到事件；
 *    不消费则子控件的点击、滚动、拖拽全部照旧。
 * 2. **画在最上层**：`drawWithContent` 先画内容再画水滴，
 *    所以水滴永远盖在界面之上，且不会被任何子控件的裁剪切掉。
 *
 * 两层波：主波立即扩散，副波滞后 [secondaryDelayMs] 再发一圈更淡的，
 * 制造"水面被点了一下"的层次，而不是一个单调变大的圆。
 */
@Composable
fun XDropletHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalXWaterDropEnabled.current,
    maxRadius: Dp = 72.dp,
    secondaryDelayMs: Long = 80L,
    content: @Composable () -> Unit,
) {
    val water = Xc.colors.water
    val ring = Xc.colors.accent
    val motion = Xc.motion

    val maxRadiusPx = with(LocalDensity.current) { maxRadius.toPx() }
    val droplets = remember { mutableStateListOf<Droplet>() }
    val scope = rememberCoroutineScope()

    fun splash(at: Offset) {
        val main = Droplet(at, maxRadiusPx, strength = 1f)
        droplets.add(main)
        scope.launch {
            try {
                main.progress.animateTo(1f, motion.waterDropSpec())
            } finally {
                droplets.remove(main)
            }
        }
        val echo = Droplet(at, maxRadiusPx * 0.72f, strength = 0.5f)
        droplets.add(echo)
        scope.launch {
            try {
                delay(secondaryDelayMs)
                echo.progress.animateTo(1f, motion.waterDropSpec())
            } finally {
                droplets.remove(echo)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(motion) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                // 只认「新按下」，不干预任何后续事件
                                event.changes.forEach { change ->
                                    if (change.changedToDownIgnoreConsumed()) {
                                        splash(change.position)
                                    }
                                }
                            }
                        }
                    }
                },
            )
            .drawWithContent {
                drawContent()
                for (index in droplets.indices) {
                    val droplet = droplets[index]
                    val progress = droplet.progress.value
                    if (progress <= 0f || progress >= 1f) continue
                    drawDroplet(
                        center = droplet.center,
                        progress = progress,
                        maxRadius = droplet.maxRadius,
                        water = water,
                        ring = ring,
                        strength = droplet.strength,
                    )
                }
            },
    ) {
        content()
    }
}

/**
 * 单滴水。进度由 [Animatable] 驱动，绘制时读 `progress.value`，
 * Compose 只会失效重绘、不会触发重组。
 */
private class Droplet(
    val center: Offset,
    val maxRadius: Float,
    val strength: Float,
) {
    val progress = Animatable(0f)
}

/**
 * 画一滴水：一层主体水膜 + 一圈贴着扩散边缘的亮环。
 *
 * 亮环是关键。只有主体的话看起来像"一个变淡的圆"，
 * 补上边缘高光之后才会被读成"水面上的波"。
 * 全部使用 Compose 自带的径向渐变，不依赖 AGSL，
 * 因此 API 31 的老设备上与旗舰机上看到的是同一个东西。
 */
private fun DrawScope.drawDroplet(
    center: Offset,
    progress: Float,
    maxRadius: Float,
    water: Color,
    ring: Color,
    strength: Float,
) {
    val radius = (maxRadius * progress).coerceAtLeast(0.5f)
    val fade = 1f - progress

    val bodyAlpha = fade * fade * water.alpha * strength
    if (bodyAlpha > 0.002f) {
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to water.copy(alpha = bodyAlpha),
                    0.55f to water.copy(alpha = bodyAlpha * 0.55f),
                    1f to Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }

    val ringAlpha = fade * fade * 0.85f * strength
    if (ringAlpha > 0.002f) {
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.72f to Color.Transparent,
                    0.88f to ring.copy(alpha = ringAlpha),
                    1f to Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/**
 * 带水滴反馈的点击：按下收缩 + 交给 [XDropletHost] 扩散。
 *
 * 水滴本体由宿主编一绘制，这里只负责"手感"（缩放）与语义（无障碍、点击标签、长按），
 * 两者分开的好处是：既有控件可以直接换成这个修饰符拿到同样的反馈，
 * 忘了换的控件也照样有水滴。
 *
 * 用 `combinedClickable` 而非 `clickable`，是为了让"长了按"的控件
 * （模块列表的操作按钮等）也能走同一条路径，不必各自 remember 交互源。
 */
@Composable
fun Modifier.xWaterDropClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .xDropletPressScale(interactionSource, enabled)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * 按下时整体轻微收缩。
 *
 * 幅度取 [me.weishu.kernelsu.ui.design.token.XcMotion.pressScale]（0.965），
 * 刻意做得很小：玻璃是硬的，收缩太多会像橡胶。
 */
@Composable
fun Modifier.xDropletPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    val motion = Xc.motion
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) motion.pressScale else 1f,
        animationSpec = motion.pressSpec(),
        label = "xDropletPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

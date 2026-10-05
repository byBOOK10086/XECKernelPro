package me.weishu.kernelsu.ui.design.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction

private const val HOVER_ALPHA = 0.05f
private const val FOCUS_ALPHA = 0.07f
private const val PRESS_ALPHA = 0.10f
private const val HOLD_DOWN_ALPHA = 0.10f

/** 底部比顶部淡这一档，是"光从上方压进玻璃"的暗示。 */
private const val GRADIENT_FALLOFF = 0.72f

private val PressInSpring: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 1200f)
private val PressOutSpring: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 420f)
private val HoverSpring: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 280f)

/**
 * XEC Fluid Glass · 圆角按压高光。
 *
 * 这是整个"每个方形框角要圆滑"要求里最要紧的一块补丁。
 *
 * miuix 自带的 `MiuixIndication` 在按下时画的是 `drawRect`——也就是一个
 * **贴满控件外接矩形的方块**，它既不看容器圆角、也不做任何裁剪。结果就是：
 * 只要某个 `clickable` 没有显式传自己的 `indication`，按下的一瞬间四角就会
 * 冒出直角，圆角越大的卡片上越刺眼。全库几百个可点区域里绝大多数都没有传，
 * 所以这个直角高光实际上无处不在。
 *
 * 逐个去给每个控件补 `indication` 是改不完的。真正可行的做法是**换掉
 * [androidx.compose.foundation.LocalIndication] 本身**——它是 miuix 那些
 * 控件（Button、Card、NavigationBarItem、NavigationRailItem、ListItem、
 * Surface……）按下时读取的默认值，换掉之后所有既有控件一次性变成圆角高光。
 *
 * 三处与上游不同的地方：
 *
 * 1. **画圆角而不是方块**：`drawRoundRect`，半径还会按控件实际短边夹取
 *    （见 [clampedRadius]），所以矮胖的药丸按钮会自动变成整圆端，不会出现
 *    "半径比控件还大"的破形。
 * 2. **上下微渐变**：顶部略强、底部略弱，模拟光从上压入玻璃，
 *    平铺纯色在大面积卡片上会显得像一块塑料贴纸。
 * 3. **XEC 弹簧**：按下快、回弹慢，和 [WaterDrop.xWaterDropClick] 的缩放
 *    共用一套手感。
 *
 * @param color 高光色。取 [me.weishu.kernelsu.ui.design.token.XcColors.text]，
 *   深色下是近白、浅色下是近黑，所以深浅两套主题都读作"提亮"而不是"染色"。
 * @param radius 圆角半径，取
 *   [me.weishu.kernelsu.ui.design.token.XcShapes.pressRadius]。
 */
@Immutable
class XcIndication(
    private val color: Color,
    private val radius: Dp = 16.dp,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        XcIndicationInstance(interactionSource, color, radius)

    override fun hashCode(): Int = 31 * color.hashCode() + radius.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XcIndication) return false
        return color == other.color && radius == other.radius
    }

    private class XcIndicationInstance(
        private val interactionSource: InteractionSource,
        private val color: Color,
        private val radius: Dp,
    ) : Modifier.Node(),
        DrawModifierNode {

        private var isPressed = false
        private var isHovered = false
        private var isFocused = false
        private var isHoldDown = false

        private val animatedAlpha = Animatable(0f)
        private var pressedAnimation: Job? = null
        private var restingAnimation: Job? = null

        private fun targetAlpha(): Float {
            var target = 0f
            if (isHovered) target += HOVER_ALPHA
            if (isFocused) target += FOCUS_ALPHA
            if (isPressed && !isHoldDown) target += PRESS_ALPHA
            if (isHoldDown) target += HOLD_DOWN_ALPHA
            return target
        }

        private fun animateOverlay(spring: SpringSpec<Float>, fromPressRelease: Boolean) {
            val target = targetAlpha()
            if (fromPressRelease || target == 0f) {
                // 松手时先让"按下"那段跑完再收，否则快速点击会看到高光闪断。
                restingAnimation?.cancel()
                restingAnimation = coroutineScope.launch {
                    pressedAnimation?.join()
                    animatedAlpha.animateTo(targetValue = target, animationSpec = spring)
                }
            } else {
                pressedAnimation?.cancel()
                restingAnimation?.cancel()
                pressedAnimation = coroutineScope.launch {
                    animatedAlpha.animateTo(targetValue = target, animationSpec = spring)
                }
            }
        }

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val previousPressed = isPressed
                    val previousHovered = isHovered
                    val previousFocused = isFocused
                    val previousHoldDown = isHoldDown

                    when (interaction) {
                        is PressInteraction.Press -> isPressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
                        is HoverInteraction.Enter -> isHovered = true
                        is HoverInteraction.Exit -> isHovered = false
                        is FocusInteraction.Focus -> isFocused = true
                        is FocusInteraction.Unfocus -> isFocused = false
                        is HoldDownInteraction.HoldDown -> isHoldDown = true
                        is HoldDownInteraction.Release -> isHoldDown = false
                        else -> return@collect
                    }

                    val spring = when {
                        previousPressed != isPressed -> if (isPressed) PressInSpring else PressOutSpring
                        previousHoldDown != isHoldDown -> if (isHoldDown) PressInSpring else PressOutSpring
                        previousHovered != isHovered -> HoverSpring
                        previousFocused != isFocused -> HoverSpring
                        else -> return@collect
                    }
                    val fromPressRelease =
                        (previousPressed && !isPressed) || (previousHoldDown && !isHoldDown)
                    animateOverlay(spring, fromPressRelease)
                }
            }
        }

        /**
         * 半径按短边夹取到一半。
         *
         * 这一步是"圆滑"能成立的前提：`32.dp` 的圆角放在一个 32dp 高的行上
         * 本来会画歪（Compose 会把超过一半的半径硬压回去，但压在两端会露出
         * 直边），夹到 `short / 2` 之后矮控件正好收成整圆端，和它的外形一致。
         */
        private fun DrawScope.clampedRadius(): Float =
            radius.toPx().coerceAtMost(size.minDimension / 2f).coerceAtLeast(0f)

        override fun ContentDrawScope.draw() {
            drawContent()
            val alpha = animatedAlpha.value
            if (alpha <= 0.002f) return

            val corner = clampedRadius()
            // 控件短边不足 1px 时没有可画的圆角，宁可不出高光也不出直角。
            if (corner <= 0.5f) return

            val base = color.copy(alpha = color.alpha * alpha)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to base,
                    1f to base.copy(alpha = base.alpha * GRADIENT_FALLOFF),
                ),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(corner, corner),
            )

            // 贴着圆角边缘补一圈同色细线：渐变会把高光底部压淡，
            // 描边让高光的轮廓在淡色玻璃上依然闭合、不发虚。
            drawRoundRect(
                color = base,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(corner, corner),
                alpha = 0.35f,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

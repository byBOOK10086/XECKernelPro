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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction

private const val HOVER_ALPHA = 0.05f
private const val FOCUS_ALPHA = 0.07f

/**
 * 基础"按压光"的强度。
 *
 * 这一层是旧的整块高光，现在**主动让位**给侧角凹陷：它只剩三分之一的量，
 * 作用是给整个可点面一个极轻的底色变化，让凹陷区域和它周围不至于完全脱开。
 * 整块高光如果继续按旧值画，凹陷就会被自己的底光糊掉，"哪一侧陷下去"看不见。
 */
private const val PRESS_ALPHA = 0.035f
private const val HOLD_DOWN_ALPHA = 0.05f

/** 底部比顶部淡这一档，是"光从上方压进玻璃"的暗示。 */
private const val GRADIENT_FALLOFF = 0.72f

/** 凹陷最深处相对控件短边的占比，再夹到一个手感合适的区间。 */
private const val DENT_RATIO = 0.62f
private val DENT_MIN = 30.dp
private val DENT_MAX = 108.dp

/** 判定"按到边上"的归一化阈值：小于它算贴边，大于 1-它算贴另一侧。 */
private const val EDGE_BAND = 0.34f

/**
 * 按下：快而稳（几乎不过冲），凹陷"咔"一下就位。
 * 松手：欠阻尼，回弹时越过原位——这就是"弹回"。
 * 展示层（不可点的内容区）另用一套：力度更小、回弹更大（见 [BODY_RELEASE_SPRING]）。
 */
private val PressInSpring: SpringSpec<Float> = spring(dampingRatio = 0.92f, stiffness = 1400f)
private val PressOutSpring: SpringSpec<Float> = spring(dampingRatio = 0.50f, stiffness = 520f)
private val HoverSpring: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 280f)
private val BodyPressSpring: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 300f)
private val BodyReleaseSpring: SpringSpec<Float> = spring(dampingRatio = 0.30f, stiffness = 240f)

/**
 * XEC Fluid Glass · 侧角凹陷按压反馈。
 *
 * 这是全应用唯一的下发点：miuix 的 Button / Card / NavigationBarItem / ListItem /
 * Surface 按下时都读 [androidx.compose.foundation.LocalIndication]，把它换掉，
 * 几百个可点区域一次性拿到同一套手感，不必逐个控件补 `indication`。
 *
 * ## 与旧版的关系（旧版只画一块圆角高光）
 *
 * 旧版解决的问题是"按下瞬间四角冒直角"——它画 `drawRoundRect` 而不是 `drawRect`，
 * 这部分行为**原样保留**。新版在此基础上加了用户要的"活动凹陷"，并且刻意避开
 * 两种做错的方式：
 *
 * 1. **不是一个坑**：没有用径向渐变在控件正中压一个圆。凹陷的**锚点由按压位置
 *    决定**——按在左上就往左上陷，按在右中就在右侧中段陷，按在底边就往下陷。
 *    锚点落在控件边界上，径向瓣被圆角路径裁掉一半，读起来才是"这一侧被按进去了"。
 * 2. **不是整个按钮一起陷**：控件本体没有任何缩放，也没有整块的暗化。整块高光
 *    反而被压到 [PRESS_ALPHA]（旧值的 1/3），让"陷下去的那一块"成为唯一的视觉
 *    主体。整块缩放从手感上更像橡胶，而不是玻璃。
 *
 * ## 三层结构（"不同部分动效不同"的落点）
 *
 * - **凹陷层**：贴着按压侧角的黑色阴影（玻璃被压进去的那一侧背光）+ 外圈一道
 *   白色亮环（弯折的玻璃把光折回来）。按得快、弹得脆，欠阻尼回弹。
 * - **展示层**：不可点击的内容区只有极弱的一层整体明暗，而且**回弹比按下大**——
 *   松手时先给一个反向位移再收（[BodyReleaseSpring] 阻尼 0.30），玻璃"抖回来"
 *   的幅度明显大于它被按进去的幅度。力度小、回弹大，正是这两者的分界。
 * - **基础高光**：旧的整块圆角高光，只留一点点垫底。
 *
 * 全程只用 Compose 自带的 Brush / Path，不依赖 AGSL，所以 API 31 的老设备与
 * 旗舰机上看到的是同一个东西。
 *
 * @param color 基础高光色（[me.weishu.kernelsu.ui.design.token.XcColors.text]）。
 * @param radius 圆角半径（[me.weishu.kernelsu.ui.design.token.XcShapes.pressRadius]）。
 *   本节点拿不到控件的真实 `Shape`（Indication API 不提供），所以凹陷的裁切用的是
 *   同一个 radius，并按短边夹到一半。对 `md`（16dp）这类卡片正好一致；对更大的
 *   圆角（`lg`/`xl`/`bar`）则由**父容器自己的裁切**兜住——卡片/按钮都会 `clip(shape)`，
 *   这里的裁切只是第二道保护，避免在无裁切的裸 `clickable` 上渗出边界。
 * @param shadeColor 凹陷阴影色，默认纯黑（凹陷在任何主题下都读作阴影）。
 * @param sheenColor 折射亮环色，默认纯白。
 * @param shadeAlpha 阴影峰值透明度倍率，由调用方按明暗档给（深色档要更重才压得住
 *   黑液态玻璃；浅色档给多了会像一块脏斑）。
 * @param sheenAlpha 亮环峰值透明度倍率。
 * @param displayAlpha 展示层（不可点内容区）的力度，默认 0.35——比凹陷层小得多。
 */
@Immutable
class XcIndication(
    private val color: Color,
    private val radius: Dp = 16.dp,
    private val shadeColor: Color = Color.Black,
    private val sheenColor: Color = Color.White,
    private val shadeAlpha: Float = 0.26f,
    private val sheenAlpha: Float = 0.28f,
    private val displayAlpha: Float = 0.35f,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        XcIndicationInstance(
            interactionSource = interactionSource,
            color = color,
            radius = radius,
            shadeColor = shadeColor,
            sheenColor = sheenColor,
            shadeAlpha = shadeAlpha,
            sheenAlpha = sheenAlpha,
            displayAlpha = displayAlpha,
        )

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + shadeColor.hashCode()
        result = 31 * result + sheenColor.hashCode()
        result = 31 * result + shadeAlpha.hashCode()
        result = 31 * result + sheenAlpha.hashCode()
        result = 31 * result + displayAlpha.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XcIndication) return false
        return color == other.color &&
            radius == other.radius &&
            shadeColor == other.shadeColor &&
            sheenColor == other.sheenColor &&
            shadeAlpha == other.shadeAlpha &&
            sheenAlpha == other.sheenAlpha &&
            displayAlpha == other.displayAlpha
    }

    private class XcIndicationInstance(
        private val interactionSource: InteractionSource,
        private val color: Color,
        private val radius: Dp,
        private val shadeColor: Color,
        private val sheenColor: Color,
        private val shadeAlpha: Float,
        private val sheenAlpha: Float,
        private val displayAlpha: Float,
    ) : Modifier.Node(),
        DrawModifierNode {

        private var isPressed = false
        private var isHovered = false
        private var isFocused = false
        private var isHoldDown = false

        /** 按压点在控件局部坐标里的位置；[Offset.Unspecified] 表示还没按下过。 */
        private var pressPosition: Offset = Offset.Unspecified

        private val animatedAlpha = Animatable(0f)

        /** 凹陷深度 0..1（回弹时可能短暂为负，负值代表"越过原位"）。 */
        private val dent = Animatable(0f)

        /** 展示层位移，可正可负：正是它负责"力度小、回弹大"。 */
        private val body = Animatable(0f)

        private var pressedAnimation: Job? = null
        private var restingAnimation: Job? = null
        private var dentJob: Job? = null
        private var bodyJob: Job? = null

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

        /**
         * 凹陷层与展示层各自独立推进。
         *
         * 松手时展示层不是简单地回到 0，而是先被推到 `-display * 1.8`（反向"抖回来"）
         * 再用欠阻尼弹簧收回：这就是"不可点击的展示部分力度更小、回弹更大"。
         */
        private fun animateDent(pressed: Boolean) {
            dentJob?.cancel()
            bodyJob?.cancel()
            if (pressed) {
                dentJob = coroutineScope.launch {
                    dent.animateTo(1f, PressInSpring)
                }
                bodyJob = coroutineScope.launch {
                    body.animateTo(displayAlpha, BodyPressSpring)
                }
            } else {
                dentJob = coroutineScope.launch {
                    dent.animateTo(0f, PressOutSpring)
                }
                bodyJob = coroutineScope.launch {
                    body.snapTo(body.value.coerceAtMost(displayAlpha) * -1.8f)
                    body.animateTo(0f, BodyReleaseSpring)
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
                        is PressInteraction.Press -> {
                            isPressed = true
                            pressPosition = interaction.pressPosition
                        }

                        is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
                        is HoverInteraction.Enter -> isHovered = true
                        is HoverInteraction.Exit -> isHovered = false
                        is FocusInteraction.Focus -> isFocused = true
                        is FocusInteraction.Unfocus -> isFocused = false
                        is HoldDownInteraction.HoldDown -> isHoldDown = true
                        is HoldDownInteraction.Release -> isHoldDown = false
                        else -> return@collect
                    }

                    if (previousPressed != isPressed || previousHoldDown != isHoldDown) {
                        animateDent(isPressed || isHoldDown)
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

        /**
         * 凹陷锚点：由按压位置归一化后决定，落在控件边界上。
         *
         * 规则（"根据可点击的位置变化"）：
         * - 归一化坐标在 [EDGE_BAND] 之内的那一轴算"贴边"，锚点直接贴到那条边上；
         * - 两轴都贴边 → 锚点就是那个角（"凹陷一个侧角部分"）；
         * - 只有一轴贴边 → 锚点在那条边的中段，纵向/横向位置跟随手指；
         * - 两轴都在中段（按在控件中央）→ 取较近的那条边，锚点沿该边跟随手指。
         *
         * 返回 `null` 表示控件太小、画不出可辨认的凹陷，此时整层跳过。
         *
         * 注意这里是 `DrawScope` 的扩展：`size` 必须是绘制作用域里的 `Size`
         * （Float 尺寸），不能用 `Modifier.Node` 自己的 `size`（IntSize）——
         * 那样下面的 `when` 会推出 `Number`，`Offset(x, y)` 直接编译不过。
         */
        private fun DrawScope.dentCenter(): Offset? {
            val w = size.width
            val h = size.height
            if (w <= 1f || h <= 1f) return null

            val p = if (pressPosition == Offset.Unspecified) {
                Offset(w / 2f, h / 2f)
            } else {
                pressPosition
            }
            val nx = (p.x / w).coerceIn(0f, 1f)
            val ny = (p.y / h).coerceIn(0f, 1f)

            val atLeft = nx <= EDGE_BAND
            val atRight = nx >= 1f - EDGE_BAND
            val atTop = ny <= EDGE_BAND
            val atBottom = ny >= 1f - EDGE_BAND

            val x = when {
                atLeft -> 0f
                atRight -> w
                // 两轴都没贴边时按较近的一侧吸附，否则凹陷会飘在控件中央变成"一个坑"
                atTop || atBottom -> nx * w
                else -> if (nx < 0.5f) 0f else w
            }
            val y = when {
                atTop -> 0f
                atBottom -> h
                atLeft || atRight -> ny * h
                else -> if (ny < 0.5f) 0f else h
            }
            return Offset(x, y)
        }

        override fun ContentDrawScope.draw() {
            drawContent()

            val alpha = animatedAlpha.value
            val dentValue = dent.value
            val bodyValue = body.value

            val corner = clampedRadius()
            // 控件短边不足 1px 时没有可画的圆角，宁可不出高光也不出直角。
            if (corner <= 0.5f) return

            // 圆角路径：凹陷的径向瓣必须被它裁掉一半，才会落在"边界"上而不是
            // 糊在控件中间；不裁的话圆角外也会渗出阴影。
            val clip = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                        cornerRadius = CornerRadius(corner, corner),
                    ),
                )
            }

            val base = color.copy(alpha = color.alpha * alpha)

            // --- 基础高光（旧版行为，量已压到 1/3）---
            if (alpha > 0.002f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to base,
                        1f to base.copy(alpha = base.alpha * GRADIENT_FALLOFF),
                    ),
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    color = base,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(corner, corner),
                    alpha = 0.35f,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // --- 展示层：不可点击内容区的极弱整体明暗，回弹时可以反向（变亮）---
            if (bodyValue > 0.001f) {
                drawRect(color = shadeColor.copy(alpha = shadeAlpha * 0.35f * bodyValue))
            } else if (bodyValue < -0.001f) {
                drawRect(color = sheenColor.copy(alpha = sheenAlpha * 0.30f * -bodyValue))
            }

            if (dentValue <= 0.002f) {
                // 回弹越过原位（dent 为负）：玻璃弹出来的一瞬间在同一个锚点
                // 反向挂一层极淡的亮环。松手的"弹回"因此看得见，而不是只有
                // 阴影默默消失。
                if (dentValue >= -0.002f) return
                val center = dentCenter() ?: return
                val dentRadius = (size.minDimension * DENT_RATIO)
                    .coerceIn(DENT_MIN.toPx(), DENT_MAX.toPx())
                clipPath(clip) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.62f to Color.Transparent,
                                0.86f to sheenColor.copy(alpha = sheenAlpha * dentValue * -0.6f),
                                1f to Color.Transparent,
                            ),
                            center = center,
                            radius = dentRadius * 1.18f,
                        ),
                        radius = dentRadius * 1.18f,
                        center = center,
                    )
                }
                return
            }

            val center = dentCenter() ?: return
            val dentRadius = (size.minDimension * DENT_RATIO)
                .coerceIn(DENT_MIN.toPx(), DENT_MAX.toPx())

            clipPath(clip) {
                // 凹陷阴影：从锚点向内衰减，读作"这一侧被压进去、背光"。
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to shadeColor.copy(alpha = shadeAlpha * dentValue),
                            0.45f to shadeColor.copy(alpha = shadeAlpha * dentValue * 0.55f),
                            1f to Color.Transparent,
                        ),
                        center = center,
                        radius = dentRadius,
                    ),
                    radius = dentRadius,
                    center = center,
                )

                // 折射亮环：比阴影略大一圈，读作"弯折的玻璃把光折回来"。
                // 用菲涅尔式的窄环（0.72 之前全透）而不是实心圆，避免变成"一个坑"。
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.84f to sheenColor.copy(alpha = sheenAlpha * dentValue),
                            1f to Color.Transparent,
                        ),
                        center = center,
                        radius = dentRadius * 1.18f,
                    ),
                    radius = dentRadius * 1.18f,
                    center = center,
                )
            }
        }
    }
}

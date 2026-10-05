package me.weishu.kernelsu.ui.design.token

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * XEC Fluid Glass · 动效令牌。
 *
 * 水滴的两条曲线是整个设计的"手感"所在：
 * 快出不快收、末端拉长，才像水面被点了一下，
 * 而不是一个均匀变大的圆。
 */
@Immutable
class XcMotion {
    /** 水滴扩散时长。 */
    val waterDropDuration: Int = 620

    /** 水滴扩散缓动：前段快、后段长尾。 */
    val waterDropEasing: Easing = CubicBezierEasing(0.16f, 0.84f, 0.30f, 1.0f)

    /** 按下收缩的幅度。 */
    val pressScale: Float = 0.965f

    /** 按下 / 回弹时长。 */
    val pressDuration: Int = 320

    /** 状态切换（选中、展开）的时长。 */
    val stateDuration: Int = 220

    /** 页面切换弹簧，略带过冲。 */
    fun <T> pageSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 380f,
    )

    fun waterDropSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = waterDropDuration, easing = waterDropEasing)

    fun pressSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = pressDuration, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}

val XcMotionDefault = XcMotion()

val LocalXcMotion = staticCompositionLocalOf { XcMotionDefault }

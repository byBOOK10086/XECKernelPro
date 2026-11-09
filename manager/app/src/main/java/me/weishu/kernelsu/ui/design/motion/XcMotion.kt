package me.weishu.kernelsu.ui.design.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * XEC 动效令牌（2026-11-09）。
 *
 * 为什么要单独一份：这套 UI 已经有主题令牌（`Xc.colors` 的颜色、`XcThemeData` 的
 * 圆角/模糊/发光），但**时间**一直是散落各处的魔法数字 —— 轮盘 150ms、雷达无动画、
 * 卡片 spring 各自写。结果是同一个"展开"动作在不同页面快慢不一，换主题时颜色变了、
 * 手感却没变。
 *
 * 这里把动效收成四个语义档位，页面只准引用档位、不准写毫秒：
 *
 *  - [reveal]  展开/入场：带轻微过冲，回答"东西从哪来"；
 *  - [settle]  数值收敛（分数、进度）：不过冲，避免读数抖动；
 *  - [collapse] 收起/退场：比展开快、不过冲，退场不该被等；
 *  - [trace]   轨迹类（拖拽高亮、连线）：最短，跟手。
 *
 * [motionScale] 由主题深浅决定：亮色主题的过冲压低一点（大色块在浅底上过冲更晃眼），
 * 深色主题用完整手感。以后要做"省电/减少动画"开关，只需要改这一个数。
 */
object XcMotion {

    /** 统一的手感伸缩系数。亮色主题 0.85，深色 1.0。 */
    fun scaleFor(dark: Boolean): Float = if (dark) 1f else 0.85f

    /** 展开/入场：轻微过冲的弹簧。 */
    fun <T> reveal(dark: Boolean): AnimationSpec<T> = spring(
        dampingRatio = 0.72f / scaleFor(dark),
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 数值收敛：临界阻尼，不过冲 —— 分数不该先冲到 103 再回到 100。 */
    fun <T> settle(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** 收起/退场：短、无过冲。 */
    fun <T> collapse(): AnimationSpec<T> = tween(durationMillis = 150, easing = EASE_IN)

    /** 轨迹跟随：最短的一档（拖拽高亮、连线出现）。 */
    fun <T> trace(): AnimationSpec<T> = tween(durationMillis = 90, easing = EASE_OUT)

    /** 与 `easeOutBack` 同族的缓动，用于需要手写进度的场合（Canvas 涟漪等）。 */
    val OVERSHOOT: Easing = CubicBezierEasing(0.22f, 1.12f, 0.36f, 1f)

    private val EASE_OUT: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val EASE_IN: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

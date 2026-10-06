// XEC Clear Glass · 果冻过渡动画。
//
// 顶栏在展开（大标题）与收缩（紧凑栏）之间过渡时，像一块果冻一样先被压扁/拉长，
// 再以欠阻尼弹簧回弹到原位。形变源是栏体自身高度的变化速度：滚动折叠时高度逐帧
// 收缩 → 向下的挤压；重新展开时高度回升 → 纵向拉伸，松手后由弹簧晃回。
//
// 刻意不读 MiuixScrollBehavior 的内部状态：用 onSizeChanged 观察栏体实测高度，
// 对库的内部实现零依赖，搜索栏展开等任何"高度会变"的容器都能复用。

package me.weishu.kernelsu.ui.design.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 给容器挂上果冻形变：高度变化时按变化速度压扁/拉伸，欠阻尼弹簧回弹。
 *
 * 形变锚定在**顶部中心**（[TransformOrigin] (0.5, 0)）：顶栏折叠时从顶边往下压，
 * 视觉上是"被内容推着塌下去"，而不是向四周缩。
 *
 * @param sensitivity 每像素高度变化注入的形变量。越大晃得越明显。
 * @param maxSquish 形变量上限，防止极端滚动速度下过度拉伸。
 * @param widthFlex 横向伸缩比例（挤压时变宽、拉伸时变窄的幅度）。
 *   上限约束：顶栏左右各留 12dp 内缩，1.0 + maxSquish * widthFlex ≈ 1.077，
 *   展宽仍落在内缩余量内，不会顶出屏幕边缘。
 * @param heightFlex 纵向伸缩比例（挤压时变扁、拉伸时变高的幅度）。
 */
@Composable
fun Modifier.xJellyBar(
    sensitivity: Float = 0.045f,
    maxSquish: Float = 0.35f,
    widthFlex: Float = 0.22f,
    heightFlex: Float = 0.38f,
): Modifier {
    val squish = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 用 float 数组而不是 state：首帧测量值不需要参与组合，只在这一帧之后才生效
    val lastHeight = remember { floatArrayOf(0f) }
    val jellySpring = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }
    return this
        .onSizeChanged { size ->
            val h = size.height.toFloat()
            val prev = lastHeight[0]
            val delta = h - prev
            lastHeight[0] = h
            // 跳过首帧测量（0 → h 会在每次进页面时白晃一下），只响应真实的折叠/展开
            if (prev > 0f && h > 0f && abs(delta) > 0.5f) {
                scope.launch {
                    squish.snapTo((squish.value + delta * sensitivity).coerceIn(-maxSquish, maxSquish))
                    squish.animateTo(0f, jellySpring)
                }
            }
        }
        .graphicsLayer {
            val v = squish.value
            // 收缩（delta<0 → v<0）：scaleY<1 压扁、scaleX>1 变宽；展开反之
            scaleX = 1f - v * widthFlex
            scaleY = 1f + v * heightFlex
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
}

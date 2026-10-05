package me.weishu.kernelsu.ui.design.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * XEC Fluid Glass · 玻璃对话框。
 *
 * 刻意**不用**独立 Window 的 `Dialog`：`LayerBackdrop` 取的是当前窗口的图层，
 * 换一个 window 之后模糊与折射都会失效，只剩一块死色。
 * 所以这里是一个同窗口的全屏浮层，调用方把它放在根节点的最后一个子项即可。
 *
 * 入场的缩放从 0.92 起、用弹簧收尾，配上下方 [Xc] 的圆角，落点手感与水滴一致。
 *
 * 显隐由 [show] 受控：本组件**永远留在组合里**，靠 [MutableTransitionState]
 * 驱动进出场，退场动画才不会因为调用方把它从树上摘掉而永远看不到。
 *
 * 从平台 `Dialog` 换成同窗口浮层，返回键的接管要自己补回来——否则一次返回会
 * 直接 pop 掉整个路由，而对话框还留在屏幕上。这里统一按平台 `Dialog` 的语义
 * 处理：返回 = [onDismissRequest]。于是"点外面不关闭"的场景（传 `{}`）自然
 * 等价于"吞掉返回、什么都不做"，加载框正是要这个行为。
 *
 * @param show 为 `false` 且退场动画跑完之后，本组件不再绘制任何内容、也不再
 *   接管返回键，因此它是可以放心跟着页面一起挂载的。
 * @param onDismissRequest 点遮罩或按返回时回调；不传可用 `{}` 表示"不关闭"。
 * @param backdrop 根节点的 `rememberBlurBackdrop` 结果；`null` 时退化为不透明底 + 描边。
 *
 * 面板宽度上限 [maxWidth]，高度上限为**扣掉安全区之后**可用高度的 90%（且不超过
 * 640dp）；遮罩铺满整个窗口，面板则被收在挖孔与手势条之内。内容超出时在面板
 * 内部滚动，所以再长的说明文字也不会把按钮顶出屏幕。
 */
@Composable
fun XGlassDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.xl,
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show

    // 退场动画期间必须留在树上，跑完才整体撤掉。
    if (!visibleState.currentState && !visibleState.targetState) return

    // 放在绘制之前、并且和绘制共用同一个可见性判断：隐藏时不会被注册，
    // 所以不会出现"对话框早就没了、返回键还被吃掉"的情况。
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = true,
        onBackCompleted = onDismissRequest,
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 面板高度上限：遮罩是全屏的，这套约束里含着状态栏与导航栏，
        // 所以先把安全区减掉，再取九成——否则长文案会把玻璃顶进状态栏。
        val safeArea = WindowInsets.safeDrawing.asPaddingValues()
        val usableHeight = maxHeight - safeArea.calculateTopPadding() - safeArea.calculateBottomPadding()
        val panelMaxHeight = minOf(usableHeight * 0.9f, 640.dp)

        // 遮罩：点空白处关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Xc.colors.backdropScrim)
                .pointerInput(Unit) {
                    detectTapGestures { onDismissRequest() }
                },
        )

        // 安全区只约束面板、不约束遮罩：遮罩仍然铺满到屏幕边缘（否则状态栏
        // 那一条会亮着不被压暗），而面板永远落在挖孔与手势条之内。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(animationSpec = tween(durationMillis = 160)) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                ),
                exit = fadeOut(animationSpec = tween(durationMillis = 120)),
            ) {
                XGlassSurface(
                    backdrop = backdrop,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = maxWidth)
                        .heightIn(max = panelMaxHeight),
                    shape = shape,
                    tint = Xc.colors.glassTint,
                    blurRadius = 12.dp,
                    refraction = 26.dp,
                ) {
                    Column(
                        // 内边距在外、滚动在内：22dp 的呼吸位固定不动，超长内容只在
                        // 自己那块区域里滚，不会顶到圆角边上。
                        modifier = Modifier
                            .padding(22.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

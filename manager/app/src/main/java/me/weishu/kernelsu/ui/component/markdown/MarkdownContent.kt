package me.weishu.kernelsu.ui.component.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

@Composable
fun MarkdownContent(
    content: String,
    isMarkdown: Boolean,
) {
    var loaded by remember(content, isMarkdown) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "MarkdownContentAlpha",
    )
    val placeholderAlpha by animateFloatAsState(
        targetValue = if (loaded) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "MarkdownContentPlaceholderAlpha",
    )
    val containerColor = null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 300))
    ) {
        // 本组件的唯一调用点是 XDialogHost 的确认框，而确认框的正文本来就活在
        // XGlassDialog 的纵向滚动 Column 里（那一层传给子项的 maxHeight 是
        // Infinity）。此处再套一层 verticalScroll 就是同方向嵌套滚动，必崩：
        // IllegalStateException: Vertically scrollable component was measured with
        // an infinity maximum height constraints。WebView 自己是 wrapContentHeight、
        // 不滚动，高度交给外层滚即可。
        Box(
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
        ) {
            GithubMarkdown(
                content = content,
                isMarkdown = isMarkdown,
                onLoadingChange = { loaded = !it },
                containerColor = containerColor,
            )
        }
        if (placeholderAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .graphicsLayer { this.alpha = placeholderAlpha },
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator()
            }
        }
    }
}

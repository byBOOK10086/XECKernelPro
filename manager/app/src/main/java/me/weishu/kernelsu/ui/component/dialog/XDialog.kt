package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text

/**
 * `XDialog(...)` 向宿主登记的一份「自绘内容」显示请求。
 *
 * 与 [ConfirmDialogRegistration] 的区别：那一份的内容是"标题 + 正文 + 两个按钮"这种
 * 固定骨架，宿主能自己拼；这一份的内容只有调用方知道，所以整个 `content`
 * 由调用方提供，宿主只负责把它放进玻璃面板里。
 *
 * 三项都是 provider / 回调而不是快照值，是为了让这份登记**可以只建一次**：
 * 建一次就挂进宿主的列表，之后 `show` 怎么变都只是读一下最新值，不必反复增删。
 */
internal class CustomDialogRegistration(
    val isVisible: () -> Boolean,
    val maxWidth: () -> Dp,
    val onDismissRequest: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * 把一个自绘内容的对话框挂到根层玻璃宿主上。
 *
 * **为什么不是就地画**：[XGlassDialog] 内部是 `fillMaxSize` 的全屏浮层，就地画会被
 * 所在页面的子树裁成一小块（列表 `item` 里更是直接崩），所以绘制统一交给根层的
 * [XDialogHost]。调用方这边只登记一句"我要显示什么"，位置照旧——弹窗挂在
 * `Card` 里、挂在 `LazyColumn` 的 `item` 里都不影响。
 *
 * 因此**调用点的写法与原来的 `OverlayDialog` 完全一致**（`show` 照旧读自己 hold 的
 * `MutableState<Boolean>`），只有对话框自己那一层从"独立窗口"换成了"根层浮层"。
 *
 * @param show 是否可见。刻意收成普通 `Boolean`：宿主在自己的组合里读它，
 *   状态的归属与生命周期都留在调用方，宿主不必反向持有别人的状态。
 * @param onDismissRequest 点遮罩或按返回时回调。
 * @param maxWidth 玻璃面板的宽度上限，默认 420dp。
 */
@Composable
fun XDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalXDialogHost.current
    val currentShow by rememberUpdatedState(show)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentMaxWidth by rememberUpdatedState(maxWidth)
    val currentContent by rememberUpdatedState(content)

    val registration = remember(host) {
        CustomDialogRegistration(
            isVisible = { currentShow },
            maxWidth = { currentMaxWidth },
            onDismissRequest = { currentOnDismiss() },
            content = { currentContent(this) },
        )
    }

    DisposableEffect(host, registration) {
        host.customStates.add(registration)
        onDispose { host.customStates.remove(registration) }
    }
}

/**
 * 玻璃对话框的标题（可带一行副标题）。
 *
 * 宿主里的确认框也是同一套写法，抽出来是为了让所有对话框的标题**长得一样**：
 * 原来靠 `OverlayDialog(title = …, summary = …)` 统一，现在标题归各对话框自己画，
 * 没有一个共用件就会各写各的字号与颜色。
 */
@Composable
fun XDialogTitle(
    text: String,
    summary: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = Xc.colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = summary,
                color = Xc.colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

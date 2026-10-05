package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.markdown.MarkdownContent
import me.weishu.kernelsu.ui.design.glass.XGlassDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * XEC Fluid Glass · 全局对话框宿主。
 *
 * **为什么对话框要从"各页自己画"改成"根层统一画"**：
 *
 * 1. 玻璃必须取**同一个 window** 的图层，所以不能再走平台 `Dialog`
 *    （换 window 后 `LayerBackdrop` 失效，只剩一块死色）。
 * 2. 同窗口浮层又**不能留在页面自己那棵子树里**——`fillMaxSize` 会被父布局
 *    裁成一小块，还会实打实占掉布局空间（原来的 `WindowDialog` 是独立窗口，
 *    没有这个问题，换成同窗口浮层之后就必须重新安排位置）。
 *
 * 于是把显示请求收敛到这里的 [XDialogHostState]：各页仍然只调
 * `rememberLoadingDialog()` / `rememberConfirmDialog(...)`（API 一字未改），
 * 真正的绘制统一由根节点最后一项的 [XDialogHost] 完成，采样根层 backdrop。
 */
class XDialogHostState {
    internal val loadingStates = mutableStateListOf<MutableState<Boolean>>()

    internal val confirmStates = mutableStateListOf<ConfirmDialogRegistration>()

    /**
     * 自绘内容的显示请求（见 [XDialog]）。
     *
     * 三个通道分开而不是合成一个"通用登记"，是因为加载框与确认框的骨架是固定的、
     * 宿主能自己拼（也因此能共用同一份退出动画与标题样式）；只有这一条必须把
     * 内容整块交给调用方。放在最后一项的 [XDialogHost] 按
     * 加载 → 自绘 → 确认 的顺序绘制，后画的盖在上面 —— 确认框永远是压在
     * 最上面的那一层（选项面板里点一下再问"确定吗"就是这个顺序）。
     */
    internal val customStates = mutableStateListOf<CustomDialogRegistration>()
}

/**
 * `rememberConfirmDialog(...)` 向宿主登记的一份"显示请求"。
 *
 * [visible] 是 hold 住的那个 `MutableState`，宿主在组合期读它，因此显隐是响应的；
 * 其余三项都是回调，避免宿主反向依赖 handle 内部实现。
 */
internal class ConfirmDialogRegistration(
    val visible: MutableState<Boolean>,
    val visualsProvider: () -> ConfirmDialogVisuals,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * 退出动画期间要留住上一次的内容，否则玻璃块会先变空再淡出。
 * 刻意用普通字段而不是 `mutableStateOf`：这里只需要"读到上一次的值"，不需要触发重组。
 */
private class Retained<T>(var value: T? = null)

val LocalXDialogHost = staticCompositionLocalOf<XDialogHostState> {
    error("XDialogHostState 未下发：请在根组合里用 CompositionLocalProvider 下发，并渲染 XDialogHost()")
}

/**
 * 根层 backdrop。给"不经过宿主、自己画玻璃浮层"的对话框取用
 * （典型例子是 [me.weishu.kernelsu.ui.component.dialog.DownloadDialog]：
 * 它自带局部输入状态、又只在单个页面里出现，登记到宿主反而更绕）。
 *
 * 默认 `null` 是为了让 WebUI 那个独立 Activity 也能直接用——那边没有根层 backdrop，
 * 玻璃层会自动退化成"不透明底 + 描边"，仍然是圆角，不会变回直角色块。
 */
val LocalXDialogBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 根层对话框宿主。放在根节点的**最后一个子项**，才能盖住全部内容并且采样到根层 backdrop。
 *
 * @param state 由根层 `remember { XDialogHostState() }` 创建，并通过 [LocalXDialogHost] 下发。
 * @param backdrop 根层的 `rememberBlurBackdrop(enableBlur)`；`null` 时对话框退化为不透明底 + 描边，
 *   依旧是圆角，不会变回直角色块。
 */
@Composable
fun XDialogHost(
    state: XDialogHostState,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val loadingVisible = state.loadingStates.any { it.value }
    XGlassDialog(
        show = loadingVisible,
        // 加载中不允许点外面关掉
        onDismissRequest = { },
        backdrop = backdrop,
        modifier = modifier,
        maxWidth = 320.dp,
    ) {
        // 先把返回手势吃掉，否则加载途中一个返回键就把页面退了。
        // 这段只在本组件真正可见时才会被组合（见 XGlassDialog 的 show 语义）。
        val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = navEventState,
            isBackEnabled = true,
            onBackCompleted = { },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator(
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                modifier = Modifier.padding(start = 12.dp),
                text = stringResource(R.string.processing),
                fontWeight = FontWeight.Medium,
            )
        }
    }

    // 自绘内容的对话框（见 XDialog）。夹在中间：确认框要压在它上面，
    // 所以绘制顺序必须是 加载 → 自绘 → 确认。
    val custom = state.customStates.lastOrNull { it.isVisible() }
    val retainedCustom = remember { Retained<CustomDialogRegistration>() }
    if (custom != null) retainedCustom.value = custom
    val shownCustom = custom ?: retainedCustom.value

    XGlassDialog(
        show = custom != null,
        onDismissRequest = { shownCustom?.onDismissRequest?.invoke() },
        backdrop = backdrop,
        modifier = modifier,
        maxWidth = shownCustom?.maxWidth?.invoke() ?: 420.dp,
    ) {
        shownCustom?.content?.invoke(this)
    }

    val registration = state.confirmStates.lastOrNull { it.visible.value }
    val retained = remember { Retained<ConfirmDialogRegistration>() }
    if (registration != null) retained.value = registration
    val shown = registration ?: retained.value

    XGlassDialog(
        show = registration != null,
        onDismissRequest = { shown?.onDismiss?.invoke() },
        backdrop = backdrop,
        modifier = modifier,
    ) {
        if (shown == null) return@XGlassDialog
        val visuals = shown.visualsProvider()
        if (visuals.title.isNotBlank()) {
            XDialogTitle(text = visuals.title)
        }
        ConfirmDialogContent(
            visuals = visuals,
            confirm = shown.onConfirm,
            dismiss = shown.onDismiss,
        )
    }
}

/**
 * 确认对话框的正文 + 按钮行。
 *
 * 这里保留原来那段手写 `Layout` 的原因没变：正文（可能是 Markdown/HTML）与按钮要
 * 分两段测量，正文的高度上限是"总高 - 按钮高"，否则长正文会把按钮顶出屏幕。
 */
@Composable
private fun ConfirmDialogContent(
    visuals: ConfirmDialogVisuals,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    Layout(
        content = {
            visuals.content?.let { content ->
                when {
                    visuals.isMarkdown -> MarkdownContent(content = content, isMarkdown = true)
                    visuals.isHtml -> MarkdownContent(content = content, isMarkdown = false)
                    else -> Text(text = content)
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                TextButton(
                    text = visuals.dismiss ?: stringResource(id = android.R.string.cancel),
                    onClick = dismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = visuals.confirm ?: stringResource(id = android.R.string.ok),
                    onClick = confirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    ) { measurables, constraints ->
        if (measurables.size != 2) {
            val button = measurables[0].measure(constraints)
            layout(constraints.maxWidth, button.height) {
                button.place(0, 0)
            }
        } else {
            val button = measurables[1].measure(constraints)
            // 对话框本体放在 verticalScroll 里，纵向约束是无限的（maxHeight == Infinity）。
            // 此时直接拿「无限 - 按钮高」会得到 2147483480 这种既不等于 Infinity、
            // 又塞不进 Constraints 位压缩的畸形值，直接崩（Can't represent ... in Constraints）。
            // 纵向无限时原样传给正文让它自己 wrap，只有纵向有界时才扣掉按钮高度。
            val contentConstraints = if (constraints.maxHeight == Constraints.Infinity) {
                constraints
            } else {
                constraints.copy(maxHeight = (constraints.maxHeight - button.height).coerceAtLeast(0))
            }
            val content = measurables[0].measure(contentConstraints)
            layout(constraints.maxWidth, content.height + button.height) {
                content.place(0, 0)
                button.place(0, content.height)
            }
        }
    }
}

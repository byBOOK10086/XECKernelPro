package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.filter.FilterNumber
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8DialogAction
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8TextField
import me.weishu.kernelsu.ui.design.panel.Xc8Value
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults

/**
 * 「点一下改一个数字」的行（原来是 miuix `ArrowPreference` + `TextField` 弹窗）。
 *
 * 2026-11-08 换成 MIUI 8 皮：行是 [Xc8Row]，弹窗正文用 [Xc8TextField]，
 * 底部按钮是整格两等分的 [Xc8DialogAction]。
 *
 * 参数签名**刻意保持 miuix 那套不变**（[titleColor] / [summaryColor] / [insideMargin]
 * 现在都不再参与绘制，留着是为了不动几十个调用点）：MIUI 8 的行只有"标题 + 右值"两档，
 * 没有"标题色/副标题色/内边距各自可调"这回事，配色统一由主题令牌给。
 */
@Composable
fun SuperEditArrow(
    modifier: Modifier = Modifier,
    title: String,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    defaultValue: Int = -1,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    enabled: Boolean = true,
    onValueChange: ((Int) -> Unit)? = null
) {
    val showDialog = remember { mutableStateOf(false) }

    Xc8Row(
        title = title,
        modifier = modifier,
        enabled = enabled,
        leadingContent = startAction,
        onClick = { showDialog.value = true },
        trailing = {
            Xc8Value(defaultValue.toString())
            Xc8Chevron()
        },
    )

    EditDialog(
        title = title,
        show = showDialog.value,
        onDismissRequest = { showDialog.value = false },
        dialogTextFieldValue = defaultValue,
        onValueChange = {
            onValueChange?.invoke(it)
        }
    )
}

@Composable
private fun EditDialog(
    title: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    dialogTextFieldValue: Int,
    onValueChange: (Int) -> Unit,
) {
    val filter = remember(dialogTextFieldValue) { FilterNumber(dialogTextFieldValue) }

    XDialog(
        show = show,
        onDismissRequest = {
            onDismissRequest()
            filter.setInputValue(dialogTextFieldValue.toString())
        },
    ) {
        XDialogTitle(text = title)
        Xc8TextField(
            value = filter.getInputValue().text,
            // FilterNumber 的状态是 TextFieldValue（它要守着光标位置做数字过滤），
            // 面板族的输入框只吐 String，回填时由 setInputValue 把光标收到末尾。
            onValueChange = { filter.setInputValue(it) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Xc8DialogAction(
                text = stringResource(android.R.string.cancel),
                color = Xc.colors.textSecondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    onDismissRequest()
                    filter.setInputValue(dialogTextFieldValue.toString())
                },
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(46.dp)
                    .background(Xc.colors.glassRim),
            )
            Xc8DialogAction(
                text = stringResource(R.string.confirm),
                color = Xc.colors.accent,
                modifier = Modifier.weight(1f),
                onClick = {
                    onDismissRequest()
                    with(filter.getInputValue().text) {
                        if (isEmpty()) {
                            onValueChange(0)
                            filter.setInputValue("0")
                        } else {
                            onValueChange(this@with.toInt())
                        }
                    }
                },
            )
        }
    }
}

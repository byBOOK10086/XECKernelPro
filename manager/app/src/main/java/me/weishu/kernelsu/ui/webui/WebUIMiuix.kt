package me.weishu.kernelsu.ui.webui

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.LocalXDialogBackdrop
import me.weishu.kernelsu.ui.design.glass.XGlassDialog
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * WebUI（模块网页）里的 `alert` / `confirm` / `prompt` 三个原生对话框。
 *
 * 这里用 [XGlassDialog] 而不是 miuix 的 `WindowDialog`：`WindowDialog` 会开一个
 * 新的 platform window，`LayerBackdrop` 一旦跨 window 就失效（只剩一块死色），
 * 而且它没有任何 shape / cornerRadius 参数，"每个方形框角都要圆滑"这条要求
 * 在那边根本无法满足。
 *
 * 需要注意的是本组件运行在 [WebUIActivity]（与主界面不是同一个 Activity），
 * 所以根层 backdrop 由那边的 [LocalXDialogBackdrop] 单独下发；即使没有，
 * 玻璃层也会退化成"不透明底 + 描边"，仍然是圆角。
 */
@Composable
fun HandleWebUIEventMiuix(
    webUIState: WebUIState,
    fileLauncher: ActivityResultLauncher<Intent>
) {
    when (val event = webUIState.uiEvent) {
        is WebUIEvent.ShowAlert -> {
            val showDialog = remember(event) { mutableStateOf(true) }
            XGlassDialog(
                show = showDialog.value,
                // 原来就没有"点外面关闭"（WindowDialog 未传 onDismissRequest），保持一致
                onDismissRequest = { },
                backdrop = LocalXDialogBackdrop.current,
            ) {
                Text(text = event.message, color = Xc.colors.text)
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        webUIState.onAlertResult()
                        showDialog.value = false
                    },
                    text = stringResource(R.string.confirm),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }

        is WebUIEvent.ShowConfirm -> {
            val showDialog = remember(event) { mutableStateOf(true) }
            XGlassDialog(
                show = showDialog.value,
                onDismissRequest = { webUIState.onConfirmResult(false) },
                backdrop = LocalXDialogBackdrop.current,
            ) {
                Text(text = event.message, color = Xc.colors.text)
                DialogButtonRow(
                    onCancel = {
                        webUIState.onConfirmResult(false)
                        showDialog.value = false
                    },
                    onConfirm = {
                        webUIState.onConfirmResult(true)
                        showDialog.value = false
                    },
                )
            }
        }

        is WebUIEvent.ShowPrompt -> {
            val showDialog = remember(event) { mutableStateOf(true) }
            val state = rememberTextFieldState(event.defaultValue)
            XGlassDialog(
                show = showDialog.value,
                onDismissRequest = { webUIState.onPromptResult(null) },
                backdrop = LocalXDialogBackdrop.current,
            ) {
                Text(text = event.message, color = Xc.colors.text)
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = state
                )
                DialogButtonRow(
                    onCancel = {
                        webUIState.onPromptResult(null)
                        showDialog.value = false
                    },
                    onConfirm = {
                        webUIState.onPromptResult(state.text.toString())
                        showDialog.value = false
                    },
                )
            }
        }

        is WebUIEvent.ShowFileChooser -> {
            LaunchedEffect(event) {
                try {
                    fileLauncher.launch(event.intent)
                } catch (_: Exception) {
                    webUIState.onFileChooserResult(null)
                }
            }
        }

        else -> {}
    }
}

/**
 * 取消 / 确定 两枚等宽按钮。
 * 用 `SpaceBetween` + `weight(1f)` 而不是 `Row` 里塞两个 `fillMaxWidth`，
 * 是为了让两枚按钮的宽度完全一致、缝隙只由 `Spacer` 决定。
 */
@Composable
private fun DialogButtonRow(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(
            onClick = onCancel,
            text = stringResource(android.R.string.cancel),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(20.dp))
        TextButton(
            onClick = onConfirm,
            text = stringResource(R.string.confirm),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

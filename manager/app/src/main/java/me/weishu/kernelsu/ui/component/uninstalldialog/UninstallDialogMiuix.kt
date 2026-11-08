package me.weishu.kernelsu.ui.component.uninstalldialog

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8DialogAction
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.flash.UninstallType
import me.weishu.kernelsu.ui.screen.flash.UninstallType.NONE
import me.weishu.kernelsu.ui.screen.flash.UninstallType.PERMANENT
import me.weishu.kernelsu.ui.screen.flash.UninstallType.RESTORE_STOCK_IMAGE
import me.weishu.kernelsu.ui.screen.flash.UninstallType.TEMPORARY

@Composable
fun UninstallDialogMiuix(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val options = listOf(
        // TEMPORARY,
        PERMANENT,
        RESTORE_STOCK_IMAGE
    )
    val showTodo = {
        Toast.makeText(context, "TODO", Toast.LENGTH_SHORT).show()
    }
    val showConfirmDialog = remember(show) { mutableStateOf(false) }
    val runType = remember(show) { mutableStateOf<UninstallType?>(null) }

    val run = { type: UninstallType ->
        when (type) {
            PERMANENT -> navigator.push(Route.Flash(FlashIt.FlashUninstall))

            RESTORE_STOCK_IMAGE -> navigator.push(Route.Flash(FlashIt.FlashRestore))

            TEMPORARY -> showTodo()
            NONE -> Unit
        }
    }

    XDialog(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        XDialogTitle(text = stringResource(R.string.uninstall))
        // 选项行用面板族：图标直接给 `leading`，会套上 MIUI 8 的橙色图标砖。
        options.forEach { type ->
            Xc8Row(
                title = stringResource(type.title),
                leading = type.icon,
                onClick = {
                    showConfirmDialog.value = true
                    runType.value = type
                },
            )
        }
        // 取消是整格单按钮（MIUI 8 的单动作弹窗就是这样铺满一行）。
        Row(modifier = Modifier.fillMaxWidth()) {
            Xc8DialogAction(
                text = stringResource(android.R.string.cancel),
                color = Xc.colors.textSecondary,
                modifier = Modifier.weight(1f),
                onClick = onDismissRequest,
            )
        }
    }
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            showConfirmDialog.value = false
            onDismissRequest()
            runType.value?.let { type ->
                run(type)
            }
        },
        onDismiss = {
            showConfirmDialog.value = false
        }
    )
    val dialogTitle = runType.value?.let { type ->
        options.find { it == type }?.let { stringResource(it.title) }
    } ?: ""
    val dialogContent = runType.value?.let { type ->
        options.find { it == type }?.let { stringResource(it.message) }
    }
    if (showConfirmDialog.value) {
        confirmDialog.showConfirm(title = dialogTitle, content = dialogContent)
    }
}

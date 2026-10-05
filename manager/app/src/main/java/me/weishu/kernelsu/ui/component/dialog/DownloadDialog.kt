package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 下载远程 boot 分区镜像的输入对话框。
 *
 * **必须走 [XDialog]（根层宿主），不能就地画 `XGlassDialog`。** 根层 backdrop 的宿主
 * 就是 `MainActivity` 里那个包住**全部导航内容**的 Box，而本对话框所在的
 * `InstallScreen` 正挂在它里面。就地绘制意味着"在 backdrop 的采样源内部再去采样
 * 它自己"，miuix 的 Backdrop 会因此成环，RenderThread 在
 * `RenderNode::prepareTreeImpl` 中无限递归，直接把进程打成原生 SIGSEGV
 * （`stack pointer is not in a rw map; likely due to stack overflow.`）。
 *
 * 交给 [XDialog] 之后，绘制发生在根层宿主里——它是那个包内容 Box 的**兄弟**节点，
 * 采样源与消费者分处两棵子树，成环条件消失。`show` 与输入框内容照旧只由调用方
 * 持有（见 [XDialog] 的 KDoc），行为与就地绘制时一致。
 */
@Composable
fun DownloadDialog(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    XDialog(
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.download_dialog_title),
            color = Xc.colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.download_dialog_msg),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    enabled = isValidUrl(url.trim()),
                    onClick = { onConfirm(url.trim()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

private fun isValidUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val uri = url.toUri()
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrEmpty() &&
        !uri.path.isNullOrEmpty()
}

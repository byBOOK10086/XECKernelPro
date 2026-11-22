package me.weishu.kernelsu.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * SELinux 宽容模式守卫。
 *
 * Permissive 意味着系统安全保护处于关闭状态，部分应用可以借此发现 Root；
 * 这里提示用户立即切回 Enforcing（重启后回到系统默认值），并在切换后回读状态确认。
 */
@Composable
internal fun SelinuxGuardDialog(onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    XDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        XDialogTitle(text = stringResource(R.string.selinux_guard_title))
        Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.selinux_guard_message))
            result?.let { Text(stringResource(it)) }
            TextButton(
                text = stringResource(R.string.selinux_guard_confirm),
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val switched = enforceSelinux()
                        result = if (switched) {
                            R.string.selinux_guard_done
                        } else {
                            R.string.selinux_guard_failed
                        }
                        busy = false
                    }
                },
            )
            TextButton(
                text = stringResource(android.R.string.cancel),
                enabled = !busy,
                onClick = onDismiss,
            )
        }
    }
}

/**
 * 用 root 壳执行 `setenforce 1`，再回读一次真实状态确认（命令成功不代表已生效）。
 */
private suspend fun enforceSelinux(): Boolean = withContext(Dispatchers.IO) {
    val shell = runCatching { awaitRootShell(timeoutMs = 5_000) }.getOrNull()
        ?: return@withContext false
    if (!shell.isRoot) return@withContext false
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val executed = runCatching {
        shell.newJob().add("setenforce 1").to(stdout, stderr).exec()
    }.getOrNull()
    executed?.isSuccess == true && getSELinuxStatusRaw() == "Enforcing"
}

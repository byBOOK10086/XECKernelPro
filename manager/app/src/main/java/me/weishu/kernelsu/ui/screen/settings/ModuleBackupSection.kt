package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row

/** 备份/恢复的是哪一类东西 —— 两个 SAF 启动器共用一套回调，靠它分流。 */
private enum class BackupKind { Modules, Allowlist }

/**
 * 模块备份与恢复（2026-11-09，能力来自 KernelSU-Next 的 BackupRestore）。
 *
 * 四件事：备份模块整包、恢复模块整包、备份超级用户授权表、恢复授权表。
 * 恢复模块是「解到 `modules_update` + 提示重启」，不是直接覆盖 `modules` ——
 * 这是上游的语义，也是这条功能唯一值钱的地方：**恢复是可撤销的**，只要没重启，
 * 反悔就直接退出，下一次开机 ksud 不会合并任何东西。
 *
 * 没有新开路由：它是一块普通设置面板。多一个页面就要多一条导航边，而"备份"这种
 * 一年用两次的动作不值得占一个常驻入口。
 */
@Composable
fun ModuleBackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()
    var kind by remember { mutableStateOf(BackupKind.Modules) }
    var busy by remember { mutableStateOf(false) }

    fun say(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tar"),
    ) { uri ->
        if (uri != null) {
            val which = kind
            scope.launch {
                busy = true
                val message = if (which == BackupKind.Modules) {
                    ModuleBackupOps.backupModules(context, uri).fold(
                        onSuccess = { "已备份 ${it.count} 个模块（${it.bytes / 1024} KB）" },
                        onFailure = { "备份失败：${it.message}" },
                    )
                } else {
                    ModuleBackupOps.backupAllowlist(context, uri).fold(
                        onSuccess = { "已备份授权表（${it / 1024} KB）" },
                        onFailure = { "备份失败：${it.message}" },
                    )
                }
                busy = false
                say(message)
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val which = kind
            scope.launch {
                busy = true
                if (which == BackupKind.Modules) {
                    val result = ModuleBackupOps.restoreModules(context, uri)
                    busy = false
                    result.onSuccess { count ->
                        val answer = confirm.awaitConfirm(
                            title = "模块已解包（$count 个）",
                            content = "它们被解到 `modules_update`，**重启后**才由 ksud 合并生效。" +
                                "现在重启，还是先退出（不重启就等于没恢复）？",
                            markdown = true,
                            confirm = "立即重启",
                            dismiss = "稍后",
                        )
                        if (answer == ConfirmResult.Confirmed) {
                            ModuleBackupOps.reboot()
                        }
                    }.onFailure { say("恢复失败：${it.message}") }
                } else {
                    val result = ModuleBackupOps.restoreAllowlist(context, uri)
                    busy = false
                    say(
                        result.fold(
                            onSuccess = { "授权表已恢复（立即生效）" },
                            onFailure = { "恢复失败：${it.message}" },
                        ),
                    )
                }
            }
        }
    }

    Xc8Panel(modifier = modifier.padding(top = 12.dp)) {
        Xc8Row(
            title = "备份模块",
            subtitle = "把 /data/adb/modules 打成一个 tar，存到你指定的位置",
            leading = Icons.Rounded.Archive,
            enabled = !busy,
            onClick = {
                kind = BackupKind.Modules
                createLauncher.launch("xec-modules.tar")
            },
        )
        Xc8Row(
            title = "恢复模块",
            subtitle = "选一个备份 tar；解到 modules_update，重启后生效、不重启可反悔",
            leading = Icons.Rounded.Unarchive,
            enabled = !busy,
            onClick = {
                kind = BackupKind.Modules
                openLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*"))
            },
        )
        Xc8Row(
            title = "备份超级用户授权",
            subtitle = "导出 /data/adb/ksu/.allowlist（谁有 root、各自什么策略）",
            leading = Icons.Rounded.Security,
            enabled = !busy,
            onClick = {
                kind = BackupKind.Allowlist
                createLauncher.launch("xec-allowlist.tar")
            },
        )
        Xc8Row(
            title = "恢复超级用户授权",
            subtitle = "从备份写回授权表，立即生效",
            leading = Icons.Rounded.Security,
            enabled = !busy,
            onClick = {
                kind = BackupKind.Allowlist
                openLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*"))
            },
        )
    }
}

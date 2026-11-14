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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Note
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.util.awaitRootShell

/** 模块与授权表的备份 / 恢复面板（设置页）。 */
private enum class BackupKind { Modules, Allowlist }

/**
 * 模块备份与恢复（2026-11-09；同日第二轮按审计结论加固）。
 *
 * 四件事：备份模块整包、恢复模块整包、备份超级用户授权表、恢复授权表。
 *
 * 两条语义必须说清楚，否则用户会以为是即时生效：
 *  - **模块恢复**：解到 `modules_update`，ksud 只在下一次开机时把它 rename 进
 *    `modules`（`userspace/ksud/src/module.rs:730`）。所以"不重启＝没恢复"是真的；
 *    重启一次之后就不能再撤销了。恢复是**叠加**的：备份里没有的现有模块不会被删。
 *  - **授权表恢复**：内核只在开机初始化时读 `/data/adb/ksu/.allowlist`
 *    （`kernel/core/init.c:210`），运行期没有 reload；而且重启前任何一次授权修改都会
 *    让内核把内存里的旧表整表写回，覆盖掉刚恢复的文件。所以必须是"写完就重启"。
 *
 * [kind] 用 rememberSaveable：选文件时会跳去系统文件选择器，Activity 可能被回收重建，
 * 用 remember 的话回调里会退回默认值 Modules，把授权表的 tar 当模块包解（反之亦然）。
 */
@Composable
fun ModuleBackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()
    var kind by rememberSaveable { mutableStateOf(BackupKind.Modules) }
    var busy by remember { mutableStateOf(false) }

    // 2026-11-10（真机反馈"设置里根本没有备份入口"）：这段不再由 KsuIsValid 包住。
    // 备份/恢复只需要 root shell —— 内核接口没被认成 manager 时它照样能用，而旧写法
    // 会让整段静默消失。这里自己探一次 root，探不到就禁用并说明原因。
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (rootGranted != true) {
            rootGranted = runCatching { awaitRootShell(timeoutMs = 4_000L).isRoot }.getOrDefault(false)
            if (rootGranted != true) kotlinx.coroutines.delay(5_000L)
        }
    }
    val rootReady = rootGranted != false

    fun say(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /**
     * 所有"按一下就跑很久"的操作都从这里进：**busy 一定会在 finally 里复位**，
     * 异常一定变成一句人话，取消一定原样抛回（否则协程取消会被吞成"操作失败"）。
     * 早版本是散在各处的 `busy = true … busy = false`，中间任何异常都会让整页永远转圈。
     */
    fun launchBusy(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                say("操作失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tar"),
    ) { uri ->
        if (uri != null) {
            val which = kind
            launchBusy {
                val message = if (which == BackupKind.Modules) {
                    ModuleBackupOps.backupModules(context, uri).fold(
                        onSuccess = { "已备份 ${it.modules.size} 个模块（${it.bytes / 1024} KB）：${it.modules.joinToString("、")}" },
                        onFailure = { "备份失败：${it.message}" },
                    )
                } else {
                    ModuleBackupOps.backupAllowlist(context, uri).fold(
                        onSuccess = { "已备份授权表（${it / 1024} KB）" },
                        onFailure = { "备份失败：${it.message}" },
                    )
                }
                say(message)
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val which = kind
            launchBusy {
                if (which == BackupKind.Modules) {
                    val result = ModuleBackupOps.restoreModules(context, uri)
                    result.onSuccess { done ->
                        val count = done.installed.size
                        val skippedNote = if (done.skipped.isEmpty()) {
                            ""
                        } else {
                            "\n\n⚠️ 有 ${done.skipped.size} 个没恢复：" +
                                done.skipped.joinToString("\n") { "· ${it.id} —— ${it.reason}" }
                        }
                        val answer = confirm.awaitConfirm(
                            title = "已放入 $count 个模块",
                            content = "它们被解到 `modules_update`，**重启后**才由 ksud 合并生效；" +
                                "不重启就等于没恢复。\n\n" +
                                "恢复的模块：${done.installed.joinToString("、")}\n\n" +
                                "注意：恢复是**叠加**的 —— 备份里没有的现有模块不会被删除，" +
                                "同名模块会被这份备份覆盖；备份里带 `disable` 标记的模块恢复后仍是禁用。" +
                                "带 `initrc/`、`preinit/` 的模块要等到**再下一次**开机才装进 modules.rc。" +
                                skippedNote + "\n\n" +
                                "现在重启，还是稍后自己重启？（模块已经排队，**下次开机一定生效**；" +
                                "「稍后」只是把重启推后，不是撤销）",
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
                    result.onSuccess {
                        val answer = confirm.awaitConfirm(
                            title = "授权表已写入",
                            content = "内核**只在开机时**读 `/data/adb/ksu/.allowlist`，" +
                                "运行期没有重新加载的通道 —— 所以**重启后才会生效**。\n\n" +
                                "⚠️ 重启之前**不要再动任何授权**：那样内核会把当前内存里的旧表" +
                                "整表写回，刚恢复的内容会被覆盖。\n\n" +
                                "如果这份备份里不含本管理器自己，重启后管理器会失去 root，" +
                                "那种情况只能重刷内核恢复。原表已留了一份 `.allowlist.bak-*`。",
                            markdown = true,
                            confirm = "立即重启",
                            dismiss = "稍后",
                        )
                        if (answer == ConfirmResult.Confirmed) {
                            ModuleBackupOps.reboot()
                        }
                    }.onFailure { say("恢复失败：${it.message}") }
                }
            }
        }
    }

    Xc8Panel(modifier = modifier.padding(top = 12.dp)) {
        Xc8Row(
            title = "备份模块",
            subtitle = "把 /data/adb/modules 打成一个 tar，存到你指定的位置",
            leading = Icons.Rounded.Archive,
            enabled = !busy && rootReady,
            onClick = {
                kind = BackupKind.Modules
                createLauncher.launch("xec-modules.tar")
            },
        )
        Xc8Row(
            title = "恢复模块",
            subtitle = "选一个备份 tar；解到 modules_update，重启后生效、不重启可反悔",
            leading = Icons.Rounded.Unarchive,
            enabled = !busy && rootReady,
            onClick = {
                kind = BackupKind.Modules
                openLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*"))
            },
        )
        Xc8Row(
            title = "备份超级用户授权",
            subtitle = "导出 /data/adb/ksu/.allowlist（谁有 root、各自什么策略）",
            leading = Icons.Rounded.Security,
            enabled = !busy && rootReady,
            onClick = {
                kind = BackupKind.Allowlist
                createLauncher.launch("xec-allowlist.tar")
            },
        )
        Xc8Row(
            title = "恢复超级用户授权",
            subtitle = "从备份写回授权表；内核只在开机时读它，重启后生效",
            leading = Icons.Rounded.Security,
            enabled = !busy && rootReady,
            onClick = {
                kind = BackupKind.Allowlist
                openLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*"))
            },
        )
        if (rootGranted == false) {
            Xc8Note(
                text = "当前拿不到 root shell，四项操作都不可用：请先在「授权」里确认本管理器有 " +
                    "root，或换上与安装包同一版本的内核（管理器没被内核认成 manager 时，页面上" +
                    "的模块更新检查等设置也会一并消失）。",
            )
        }
    }
}

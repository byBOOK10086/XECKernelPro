package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.design.panel.Xc8Note
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8Switch

/** 指纹伪装面板（工具箱）。 */
@Composable
fun PifSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PifConfig.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    suspend fun reload() {
        state = PifConfig.state().getOrNull()
    }

    LaunchedEffect(Unit) { reload() }

    fun launchAction(action: suspend () -> Result<String>) {
        scope.launch {
            busy = true
            try {
                val result = action()
                note = result.fold(
                    onSuccess = { it },
                    onFailure = { "失败：${it.message}" },
                )
                toast(note.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                note = "操作失败：${t.message ?: t.javaClass.simpleName}"
                toast(note.orEmpty())
            } finally {
                busy = false
            }
            reload()
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launchAction { PifConfig.importFromUri(context, uri) }
        }
    }

    val current = state

    Xc8Panel(modifier = modifier) {
        Xc8Row(
            title = "构建指纹伪装",
            subtitle = when {
                current == null -> if (busy) "读取中…" else "还没读取"
                !current.enabled -> "已关闭（开机不会改任何 build 属性）"
                current.propCount == 0 -> "已开启，但还没有导入指纹 —— 等于没生效"
                else -> "已开启 · ${current.propCount} 条属性\n" +
                    "当前机型 ${current.model ?: "?"}\n" +
                    "指纹 ${current.fingerprint ?: "?"}"
            },
            leading = Icons.Rounded.Fingerprint,
            trailing = {
                current?.let { snapshot ->
                    Xc8Switch(
                        checked = snapshot.enabled,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                busy = true
                                PifConfig.setEnabled(on)
                                busy = false
                                note = if (on) {
                                    "已开启：下次开机生效，也可以点下面的「立即应用」"
                                } else {
                                    "已关闭：下次开机不会再改指纹"
                                }
                                toast(note.orEmpty())
                                reload()
                            }
                        },
                    )
                }
            },
        )

        // 来源一：已装 PIF 类模块（最靠谱 —— 那是别人维护好的、Play 认的指纹）
        val source = current?.availableSources?.firstOrNull()
        Xc8Row(
            title = "从已装 PIF 模块导入",
            subtitle = source?.let { "读 $it（含机型/指纹/补丁，最贴合 Play 的口味）" }
                ?: "没找到 pif.json —— 装了 Play Integrity Fix 一类模块才会有",
            leading = Icons.Rounded.SystemUpdateAlt,
            enabled = !busy && source != null,
            onClick = {
                if (source != null) {
                    launchAction { PifConfig.importFromPath(context, source) }
                }
            },
        )

        // 来源二：用户自己选的 JSON
        Xc8Row(
            title = "选择 JSON 文件导入",
            subtitle = "选一个 pif.json / 指纹配置；解析在管理器里做，脚本只认白名单属性",
            leading = Icons.Rounded.FileOpen,
            enabled = !busy,
            onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )

        // 来源三：内置兜底 profile
        for (profile in PifConfig.BUILTIN_PROFILES) {
            Xc8Row(
                title = "内置：${profile.name}",
                subtitle = profile.fields["FINGERPRINT"],
                leading = Icons.Rounded.Edit,
                enabled = !busy,
                onClick = { launchAction { PifConfig.applyProfile(context, profile) } },
            )
        }

        Xc8Row(
            title = "立即应用",
            subtitle = "不用重启：直接跑模块脚本把属性写进当前系统（重启后开机脚本会再写一次）",
            leading = Icons.Rounded.PlayArrow,
            enabled = !busy && current?.enabled == true && (current.propCount) > 0,
            onClick = { launchAction { PifConfig.applyNow() } },
        )

        Xc8Note(
            note ?: "说明：内置 profile 只是「没有别的来源时」的起步点，不保证 Play 接受；" +
                "最稳的是从已装 PIF 模块导入，或换一张更新的指纹。" +
                "安全补丁由守护进程每 2 小时自动刷新（导入 PIF 时会把它的 SECURITY_PATCH " +
                "设成自定义日期，两者保持自洽）。",
        )
    }
}

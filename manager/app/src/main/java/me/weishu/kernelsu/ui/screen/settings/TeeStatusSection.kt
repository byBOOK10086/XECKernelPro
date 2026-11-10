package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Note
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8Tag
import me.weishu.kernelsu.ui.design.panel.Xc8Tone

/**
 * 密钥库（TEE）体检面板（2026-11-10）。
 *
 * 这一屏只做两件事：**如实显示**这台机器现在给出的是什么证明，以及**允许用户修**。
 * 显示分三层，从"最不可伪造"到"最可伪造"：
 *
 *  1. [TeeAttestation] 现场生成一把密钥、解析证书里的 KeyDescription —— 检测方看到的
 *     就是这一份（安全级别是软件/TEE/StrongBox、锁态是 verified/unverified）。
 *     属性可以被 resetprop 改，**这一位改不了**。
 *  2. [TeeDiagnostics] 引擎进程、拦截表、keybox 的密码学校验结论（daemon 自己给的 JSON）。
 *  3. 系统属性（verifiedbootstate / flash.locked）—— 只有对照价值：它和 1 不一致，
 *     就说明伪装只停留在属性层，真正的证明没换过来。
 *
 * 危险动作（清空密钥库）一律二次确认，且文案里写清后果。
 */
@Composable
fun TeeStatusSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()
    var busy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<TeeDiagnostics.Snapshot?>(null) }
    var attestation by remember { mutableStateOf<TeeAttestation.Result?>(null) }
    var rkpTool by remember { mutableStateOf<RkpRepair.Tool?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    suspend fun refresh() {
        busy = true
        attestation = runCatching { TeeAttestation.probe() }.getOrNull()
        snapshot = runCatching { TeeDiagnostics.probe() }.getOrNull()
        rkpTool = runCatching { RkpRepair.probeTool() }.getOrNull()
        busy = false
    }

    LaunchedEffect(Unit) { refresh() }

    val rkpLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val answer = confirm.awaitConfirm(
                title = "把这份 keybox 写进 TEE？",
                content = "会用厂商工具 `KmInstallKeybox` 把文件真正写回 TEE/RKP 存储，然后重启证明引擎。\n\n" +
                    "这一步不可撤销（旧箱子会自动备份成 `keybox.xml.bak-时间`），写错箱子会让" +
                    "验机工具判证明无效。只在**确认这份 keybox 与机型匹配**时继续。",
                markdown = true,
                confirm = "写入 TEE",
                dismiss = "取消",
            )
            if (answer != ConfirmResult.Confirmed) return@launch
            busy = true
            val result = RkpRepair.installFromUri(context, uri)
            busy = false
            note = result.fold(onSuccess = { it }, onFailure = { "重签失败：${it.message}" })
            toast(note.orEmpty())
            refresh()
        }
    }

    val snap = snapshot
    val att = attestation
    val keyboxTrusted: Boolean? = snap?.keybox?.ok

    Xc8Panel(modifier = modifier) {
        // ---------------------------------------------------------------- 1. 硬件证明
        Xc8Row(
            title = "硬件证明（现场取证）",
            subtitle = when {
                att == null -> if (busy) "正在向密钥库要一张证明…" else "还没检测"
                !att.ok -> "取不到：${att.error ?: "未知原因"}"
                else -> "${TeeAttestation.summary(att, keyboxTrusted)}\n" +
                    "证书链 ${att.chainSize} 张" +
                    if (att.googleRoot) " · 含 Google 根" else " · 不含 Google 根"
            },
            leading = Icons.Rounded.VerifiedUser,
            trailing = {
                when {
                    att == null -> Unit
                    !att.ok -> Xc8Tag("失败", tone = Xc8Tone.Danger)
                    att.securityLevel == TeeAttestation.Level.SOFTWARE ->
                        Xc8Tag("软件证明", tone = Xc8Tone.Warning)

                    else -> Xc8Tag(att.levelText, tone = Xc8Tone.Success)
                }
            },
        )

        // ---------------------------------------------------------------- 2. 引擎
        Xc8Row(
            title = "证明引擎",
            subtitle = when {
                snap == null -> if (busy) "读取中…" else "还没检测"
                snap.engineDir == null -> "没有找到 tricky_store 引擎（内置模块可能没装或已停用）"
                !snap.engineEnabled -> "引擎模块在 ${snap.engineDir}，但被 disable 了"
                else -> "${snap.engineDir}\n" +
                    "进程 ${if (snap.enginePid > 0) "运行中 (pid ${snap.enginePid})" else "没在运行"} · " +
                    "拦截 ${snap.targetCount} 个包" +
                    (snap.patchLine?.let { " · 补丁 $it" } ?: "")
            },
            leading = Icons.Rounded.Memory,
            trailing = {
                if (snap != null && snap.engineDir != null) {
                    if (snap.enginePid > 0) Xc8Tag("在跑", tone = Xc8Tone.Success)
                    else Xc8Tag("没跑", tone = Xc8Tone.Danger)
                }
            },
        )

        // ---------------------------------------------------------------- 3. keybox
        val kb = snap?.keybox
        Xc8Row(
            title = "密钥箱 keybox",
            subtitle = when {
                snap == null -> if (busy) "读取中…" else "还没检测"
                !snap.keyboxPresent -> "没有 ${"/data/adb/tricky_store/keybox.xml"}（引擎拿不到可用的证明链）"
                kb == null -> "文件在（${snap.keyboxBytes / 1024} KB），但守护进程没给出校验结论"
                else -> buildString {
                    append(if (kb.ok) "校验通过" else "校验未通过")
                    kb.rootType?.let { append(" · 根 $it") }
                    kb.deviceId?.let { append(" · $it") }
                    snap.keyboxDaysLeft?.let { days ->
                        append(if (days >= 0) " · ${days} 天后到期" else " · **已过期 ${-days} 天**")
                    }
                    if (kb.revocation != null) append(" · 已被吊销(${kb.revocation})")
                    if (kb.errors.isNotEmpty()) append("\n${kb.errors.first()}")
                }
            },
            leading = Icons.Rounded.VpnKey,
            trailing = {
                if (snap != null && snap.keyboxPresent) {
                    when (kb?.ok) {
                        true -> Xc8Tag("可信", tone = Xc8Tone.Success)
                        false -> Xc8Tag("不可信", tone = Xc8Tone.Danger)
                        null -> Xc8Tag("未知", tone = Xc8Tone.Neutral)
                    }
                }
            },
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    val result = TeeDiagnostics.refreshKeybox()
                    busy = false
                    note = result.fold(
                        onSuccess = { "已让守护进程重新拉取 keybox：$it" },
                        onFailure = { "拉取失败：${it.message}" },
                    )
                    toast(note.orEmpty())
                    refresh()
                }
            },
        )

        // ---------------------------------------------------------------- 4. 锁态对照
        Xc8Row(
            title = "锁态对照",
            subtitle = when {
                snap == null -> if (busy) "读取中…" else "还没检测"
                else -> buildString {
                    append("属性：verifiedbootstate=${snap.bootStateProp ?: "?"}、" +
                        "flash.locked=${snap.flashLockedProp ?: "?"}")
                    att?.let {
                        append("\n证明：${it.bootText}")
                        if (it.ok && it.verifiedBootState != TeeAttestation.BootState.UNKNOWN) {
                            val propLooksLocked = snap.bootStateProp == "green" && snap.flashLockedProp == "1"
                            val teeLooksLocked = it.verifiedBootState == TeeAttestation.BootState.VERIFIED
                            if (propLooksLocked != teeLooksLocked) {
                                append(" —— 两者不一致：属性层伪装了，TEE 里没有")
                            }
                        }
                    }
                }
            },
            leading = Icons.Rounded.VerifiedUser,
        )

        // ---------------------------------------------------------------- 5. 动作
        Xc8Row(
            title = "重新检测",
            subtitle = "重新取一次证明并重读引擎/密钥箱状态",
            leading = Icons.Rounded.Refresh,
            enabled = !busy,
            onClick = { scope.launch { refresh() } },
        )
        Xc8Row(
            title = "重启证明引擎",
            subtitle = "杀掉 TEESimulator 并重新拉起（改完 target.txt / 换完 keybox 后用）",
            leading = Icons.Rounded.RestartAlt,
            enabled = !busy && snap != null,
            onClick = {
                scope.launch {
                    busy = true
                    val result = TeeDiagnostics.restartEngine(snap?.engineDir)
                    busy = false
                    note = result.fold(
                        onSuccess = { if (it) "引擎已重新拉起" else "重启命令发出去了，但进程没在 12 秒内出现" },
                        onFailure = { "重启失败：${it.message}" },
                    )
                    toast(note.orEmpty())
                    refresh()
                }
            },
        )
        Xc8Row(
            title = "清空密钥库（危险）",
            subtitle = "删掉所有应用的 AndroidKeyStore 密钥与引擎生成的持久密钥，然后重启。" +
                "银行/支付类应用要重新登录，只在证明判定异常时用",
            leading = Icons.Rounded.Delete,
            enabled = !busy,
            onClick = {
                scope.launch {
                    val answer = confirm.awaitConfirm(
                        title = "清空密钥库？",
                        content = "会删除 `/data/misc/keystore` 与引擎的 `persistent_keys`，" +
                            "**所有应用**的密钥都会消失（银行、支付、部分登录态需要重新授权），" +
                            "而且清完必须立刻重启才生效。\n\n平时不要用；只在" +
                            "「换了 keybox/TEE 判定仍然异常」时作为最后手段。",
                        markdown = true,
                        confirm = "清空并重启",
                        dismiss = "取消",
                    )
                    if (answer != ConfirmResult.Confirmed) return@launch
                    busy = true
                    val result = TeeDiagnostics.clearKeyStore()
                    busy = false
                    result.fold(
                        onSuccess = {
                            ModuleBackupOps.reboot()
                        },
                        onFailure = { toast("清空失败：${it.message}") },
                    )
                }
            },
        )

        // ---------------------------------------------------------------- 6. 真修复（RKP 重签）
        if (rkpTool != null) {
            Xc8Row(
                title = "真·RKP 重签（写回 TEE）",
                subtitle = if (rkpTool!!.usable) {
                    "厂商工具 ${rkpTool!!.path}（root:${rkpTool!!.mode}）在位 —— " +
                        "把当前在用的 keybox 真正写回 TEE，而不是软件伪造"
                } else {
                    "找到 ${rkpTool!!.path}，但不可用：${rkpTool!!.reason}"
                },
                leading = Icons.Rounded.VpnKey,
                enabled = !busy && rkpTool!!.usable,
                onClick = {
                    scope.launch {
                        val answer = confirm.awaitConfirm(
                            title = "把当前 keybox 写回 TEE？",
                            content = "会调用厂商工具 `KmInstallKeybox`，把 `/data/adb/tricky_store/keybox.xml` " +
                                "真正写进 TEE/RKP 存储，然后重启证明引擎。旧箱子会备份成 `keybox.xml.bak-时间`。\n\n" +
                                "这是**真修复**：写成功后 TEE 会自己签证明，不再依赖软件伪造。",
                            markdown = true,
                            confirm = "写回 TEE",
                            dismiss = "取消",
                        )
                        if (answer != ConfirmResult.Confirmed) return@launch
                        busy = true
                        val result = RkpRepair.installLive()
                        busy = false
                        note = result.fold(onSuccess = { it }, onFailure = { "重签失败：${it.message}" })
                        toast(note.orEmpty())
                        refresh()
                    }
                },
            )
            Xc8Row(
                title = "选一个 keybox 写进 TEE",
                subtitle = "先选文件再写回；适合刚换了一张箱子、想直接落到 TEE 的情况",
                leading = Icons.Rounded.VpnKey,
                enabled = !busy && rkpTool!!.usable,
                onClick = { rkpLauncher.launch(arrayOf("text/xml", "application/xml", "text/plain", "*/*")) },
            )
        } else {
            Xc8Row(
                title = "真·RKP 重签",
                subtitle = "这台机器没有厂商重签工具（/vendor/bin/KmInstallKeybox）—— " +
                    "只能走软件伪造（FORGE/PATCH）。多数国产 ROM 都缺这个工具，不是本管理器能补的",
                leading = Icons.Rounded.VpnKey,
                enabled = false,
            )
        }

        Xc8Note(
            when {
                note != null -> note!!
                snap?.errors?.isNotEmpty() == true -> snap!!.errors.joinToString("；")
                snap?.logLines?.isNotEmpty() == true -> "引擎最近告警：\n" + snap!!.logLines.take(3).joinToString("\n")
                else -> "说明：属性（verifiedbootstate 等）可以被 resetprop 改，上级那一份" +
                    "「硬件证明」是 keystore 现场签的，改不了 —— 检测方看的是它。"
            },
        )
    }
}

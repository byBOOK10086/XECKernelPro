package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.design.panel.Xc8Tag
import me.weishu.kernelsu.ui.design.panel.Xc8Tone
import me.weishu.kernelsu.ui.design.token.Xc

/**
 * 密钥库体检（扁平方案 B）。
 *
 * 结构是"一条链 + 一块仪表"，不是设置列表：
 *  1. 5 段色带 —— 每一段对应证明链上的一个节点，断在哪段一眼可见；
 *  2. 链路节点 —— TEE / 密钥箱 / 引擎 / keystore2 / 开机自启；
 *  3. 大数字行 —— 到期天数、拦截包、待处理、引擎告警；
 *  4. 九格体检 —— 九项状态平铺，异常格红描边；
 *  5. 胶带主按钮 —— 整宽「一键修复」，点击后原地展开步骤流水线；
 *  6. 详情（可折叠）—— 原来那一列动作/开关全部保留在这里。
 *
 * 业务逻辑与旧版一致：所有动作最终都走 [TeeDiagnostics]。
 */
@Composable
fun TeePanelSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()
    var busy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<TeeDiagnostics.Snapshot?>(null) }
    var attestation by remember { mutableStateOf<TeeAttestation.Result?>(null) }
    var rkpTool by remember { mutableStateOf<RkpRepair.Tool?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var repairSteps by remember { mutableStateOf<List<TeeDiagnostics.RepairStep>>(emptyList()) }
    var detailsOpen by remember { mutableStateOf(false) }
    /**
     * 修复进度。
     *
     * 真机反馈（2026-11-11）：「点一键修复后直接变灰」。它确实是"正在跑"，但
     * `builtin start` 要给三段阶段脚本各 45 秒预算，全程最长两分多钟——只把按钮
     * 变灰等于让用户以为卡死。这里把阶段名和秒数显示在按钮上，变灰也变"有话说"。
     */
    var phase by remember { mutableStateOf("") }
    var repairStartedAt by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    suspend fun refresh() {
        busy = true
        try {
            attestation = runCatching { TeeAttestation.probe() }.getOrNull()
            snapshot = runCatching { TeeDiagnostics.probe() }.getOrNull()
            rkpTool = runCatching { RkpRepair.probeTool() }.getOrNull()
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 修复期间每秒刷新一次计时，让"变灰"变成"看得见的进度"。
    LaunchedEffect(busy, repairStartedAt) {
        if (!busy || repairStartedAt == 0L) return@LaunchedEffect
        while (true) {
            elapsed = ((System.currentTimeMillis() - repairStartedAt) / 1000L).toInt()
            kotlinx.coroutines.delay(1000L)
        }
    }

    fun runRepair() {
        scope.launch {
            val answer = confirm.awaitConfirm(
                title = "执行一键修复？",
                content = "依次执行：\n\n" +
                    "1. 把安装包**内置**的引擎与 TA 增强释放到 `/data/adb/ksu/builtin`\n" +
                    "2. 当场启动引擎（已有的 supervisor 先收掉再重拉）\n" +
                    "3. 拉一次 keybox，回读进程与 keystore2 注入\n\n" +
                    "**不会**动你的密钥库、不写 TEE、不删你的模块。\n\n" +
                    "重启后：旧守护进程需要再点一次；把 `/data/adb/xudc` 换成当前 APK 版本" +
                    "（详情里的「刷新守护进程」）后，每次开机都会自动拉起。",
                markdown = true,
                confirm = "开始",
                dismiss = "取消",
            )
            if (answer != ConfirmResult.Confirmed) return@launch
            busy = true
            phase = "正在释放并启动引擎"
            repairStartedAt = System.currentTimeMillis()
            elapsed = 0
            val result = try {
                // 硬性上限：阶段脚本最多各 45 秒 + 回读，超过 4 分钟一律收尾，别让
                // 按钮永远灰着（真机反馈的就是这个）。
                kotlinx.coroutines.withTimeoutOrNull(240_000L) {
                    TeeDiagnostics.oneTapRepair(snapshot?.engineDir) { next -> phase = next }
                } ?: Result.failure(
                    IllegalStateException(
                        "超过 4 分钟仍未结束：引擎可能还在后台启动，请稍后点右上角刷新看结果",
                    ),
                )
            } finally {
                busy = false
                repairStartedAt = 0L
                phase = ""
            }
            repairSteps = result.getOrElse {
                listOf(TeeDiagnostics.RepairStep("修复", false, it.message.orEmpty()))
            }
            note = result.fold(
                onSuccess = { steps ->
                    val failed = steps.filterNot { it.ok }
                    if (failed.isEmpty()) {
                        "一键修复：${steps.size} 步全部成功"
                    } else {
                        "一键修复未完全成功：${failed.size} 步失败（" +
                            failed.joinToString("、") { it.label } + "）"
                    }
                },
                onFailure = { "修复失败：${it.message}" },
            )
            toast(note.orEmpty())
            refresh()
        }
    }

    val snap = snapshot
    val att = attestation
    val view = remember(snap, att, repairSteps) { buildView(snap, att, repairSteps) }

    Column(modifier = modifier) {
        // ---------------------------------------------------------------- 标题
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "密钥库体检",
                    color = Xc.colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                )
                Text(
                    "工具箱 · TEE 证明链",
                    color = Xc.colors.textMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(1.dp, Xc.colors.glassRim, CircleShape)
                    .clickable(enabled = !busy) { scope.launch { refresh() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "重新检测",
                    tint = Xc.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // ---------------------------------------------------------------- 5 段色带
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            view.segments.forEach { seg ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(7.dp)
                        .background(toneColor(seg.state)),
                )
            }
        }

        // ---------------------------------------------------------------- 链路
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            view.nodes.forEach { node ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(toneColor(node.state)),
                    )
                    Text(
                        node.label,
                        color = Xc.colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    Text(
                        node.value,
                        color = if (node.state == S.Bad) Xc.colors.danger else Xc.colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 大数字
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        ) {
            view.stats.forEachIndexed { index, stat ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (index == 0) 0.dp else 12.dp)
                        .then(
                            if (index == 0) {
                                Modifier
                            } else {
                                Modifier.border(0.dp, Color.Transparent)
                            },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            stat.value,
                            color = Xc.colors.text,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        )
                        Text(
                            stat.unit,
                            color = Xc.colors.textMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        )
                    }
                    Text(
                        stat.label,
                        color = Xc.colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 九格
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "九项体检",
                color = Xc.colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (view.tiles.count { it.state == S.Bad } == 0) "全部通过" else
                    "${view.tiles.count { it.state == S.Bad }} 项异常",
                color = Xc.colors.textMuted,
                fontSize = 10.sp,
            )
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile -> TeeTileBox(tile, Modifier.weight(1f)) }
                }
            }
        }

        // ---------------------------------------------------------------- 胶带主按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (busy) Xc.colors.accent.copy(alpha = 0.62f) else Xc.colors.accent)
                .clickable(enabled = !busy) { runRepair() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    busy && phase.isNotEmpty() -> "修复中 ${elapsed}s · $phase"
                    busy -> "修复中 ${elapsed}s…"
                    repairSteps.isEmpty() -> "一键修复 · 装引擎并立即启动"
                    else -> "再修一次 · 装引擎并立即启动"
                },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text("→", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // ---------------------------------------------------------------- 流水线
        if (repairSteps.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "上次修复",
                    color = Xc.colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${repairSteps.size} 步 · ${repairSteps.count { !it.ok }} 步失败",
                    color = if (repairSteps.any { !it.ok }) Xc.colors.danger else Xc.colors.success,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.padding(top = 8.dp)) {
                repairSteps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .padding(top = 3.dp)
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, if (step.ok) Xc.colors.success else Xc.colors.danger, CircleShape),
                            )
                            if (index != repairSteps.lastIndex) {
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .height(30.dp)
                                        .background(Xc.colors.glassRim),
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 10.dp)) {
                            Text(
                                step.label,
                                color = Xc.colors.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                step.detail,
                                color = Xc.colors.textMuted,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            if (step.ok) "完成" else "失败",
                            color = if (step.ok) Xc.colors.success else Xc.colors.danger,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 详情（折叠）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clickable { detailsOpen = !detailsOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (detailsOpen) "收起详情与操作" else "展开详情与操作",
                color = Xc.colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (detailsOpen) "︿" else "﹀",
                color = Xc.colors.accent,
                fontSize = 12.sp,
            )
        }

        if (detailsOpen) {
            TeeDetails(
                context = context,
                snap = snap,
                att = att,
                rkpTool = rkpTool,
                busy = busy,
                onBusy = { busy = it },
                onNote = { note = it },
                onToast = { toast(it) },
                onRefresh = { scope.launch { refresh() } },
            )
        }

        note?.let {
            Text(
                text = it,
                color = Xc.colors.textMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- 详情区

@Composable
private fun TeeDetails(
    context: android.content.Context,
    snap: TeeDiagnostics.Snapshot?,
    att: TeeAttestation.Result?,
    rkpTool: RkpRepair.Tool?,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onNote: (String?) -> Unit,
    onToast: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()

    fun action(label: String, body: suspend () -> Result<String>) {
        scope.launch {
            onBusy(true)
            val result = try {
                body()
            } finally {
                onBusy(false)
            }
            onNote(result.fold(onSuccess = { it }, onFailure = { "$label 失败：${it.message}" }))
            onToast(label + (result.fold({ "：完成" }, { "：失败" })))
            onRefresh()
        }
    }

    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 状态摘要
        TeeDetailLine("硬件证明", att?.let { if (it.ok) it.bootText else (it.error ?: "取不到") } ?: "还没检测")
        TeeDetailLine("引擎目录", snap?.engineDir ?: "三个模块目录都不存在")
        TeeDetailLine(
            "拦截表",
            snap?.let { "${it.targetCount} 项 · AUTO ${it.targetAuto} / PATCH ${it.targetPatch}" } ?: "未检测",
        )
        TeeDetailLine(
            "按应用补丁覆盖",
            snap?.let { if (it.patchPerPackage == 0) "无（全部用全局补丁级别）" else "${it.patchPerPackage} 个包单独设置" }
                ?: "未检测",
        )

        // 动作
        TeeActionRow("重新检测", "重新取证并刷新本页") { action("重新检测") { Result.success("已重新检测") } }
        TeeActionRow("刷新守护进程", "把 /data/adb/xudc 换成当前 APK 版本") {
            action("刷新守护进程") {
                TeeDiagnostics.refreshBootDaemon(context).map { it }
            }
        }
        TeeActionRow("重启引擎", "改了 target / keybox 之后") {
            action("重启引擎") {
                TeeDiagnostics.restartEngine(snap?.engineDir)
                    .map { ok -> if (ok) "引擎已重新拉起" else "命令发出去了，但 12 秒内没看到进程" }
            }
        }
        TeeActionRow("拉取 keybox", "从镜像列表换一张") {
            action("拉取 keybox") { TeeDiagnostics.refreshKeybox() }
        }
        if (snap?.targetHasVerifier == false) {
            TeeActionRow("把验机工具加进拦截表", "wu.keyChain.test 现在看到的是设备真实 TEE") {
                action("加入拦截表") {
                    TeeDiagnostics.addVerifierToTarget().map { "已加入，正在重启引擎" }
                }
            }
        }

        // 远程配置优先
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("远程配置优先", color = Xc.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    "每次开机把安全补丁钉成每 2 小时、keybox 自动拉取",
                    color = Xc.colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Xc8Switch(
                checked = snap?.remotePriority == true,
                enabled = !busy,
                onCheckedChange = { on ->
                    scope.launch {
                        onBusy(true)
                        val result = try {
                            TeeDiagnostics.setRemotePriority(on)
                        } finally {
                            onBusy(false)
                        }
                        onNote(
                            result.fold(
                                onSuccess = { if (on) "已开启远程配置优先" else "已关闭远程配置优先" },
                                onFailure = { "写入失败：${it.message}" },
                            ),
                        )
                        onRefresh()
                    }
                },
            )
        }

        // 危险操作
        TeeActionRow("清空密钥库（危险）", "删掉所有应用密钥与引擎持久密钥，然后重启", danger = true) {
            scope.launch {
                val answer = confirm.awaitConfirm(
                    title = "清空密钥库？",
                    content = "会删除 `/data/misc/keystore` 与引擎的 `persistent_keys`，" +
                        "**所有应用**的密钥都会消失（银行、支付需要重新授权），清完必须立刻重启。",
                    markdown = true,
                    confirm = "清空并重启",
                    dismiss = "取消",
                )
                if (answer != ConfirmResult.Confirmed) return@launch
                onBusy(true)
                val result = try {
                    TeeDiagnostics.clearKeyStore()
                } finally {
                    onBusy(false)
                }
                result.fold(
                    onSuccess = { ModuleBackupOps.reboot() },
                    onFailure = { onToast("清空失败：${it.message}") },
                )
            }
        }

        // RKP 重签
        rkpTool?.let { tool ->
            TeeActionRow(
                "真·RKP 重签（写回 TEE）",
                if (tool.usable) "厂商工具 ${tool.path} 在位" else "找到 ${tool.path}，但不可用：${tool.reason}",
            ) {
                scope.launch {
                    onBusy(true)
                    val result = try {
                        RkpRepair.installLive()
                    } finally {
                        onBusy(false)
                    }
                    onNote(result.fold(onSuccess = { it }, onFailure = { "重签失败：${it.message}" }))
                    onRefresh()
                }
            }
        }

        // 说明
        Text(
            "说明：属性（verifiedbootstate 等）可以被 resetprop 改，但检测方看的是 keystore " +
                "现场签的那份证明 —— 所以这一页以「引擎 + keybox + 注入」三项为准。",
            color = Xc.colors.textMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun TeeDetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = Xc.colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            color = Xc.colors.text,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TeeActionRow(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (danger) Xc.colors.danger.copy(alpha = 0.35f) else Xc.colors.glassRim, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) Xc.colors.danger else Xc.colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                color = Xc.colors.textMuted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text("›", color = Xc.colors.textMuted, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------- 数据模型

private enum class S { Ok, Warn, Bad, Unknown }

private data class TeeSeg(val state: S)
private data class TeeNode(val label: String, val value: String, val state: S)
private data class TeeStat(val value: String, val unit: String, val label: String)
private data class TeeTile(val name: String, val value: String, val state: S)
private data class TeeView(
    val segments: List<TeeSeg>,
    val nodes: List<TeeNode>,
    val stats: List<TeeStat>,
    val tiles: List<TeeTile>,
)

private fun buildView(
    snap: TeeDiagnostics.Snapshot?,
    att: TeeAttestation.Result?,
    repairSteps: List<TeeDiagnostics.RepairStep>,
): TeeView {
    val keyboxOk = snap?.keybox?.ok == true
    val days = snap?.keyboxDaysLeft
    val engineRunning = (snap?.enginePid ?: 0) > 0
    val injected = snap?.engineInjected == true
    val autoStep = repairSteps.firstOrNull { it.label.contains("自启") }
    val autoState = when {
        autoStep == null -> S.Unknown
        autoStep.ok -> S.Ok
        else -> S.Bad
    }

    val keyboxState = when {
        snap == null -> S.Unknown
        !snap.keyboxPresent -> S.Bad
        days != null && days < 0 -> S.Bad
        days != null && days < 7 -> S.Warn
        keyboxOk -> S.Ok
        else -> S.Warn
    }
    val engineState = when {
        snap == null -> S.Unknown
        engineRunning -> S.Ok
        snap.engineDir != null -> S.Warn
        else -> S.Bad
    }
    val injectState = when {
        snap == null -> S.Unknown
        injected -> S.Ok
        else -> S.Bad
    }
    val teeState = when {
        att == null -> S.Unknown
        att.ok -> S.Ok
        else -> S.Bad
    }

    val tiles = listOf(
        TeeTile("引擎", if (engineRunning) "在跑" else if (snap?.engineDir != null) "没跑" else "没装", engineState),
        TeeTile("注入", if (injected) "已接管" else "未接管", injectState),
        TeeTile("密钥箱", if (snap?.keyboxPresent == true) (days?.let { "$it 天" } ?: "在") else "缺失", keyboxState),
        TeeTile("拦截表", "${snap?.targetCount ?: 0} 项", if ((snap?.targetCount ?: 0) > 0) S.Ok else S.Bad),
        TeeTile("验机工具", if (snap?.targetHasVerifier == true) "已覆盖" else "未覆盖", if (snap?.targetHasVerifier == true) S.Ok else S.Warn),
        TeeTile("锁态", snap?.bootStateProp ?: "未知", if (snap?.bootStateProp == "green") S.Ok else S.Unknown),
        TeeTile("补丁", snap?.patchLine ?: "未设置", if (snap?.patchLine != null) S.Ok else S.Warn),
        TeeTile("硬件证明", if (att?.ok == true) "可签" else "取不到", teeState),
        TeeTile("开机自启", if (autoStep == null) "未验证" else if (autoStep.ok) "已开" else "旧版", autoState),
    )

    val pending = tiles.count { it.state == S.Bad || it.state == S.Warn }
    return TeeView(
        segments = listOf(TeeSeg(teeState), TeeSeg(keyboxState), TeeSeg(engineState), TeeSeg(injectState), TeeSeg(autoState)),
        nodes = listOf(
            TeeNode("TEE", if (teeState == S.Ok) "正常" else "异常", teeState),
            TeeNode("密钥箱", days?.let { "$it 天" } ?: "缺失", keyboxState),
            TeeNode("引擎", if (engineRunning) "运行中" else "没跑", engineState),
            TeeNode("keystore2", if (injected) "已接管" else "未接管", injectState),
            TeeNode("开机自启", if (autoStep == null) "未验证" else if (autoStep.ok) "已开" else "旧版", autoState),
        ),
        stats = listOf(
            TeeStat(days?.toString() ?: "—", "天", "密钥箱到期"),
            TeeStat("${snap?.targetCount ?: 0}", "包", "拦截中"),
            TeeStat("$pending", "项", "待处理"),
            TeeStat("${snap?.logLines?.size ?: 0}", "条", "引擎告警"),
        ),
        tiles = tiles,
    )
}

@Composable
private fun TeeTileBox(tile: TeeTile, modifier: Modifier = Modifier) {
    val color = when (tile.state) {
        S.Ok -> Xc.colors.success
        S.Warn -> Xc.colors.warning
        S.Bad -> Xc.colors.danger
        S.Unknown -> Xc.colors.textMuted
    }
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (tile.state == S.Bad) Xc.colors.danger.copy(alpha = 0.55f) else Xc.colors.glassRim,
                RoundedCornerShape(8.dp),
            )
            .background(if (tile.state == S.Bad) Xc.colors.dangerTint.copy(alpha = 0.35f) else Xc.colors.surface.copy(alpha = 0.35f))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tile.name,
            color = Xc.colors.textSecondary,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tile.value,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun toneColor(state: S): Color = when (state) {
    S.Ok -> Xc.colors.success
    S.Warn -> Xc.colors.warning
    S.Bad -> Xc.colors.danger
    S.Unknown -> Xc.colors.glassRim
}

package me.weishu.kernelsu.ui.screen.detect

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcNeon
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import me.weishu.kernelsu.ui.util.isMountState
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 「内置模块」开关卡片：内置 TEE 引擎与内置 TA 增强，**两个互相独立**的开关。
 *
 * 为什么要有这一页：内置 TA/TEE 随内核与 ksud 一起分发，本体落在 tmpfs
 *（/dev/.xudc_hidden），模块页里根本看不到它；用户既没法关掉、也没法知道它跟自己刷的
 * TrickyStore / TA_enhanced 谁在管事。
 *
 * 2026-10-16 这一版把"能看见"做成"能诊断"。以前失败全塌成一句"读不到内置模块的状态"，
 * 用户只能猜。现在四种失败分开写，各自有出路：
 *   1. 开关读写走**管理器自带**的 ksud（APK 里的 libxeccli.so），不再依赖设备上那份
 *      ——设备侧 ksud 是内核带着装的，只更新管理器时它可能还是旧的（`builtin` 子命令
 *      不存在），开关就"点了没反应"；
 *   2. 内核侧（开机真正执行内置模块的那份）ksud 不支持开关时明确写出来：重启后开关
 *      不生效，要一并更新内核 / LKM；
 *   3. **引擎资产缺失**（这一版 ksud 构建时没跑 CI 的资产脚本）写得一清二楚；
 *   4. 引擎在跑但**注入 keystore2 失败**（最常见：keystore 侧 sepolicy 没打上）时给
 *      一键「修复引擎」：重打 sepolicy + 重新解密 + 干净重启注入链。
 * 挂载态（<5.10 Magisk 路线）没有内置模块，卡片直接说明。
 */
private const val BUILTIN_FLAG = "/data/adb/.xudc_secure/builtin.conf"
private const val ENGINE_START = "/data/adb/.xudc_secure/engine-start.txt"
private const val SELFCHECK = "/data/adb/.xudc_secure/tee-selfcheck.txt"

/**
 * 一次 root shell 取回两个模块的状态。
 *
 * `cli` 是**管理器自己**那份 ksud：开关的读写必须走它，否则设备侧 ksud 一旧，
 * 用户看到的就是"开关点了没反应"。内核侧那份只用来判断"重启后算不算数"。
 */
private fun builtinStatusScript(cli: String): String = listOf(
    "K=\"$cli\"; [ -x \"\$K\" ] || K=/data/adb/ksu/bin/ksud; [ -x \"\$K\" ] || K=/data/adb/xudc; [ -x \"\$K\" ] || K=ksud",
    "echo ksud=\$K",
    // JSON 压成一行：值里没有空格，压掉换行与缩进后管理器直接用 JSONObject 解析。
    "echo json=\$(\$K builtin status 2>/dev/null | tr -d '\\n\\r ')",
    "echo clierr=\$(\$K builtin status >/dev/null 2>&1 && echo none || echo failed)",
    // 内核侧那份 ksud（开机真正跑内置模块的）支不支持开关。
    "B=/data/adb/ksu/bin/ksud; [ -x \"\$B\" ] || B=/data/adb/xudc; [ -x \"\$B\" ] || B=",
    "if [ -z \"\$B\" ]; then echo bootksud=missing; elif \"\$B\" builtin status >/dev/null 2>&1; then echo bootksud=yes; else echo bootksud=no; fi",
    // 进程事实：本项目引擎叫 keymint_svc，上游模块叫 TEESimulator，TA 是 ta-enhanced。
    "echo engine=\$(pidof keymint_svc 2>/dev/null || echo none)",
    "echo upstream=\$(pidof TEESimulator 2>/dev/null || echo none)",
    "echo tadaemon=\$(pidof ta-enhanced 2>/dev/null || echo none)",
    // 注入是否**真的**成功：keystore2 的 maps 里有没有我们的 .so（唯一硬证据）。
    "KSP=\$(pidof keystore2 2>/dev/null | awk '{print \$1}')",
    "if [ -n \"\$KSP\" ] && grep -q libTEESimulator /proc/\$KSP/maps 2>/dev/null; then echo injected=yes; else echo injected=no; fi",
    // 启动诊断（service.sh 写的 engine-start.txt）与自检里的注入相关字段。
    "echo start=\$(sed -n '1,20p' $ENGINE_START 2>/dev/null | tr '\\n' '|')",
    "echo tastart=\$(sed -n '1,20p' /data/adb/.xudc_secure/ta-start.txt 2>/dev/null | tr '\\n' '|')",
    "echo sc=\$(sed -n '1,24p' $SELFCHECK 2>/dev/null | tr '\\n' '|')",
    "echo flagon=\$([ -f $BUILTIN_FLAG ] && sed -n '1,6p' $BUILTIN_FLAG | tr '\\n' ',' || echo none)",
).joinToString("\n")

/** 开 / 关 / 修复一个内置模块（`cli` = 管理器自带的 ksud）。 */
private fun builtinActionScript(cli: String, action: String, target: String): String = listOf(
    "K=\"$cli\"; [ -x \"\$K\" ] || K=/data/adb/ksu/bin/ksud; [ -x \"\$K\" ] || K=/data/adb/xudc; [ -x \"\$K\" ] || K=ksud",
    "if \$K builtin $action $target >/dev/null 2>&1; then",
    "  echo ok=yes",
    "else",
    "  echo ok=no",
    "  \$K builtin $action $target 2>&1 | tail -n 2",
    "fi",
).joinToString("\n")

private data class BuiltinModuleState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val runtime: Boolean = false,
    val external: Boolean = false,
    val processAlive: Boolean = false,
    /** 引擎二进制是否真的打进了这一版 ksud（"ok" / "missing:…"）。 */
    val assets: String = "ok",
    /** 运行目录里的落地情况（"ok" / "missing:…"）。 */
    val runtimeAssets: String = "ok",
    /** keystore 侧 sepolicy 打上了没有（ksud 记录："ok" / "failed:…" / "unknown"）。 */
    val policy: String = "unknown",
)

private data class BuiltinState(
    val ksud: String = "",
    val cliFailed: Boolean = false,
    val tee: BuiltinModuleState = BuiltinModuleState(),
    val ta: BuiltinModuleState = BuiltinModuleState(),
    val injected: Boolean = false,
    val engineAlive: Boolean = false,
    val bootKsud: String = "",
    val mountState: Boolean = false,
    val startStep: String = "",
    val startRc: String = "",
    val startDetail: String = "",
    val daemonStarts: String = "",
    val injectErr: String = "",
    val taStartStep: String = "",
    val taStartRc: String = "",
    val taStartDetail: String = "",
) {
    val assetsBad: Boolean get() = tee.assets != "ok" || ta.assets != "ok"
    val runtimeBad: Boolean get() = tee.enabled && tee.runtimeAssets != "ok"
    /** 引擎在跑但没注入 = 依然零拦截，这是最容易被误判成"已经好了"的形态。 */
    val notInjected: Boolean get() = engineAlive && !injected
}

@Composable
fun BuiltinModulesCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BuiltinState()) }
    var busy by remember { mutableStateOf(false) }
    val neon = XcNeon.colors
    val cli = remember { getKsuDaemonPath() }
    val mountState = remember { isMountState }

    suspend fun refresh() {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                withNewRootShell(true) {
                    ShellUtils.fastCmd(this, builtinStatusScript(cli))
                }
            }.getOrDefault("")
        }
        val map = out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()

        val root = runCatching { JSONObject(map["json"].orEmpty()) }.getOrNull()
        fun module(key: String, alive: Boolean): BuiltinModuleState {
            val obj = root?.optJSONObject(key) ?: return BuiltinModuleState()
            return BuiltinModuleState(
                available = true,
                enabled = obj.optBoolean("enabled"),
                runtime = obj.optBoolean("runtime"),
                external = obj.optBoolean("external"),
                processAlive = alive,
                assets = obj.optString("assets", "ok").ifEmpty { "ok" },
                runtimeAssets = obj.optString("runtimeassets", "ok").ifEmpty { "ok" },
                policy = obj.optString("policy", "unknown").ifEmpty { "unknown" },
            )
        }

        fun backfill(raw: String): Map<String, String> = raw.split('|')
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
            }
            .toMap()

        val sc = backfill(map["sc"].orEmpty())
        val start = backfill(map["start"].orEmpty())
        val taStart = backfill(map["tastart"].orEmpty())
        val engineAlive = map["engine"].orEmpty().let { it.isNotEmpty() && it != "none" }
        val upstreamAlive = map["upstream"].orEmpty().let { it.isNotEmpty() && it != "none" }
        val taAlive = map["tadaemon"].orEmpty().let { it.isNotEmpty() && it != "none" }

        state = BuiltinState(
            ksud = map["ksud"].orEmpty(),
            cliFailed = map["clierr"] == "failed",
            tee = module("tee", engineAlive || upstreamAlive),
            ta = module("ta", taAlive),
            injected = map["injected"] == "yes",
            engineAlive = engineAlive,
            bootKsud = map["bootksud"].orEmpty(),
            mountState = mountState,
            startStep = start["step"].orEmpty(),
            startRc = start["rc"].orEmpty(),
            startDetail = start["detail"].orEmpty(),
            daemonStarts = sc["daemonstarts"].orEmpty(),
            injectErr = sc["injecterr"].orEmpty().takeIf { it.isNotEmpty() && it != "none" }.orEmpty(),
            taStartStep = taStart["step"].orEmpty(),
            taStartRc = taStart["rc"].orEmpty(),
            taStartDetail = taStart["detail"].orEmpty(),
        )
    }

    LaunchedEffect(Unit) { refresh() }

    /**
     * 跑一个动作并**核对结果**：以前只看命令退出码，而 `disable` 对已经关掉的模块也返回 0，
     * 于是"什么都没发生"也会弹一句成功。这里改成重读状态、以设备上的实际开关为准。
     */
    fun act(action: String, target: String, wantEnabled: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, builtinActionScript(cli, action, target))
                    }
                }.getOrDefault("")
            }
            refresh()
            busy = false
            val now = if (target == "tee") state.tee.enabled else state.ta.enabled
            val ok = out.contains("ok=yes") && now == wantEnabled
            Toast.makeText(
                context,
                context.getString(
                    when {
                        !ok -> R.string.detect_builtin_toggle_failed
                        action == "repair" -> R.string.detect_builtin_repair_done
                        wantEnabled -> R.string.detect_builtin_toggle_on
                        else -> R.string.detect_builtin_toggle_off
                    }
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.detect_builtin_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.detect_builtin_note),
                fontSize = 12.sp,
                color = Xc.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))

            if (state.mountState) {
                Text(
                    text = stringResource(R.string.detect_builtin_mount_state),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Xc.colors.warning,
                )
                return@Column
            }

            if (!state.tee.available) {
                Text(
                    text = if (state.cliFailed) {
                        stringResource(R.string.detect_builtin_cli_failed)
                    } else {
                        stringResource(R.string.detect_builtin_no_ksud)
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Xc.colors.textSecondary,
                )
                return@Column
            }

            BuiltinSwitchRow(
                title = stringResource(R.string.detect_builtin_tee_title),
                summary = when {
                    state.tee.external -> stringResource(R.string.detect_builtin_tee_external)
                    state.tee.enabled && state.notInjected && state.engineAlive ->
                        stringResource(R.string.detect_builtin_not_injected)
                    state.tee.enabled && state.tee.processAlive ->
                        stringResource(R.string.detect_builtin_tee_on)
                    state.tee.enabled -> stringResource(R.string.detect_builtin_tee_on_down)
                    else -> stringResource(R.string.detect_builtin_tee_off)
                },
                checked = state.tee.enabled,
                switchEnabled = !busy,
                onCheckedChange = { act(if (it) "enable" else "disable", "tee", it) },
                accent = neon.accentCyan,
                mainColor = neon.textMain,
                subColor = neon.textSub,
            )
            Spacer(Modifier.height(10.dp))
            BuiltinSwitchRow(
                title = stringResource(R.string.detect_builtin_ta_title),
                summary = when {
                    state.ta.external -> stringResource(R.string.detect_builtin_ta_external)
                    state.ta.enabled && state.ta.processAlive ->
                        stringResource(R.string.detect_builtin_ta_on)
                    state.ta.enabled -> stringResource(R.string.detect_builtin_ta_on_down)
                    else -> stringResource(R.string.detect_builtin_ta_off)
                },
                checked = state.ta.enabled,
                switchEnabled = !busy,
                onCheckedChange = { act(if (it) "enable" else "disable", "ta", it) },
                accent = neon.accentCyan,
                mainColor = neon.textMain,
                subColor = neon.textSub,
            )

            // 诊断：资产完整性 / 内核侧 ksud / 启动失败原因 —— 三件事都可能让开关"看起来无效"。
            val diag = buildList {
                if (state.assetsBad) {
                    add("assets: tee=" + state.tee.assets + " ta=" + state.ta.assets)
                }
                if (state.runtimeBad) {
                    add("runtime: " + state.tee.runtimeAssets)
                }
                // sepolicy 打不上 = 注入必失败（keystore2 打不开要注入的 .so）。
                if (state.tee.enabled && state.tee.policy != "ok") {
                    add("policy: " + state.tee.policy)
                }
                if (state.startStep.isNotEmpty()) {
                    add("step=${state.startStep} rc=${state.startRc} ${state.startDetail}".trim())
                }
                if (state.taStartStep.isNotEmpty()) {
                    add("TA step=${state.taStartStep} rc=${state.taStartRc} ${state.taStartDetail}".trim())
                }
                if (state.daemonStarts.isNotEmpty() && state.daemonStarts != "0") {
                    add("daemon_starts=${state.daemonStarts}")
                }
                if (state.injectErr.isNotEmpty()) {
                    add(state.injectErr)
                }
            }
            if (diag.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.detect_builtin_diag) + "\n" +
                            diag.joinToString("\n"),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Xc.colors.textSecondary,
                )
            }

            // 引擎在跑却没注入、或者引擎起不来：一键修复（重打 keystore 侧 sepolicy +
            // 重新解密 + 干净重启注入链）。运行期打开开关最容易漏掉的就是第一步。
            if (state.tee.enabled && (!state.injected || state.assetsBad || state.runtimeBad)) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = stringResource(R.string.detect_builtin_repair),
                    onClick = { act("repair", "tee", true) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            // 两个开关独立，但 TA 的 keybox/名单维护是给引擎用的。
            if (state.ta.enabled && !state.tee.enabled && !state.tee.external) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.detect_builtin_ta_needs_tee),
                    fontSize = 12.sp,
                    color = Xc.colors.warning,
                )
            }

            // 内核侧（开机时执行的那份）ksud 不支持开关：重启后开关不会生效。
            if (state.bootKsud == "no" || state.bootKsud == "missing") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (state.bootKsud == "missing") {
                            R.string.detect_builtin_boot_ksud_missing
                        } else {
                            R.string.detect_builtin_boot_ksud_old
                        }
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Xc.colors.warning,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.detect_builtin_restart_note),
                fontSize = 11.sp,
                color = Xc.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BuiltinSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    switchEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    mainColor: Color,
    subColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = mainColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                color = subColor,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            enabled = switchEnabled,
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 四种状态色全部显式给：Miuix 默认轨道在深色玻璃卡片上与卡片几乎同色，
            // 开关的"开/关"读不出来。
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                uncheckedThumbColor = mainColor,
                uncheckedTrackColor = subColor.copy(alpha = 0.34f),
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.70f),
                disabledCheckedTrackColor = accent.copy(alpha = 0.45f),
                disabledUncheckedThumbColor = mainColor.copy(alpha = 0.55f),
                disabledUncheckedTrackColor = subColor.copy(alpha = 0.20f),
            ),
        )
    }
}

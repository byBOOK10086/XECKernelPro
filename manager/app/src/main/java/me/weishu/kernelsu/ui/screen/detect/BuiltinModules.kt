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
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 「内置模块」开关卡片：内置 TEE 引擎与内置 TA 增强，**两个互相独立**的开关。
 *
 * 为什么要有这一页：内置 TA/TEE 是随内核与 ksud 一起分发的能力，本体落在 tmpfs
 *（/dev/.xudc_hidden），模块页里根本看不到它；而在此之前它们是"开机必然运行"的，
 * 用户既没法关掉、也没法知道它跟自己刷的 TrickyStore / TA_enhanced 谁在管事——
 * 2026-10-15 用户报的"刷入 TA 和 tee 模块后重启，模块从列表里消失"就是这条链上的
 * 事故（内置那份会删掉它认为多余的 /data/adb/modules/<id>）。
 *
 * 现在的契约（ksud `builtin status|enable|disable`，开关落在
 * /data/adb/.xudc_secure/builtin.conf）：
 *   * 默认**关闭**：内置能力不再默认接管设备；
 *   * 两个键独立：开 TEE 不会顺带开 TA，反之亦然；
 *   * 开着的模块一旦发现用户自己装了同名/同类模块就让位，且**绝不**再删任何模块目录。
 */
/** 一次 root shell 取回两个模块的状态（ksud 的 JSON + 进程/外部模块事实）。 */
private val BUILTIN_STATUS_SCRIPT = listOf(
    "K=/data/adb/ksu/bin/ksud; [ -x \$K ] || K=/data/adb/xudc; [ -x \$K ] || K=ksud",
    "echo ksud=\$K",
    // JSON 压成一行：值里没有空格，压掉换行与缩进后管理器直接用 JSONObject 解析。
    "echo json=\$(\$K builtin status 2>/dev/null | tr -d '\\n\\r ')",
    // 进程事实：本项目的引擎叫 keymint_svc（去指纹），用户自己装的上游模块叫
    // TEESimulator；TA 的 daemon 是 staging 到 /data 的 ta-enhanced。
    "echo engine=\$(pidof keymint_svc 2>/dev/null || echo none)",
    "echo upstream=\$(pidof TEESimulator 2>/dev/null || echo none)",
    "echo tadaemon=\$(pidof ta-enhanced 2>/dev/null || echo none)",
).joinToString("\n")

/** 开/关一个内置模块（ksud 会同时改开关文件并立刻让设备侧生效）。 */
private fun builtinApplyScript(target: String, on: Boolean): String = listOf(
    "K=/data/adb/ksu/bin/ksud; [ -x \$K ] || K=/data/adb/xudc; [ -x \$K ] || K=ksud",
    "if \$K builtin ${if (on) "enable" else "disable"} $target >/dev/null 2>&1; then",
    "  echo ok=yes",
    "else",
    "  echo ok=no",
    "fi",
).joinToString("\n")

private data class BuiltinModuleState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val runtime: Boolean = false,
    val external: Boolean = false,
    val processAlive: Boolean = false,
)

private data class BuiltinState(
    val ksud: String = "",
    val tee: BuiltinModuleState = BuiltinModuleState(),
    val ta: BuiltinModuleState = BuiltinModuleState(),
)

@Composable
fun BuiltinModulesCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BuiltinState()) }
    var busy by remember { mutableStateOf(false) }
    val neon = XcNeon.colors

    suspend fun refresh() {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                withNewRootShell(true) {
                    ShellUtils.fastCmd(this, BUILTIN_STATUS_SCRIPT)
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
            )
        }

        val engineAlive = map["engine"].orEmpty().let { it.isNotEmpty() && it != "none" }
        val upstreamAlive = map["upstream"].orEmpty().let { it.isNotEmpty() && it != "none" }
        val taAlive = map["tadaemon"].orEmpty().let { it.isNotEmpty() && it != "none" }

        state = BuiltinState(
            ksud = map["ksud"].orEmpty(),
            tee = module("tee", engineAlive || upstreamAlive),
            ta = module("ta", taAlive),
        )
    }

    LaunchedEffect(Unit) { refresh() }

    fun apply(target: String, on: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, builtinApplyScript(target, on))
                    }
                }.getOrDefault("")
            }
            busy = false
            val ok = out.contains("ok=yes")
            Toast.makeText(
                context,
                context.getString(
                    when {
                        !ok -> R.string.detect_builtin_toggle_failed
                        on -> R.string.detect_builtin_toggle_on
                        else -> R.string.detect_builtin_toggle_off
                    }
                ),
                Toast.LENGTH_SHORT,
            ).show()
            refresh()
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

            if (!state.tee.available) {
                Text(
                    text = stringResource(R.string.detect_builtin_no_ksud),
                    fontSize = 13.sp,
                    color = Xc.colors.textSecondary,
                )
                return@Column
            }

            BuiltinSwitchRow(
                title = stringResource(R.string.detect_builtin_tee_title),
                summary = when {
                    state.tee.external -> stringResource(R.string.detect_builtin_tee_external)
                    state.tee.enabled && state.tee.processAlive ->
                        stringResource(R.string.detect_builtin_tee_on)
                    state.tee.enabled -> stringResource(R.string.detect_builtin_tee_on_down)
                    else -> stringResource(R.string.detect_builtin_tee_off)
                },
                checked = state.tee.enabled,
                switchEnabled = !busy,
                onCheckedChange = { apply("tee", it) },
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
                onCheckedChange = { apply("ta", it) },
                accent = neon.accentCyan,
                mainColor = neon.textMain,
                subColor = neon.textSub,
            )

            // 两个开关独立，但 TA 的 keybox/名单维护是给引擎用的：内置 TEE 关着、
            // 也没装外部模块时把这件事说清楚，免得用户以为"开了 TA 就有人拦"。
            if (state.ta.enabled && !state.tee.enabled && !state.tee.external) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.detect_builtin_ta_needs_tee),
                    fontSize = 12.sp,
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
        Column(modifier = Modifier.weight(1f)) {
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

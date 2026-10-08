package me.weishu.kernelsu.ui.screen.detect

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import me.weishu.kernelsu.ui.util.withNewRootShell
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 「TEE 引擎与拦截」实测看板。
 *
 * 存在的理由：引擎的拦截是**穷举白名单**（ConfigurationManager 的 shouldSkipUid），
 * 没列进来的包会原样拿到真实 TEE 证明——表现出来就是验机工具报"未知认证根证书 /
 * 无效的信任根状态"，而设备上没有任何界面能看出"到底拦没拦"。内置模块又落在
 * tmpfs（/dev/.xudc_hidden），在模块页里根本不可见，于是只能靠 ADB 读日志。
 *
 * 这里把决定成败的四件事直接读出来：引擎进程、当前 keybox、白名单条目与内置验机
 * 工具是否被覆盖、锁态属性实际值；覆盖缺失时给一键补进名单的按钮。
 */
private const val TRICKY_RUNTIME = "/data/adb/tricky_store"
private const val TRICKY_DATA = "/data/adb/.xudc_secure"
private const val BUNDLED_CHECKER = "wu.keyChain.test"

/** 一次 root shell 取回全部事实，按 KEY=VALUE 逐行解析（避免多条命令的往返开销）。 */
private val STATUS_SCRIPT = listOf(
    "TK=$TRICKY_RUNTIME",
    // 引擎只往 logcat 写（tag=TEESimulator），.engine.log 只有 service.sh 的快照；
    // 想要"引擎到底交出了什么锁态"必须读 logcat。这里只取一次，后面复用。
    "LC=\$(/system/bin/logcat -d -s TEESimulator:* 2>/dev/null | tail -n 500)",
    "echo engine=\$(pidof TEESimulator 2>/dev/null || echo none)",
    "echo enginefiles=\$([ -f \$TK/daemon ] && [ -f \$TK/classes.dex ] && [ -f \$TK/libTEESimulator.so ] && [ -x \$TK/inject ] && echo ok || echo missing)",
    "echo deviceid=\$(sed -n 's/.*DeviceID=\"\\([^\"]*\\)\".*/\\1/p' \$TK/keybox.xml 2>/dev/null | head -n 1)",
    "echo kbperm=\$(stat -c %a \$TK/keybox.xml 2>/dev/null)",
    "echo target=\$(grep -cvE '^[[:space:]]*(#|\$)' \$TK/target.txt 2>/dev/null)",
    // 覆盖检查容忍 `包名!` / `包名?` 与行尾空白：引擎把模式后缀当同一目标，逐字比较会误报。
    "echo checker=\$(grep -qE '^[[:space:]]*wu\\.keyChain\\.test[!?]?[[:space:]]*\$' \$TK/target.txt 2>/dev/null && echo yes || echo no)",
    "echo vvb=\$(grep -qE '^[[:space:]]*io\\.github\\.vvb2060\\.keyattestation[!?]?[[:space:]]*\$' \$TK/target.txt 2>/dev/null && echo yes || echo no)",
    "echo servrot=\$(printf '%s\\n' \"\$LC\" | grep -o 'Served RootOfTrust:.*' | tail -n 1)",
    "echo patcherr=\$(printf '%s\\n' \"\$LC\" | grep -c -E 'Failed to patch|No keybox found|Failed to modify hardware')",
    "echo lasterr=\$(printf '%s\\n' \"\$LC\" | grep -E ' E TEESimulator' | tail -n 1)",
    "echo vbs=\$(getprop ro.boot.verifiedbootstate)",
    "echo locked=\$(getprop ro.boot.flash.locked)",
    "echo devstate=\$(getprop ro.boot.vbmeta.device_state)",
    "echo zeromount=\$([ -d /data/adb/modules/meta-zeromount ] && [ ! -f /data/adb/modules/meta-zeromount/disable ] && echo yes || echo no)",
).joinToString("\n")

/** 把内置验机工具补进白名单，并同步管理器副本（引擎会在文件变化后自动重载）。 */
private val ADD_CHECKER_SCRIPT = listOf(
    "TK=$TRICKY_RUNTIME",
    "T=\$TK/target.txt",
    "mkdir -p \$TK " + TRICKY_DATA,
    "[ -f \$T ] || : > \$T",
    "if ! grep -qE '^[[:space:]]*wu\\.keyChain\\.test[!?]?[[:space:]]*\$' \$T; then",
    "  cp -f \$T \$T.new.\$\$ && printf '\\n[keybox.xml]\\n$BUNDLED_CHECKER\\n' >> \$T.new.\$\$ && chmod 644 \$T.new.\$\$ && mv -f \$T.new.\$\$ \$T",
    "fi",
    "cp -f \$T " + TRICKY_DATA + "/target.txt 2>/dev/null",
    "chmod 644 \$T " + TRICKY_DATA + "/target.txt 2>/dev/null",
    "grep -qE '^[[:space:]]*wu\\.keyChain\\.test[!?]?[[:space:]]*\$' \$T && echo added=yes || echo added=no",
).joinToString("\n")

private data class TeeStatus(
    val engine: String = "",
    val engineFilesOk: Boolean = false,
    val deviceId: String = "",
    val kbPerm: String = "",
    val targetCount: Int = 0,
    val checkerCovered: Boolean = false,
    val vvbCovered: Boolean = false,
    val servedRootOfTrust: String = "",
    val patchErrors: Int = 0,
    val lastEngineError: String = "",
    val verifiedBoot: String = "",
    val flashLocked: String = "",
    val deviceState: String = "",
    val zeroMount: Boolean = false,
) {
    val engineAlive: Boolean get() = engine.isNotEmpty() && engine != "none"
    /** 引擎日志里那句 "Served RootOfTrust: deviceLocked=true verifiedBootState=0 …"。 */
    val servedLocked: Boolean get() = servedRootOfTrust.contains("deviceLocked=true")
    val servedVerified: Boolean get() = servedRootOfTrust.contains("verifiedBootState=0")
}

@Composable
fun TeeStatusCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TeeStatus?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                withNewRootShell(true) {
                    ShellUtils.fastCmd(this, STATUS_SCRIPT)
                }
            }.getOrDefault("")
        }
        val map = out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()
        status = TeeStatus(
            engine = map["engine"].orEmpty(),
            engineFilesOk = map["enginefiles"] == "ok",
            deviceId = map["deviceid"].orEmpty(),
            kbPerm = map["kbperm"].orEmpty(),
            targetCount = map["target"].orEmpty().toIntOrNull() ?: 0,
            checkerCovered = map["checker"] == "yes",
            vvbCovered = map["vvb"] == "yes",
            servedRootOfTrust = map["servrot"].orEmpty(),
            patchErrors = map["patcherr"].orEmpty().toIntOrNull() ?: 0,
            lastEngineError = map["lasterr"].orEmpty(),
            verifiedBoot = map["vbs"].orEmpty(),
            flashLocked = map["locked"].orEmpty(),
            deviceState = map["devstate"].orEmpty(),
            zeroMount = map["zeromount"] == "yes",
        )
    }

    LaunchedEffect(Unit) { refresh() }

    fun addChecker() {
        if (busy) return
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, ADD_CHECKER_SCRIPT)
                    }.contains("added=yes")
                }.getOrDefault(false)
            }
            busy = false
            Toast.makeText(
                context,
                context.getString(
                    if (ok) R.string.detect_tee_add_done else R.string.detect_tee_add_failed
                ),
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) refresh()
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
                text = stringResource(R.string.detect_tee_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.text,
            )
            Spacer(Modifier.height(8.dp))

            val current = status
            if (current == null) {
                Text(
                    text = stringResource(R.string.detect_checking),
                    fontSize = 13.sp,
                    color = Xc.colors.textSecondary,
                )
                return@Column
            }

            // 引擎进程：不在 = keystore 完全没被接管，任何 keybox / 属性伪装都无意义。
            StatusRow(
                label = stringResource(R.string.detect_tee_engine),
                value = if (current.engineAlive) {
                    stringResource(R.string.detect_tee_running) + " · pid " + current.engine
                } else {
                    stringResource(R.string.detect_tee_not_running)
                },
                bad = !current.engineAlive,
            )
            // 当前 keybox：DeviceID 带 yurikey 代次，权限应为 644（读取方在 keystore2 进程）。
            StatusRow(
                label = stringResource(R.string.detect_tee_keybox),
                value = listOfNotNull(
                    current.deviceId.ifEmpty { null },
                    current.kbPerm.ifEmpty { null }?.let { "chmod $it" },
                ).joinToString(" · ").ifEmpty { "—" },
                bad = current.kbPerm.isNotEmpty() && current.kbPerm != "644",
            )
            // 白名单覆盖：这是"检测方到底会不会被拦截"的直接答案。
            StatusRow(
                label = stringResource(R.string.detect_tee_coverage),
                value = if (current.checkerCovered) {
                    stringResource(R.string.detect_tee_covered_fmt, current.targetCount)
                } else {
                    stringResource(R.string.detect_tee_not_covered_fmt, current.targetCount)
                },
                bad = !current.checkerCovered,
            )
            StatusRow(
                label = stringResource(R.string.detect_tee_bootstate),
                value = "verifiedbootstate=${current.verifiedBoot.ifEmpty { "?" }} · " +
                        "flash.locked=${current.flashLocked.ifEmpty { "?" }} · " +
                        "device_state=${current.deviceState.ifEmpty { "?" }}",
                bad = current.verifiedBoot != "green" || current.flashLocked != "1",
            )
            // 引擎资产是否真的落到运行时目录：缺一个文件引擎就起不来，而模块本体在
            // tmpfs（/dev/.xudc_hidden）里，模块页看不到任何异常。
            StatusRow(
                label = stringResource(R.string.detect_tee_engine_files),
                value = if (current.engineFilesOk) {
                    stringResource(R.string.detect_tee_files_ok)
                } else {
                    stringResource(R.string.detect_tee_files_missing)
                },
                bad = !current.engineFilesOk,
            )
            // 引擎自己写下的"我给出的锁态"——证书里 RootOfTrust 的真实取值。
            // 检测方报"密钥正常但引导加载程序已解锁"时，这一行是分辨两种原因的关键：
            // 这里是 true/0 = 引擎确实伪造成功，问题在检测方读了别的地方；
            // 这里是 false/2 或根本没有记录 = 引擎没接管过这个包。
            StatusRow(
                label = stringResource(R.string.detect_tee_served_rot),
                value = current.servedRootOfTrust.ifEmpty {
                    stringResource(R.string.detect_tee_served_rot_none)
                },
                bad = current.servedRootOfTrust.isNotEmpty() &&
                        (!current.servedLocked || !current.servedVerified),
            )
            StatusRow(
                label = stringResource(R.string.detect_tee_patch_errors),
                value = if (current.patchErrors == 0) {
                    stringResource(R.string.detect_tee_patch_errors_none)
                } else {
                    current.patchErrors.toString() + " · " + current.lastEngineError.take(120)
                },
                bad = current.patchErrors > 0,
            )
            if (!current.vvbCovered) {
                Text(
                    text = stringResource(R.string.detect_tee_vvb_missing),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Xc.colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (current.zeroMount) {
                Text(
                    text = stringResource(R.string.detect_tee_zeromount_note),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Xc.colors.warning,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !current.engineAlive -> stringResource(R.string.detect_tee_engine_down_note)
                    current.patchErrors > 0 -> stringResource(R.string.detect_tee_patch_failed_note)
                    else -> stringResource(R.string.detect_tee_note)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Xc.colors.textSecondary,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.detect_tee_refresh),
                    onClick = { scope.launch { refresh() } },
                    enabled = !busy,
                )
                if (!current.checkerCovered) {
                    TextButton(
                        text = stringResource(R.string.detect_tee_add),
                        onClick = { addChecker() },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, bad: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (bad) Xc.colors.danger else Xc.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

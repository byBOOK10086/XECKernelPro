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
import me.weishu.kernelsu.ui.util.ExternalModules
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
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
 * 这里把决定成败的五件事直接读出来：引擎进程、当前 keybox、**链末根证书是不是
 * Google 证明根**、白名单条目与内置验机工具是否被覆盖、锁态属性实际值；
 * 覆盖缺失时给一键补进名单的按钮。
 */
private val TRICKY_RUNTIME = DetectTargets.TRICKY_RUNTIME

/** 一次 root shell 取回全部事实，按 KEY=VALUE 逐行解析（避免多条命令的往返开销）。 */
private val STATUS_SCRIPT = listOf(
    "TK=$TRICKY_RUNTIME",
    // 引擎只往 logcat 写（tag=TEESimulator），.engine.log 只有 service.sh 的快照；
    // 想要"引擎到底交出了什么锁态"必须读 logcat。这里只取一次，后面复用。
    "LC=\$(/system/bin/logcat -d -s keymint_svc:* 2>/dev/null | tail -n 500)",
    "echo engine=\$(pidof keymint_svc 2>/dev/null || echo none)",
    "echo enginefiles=\$([ -f \$TK/daemon ] && [ -f \$TK/classes.dex ] && [ -f \$TK/libTEESimulator.so ] && [ -x \$TK/inject ] && echo ok || echo missing)",
    "echo deviceid=\$(sed -n 's/.*DeviceID=\"\\([^\"]*\\)\".*/\\1/p' \$TK/keybox.xml 2>/dev/null | head -n 1)",
    "echo kbperm=\$(stat -c %a \$TK/keybox.xml 2>/dev/null)",
    // 链末（根）证书判定：由 tricky_store 的 service.sh 与 TA_enhanced 的看护循环写进
    // .keybox_root（格式 "google|other|missing|unknown <命中>/<总数> <DeviceID> <sha16>"）。
    // 管理器不自己算哈希：root shell 里拼 awk/sha256sum 出错是静默的。
    "echo kbroot=\$(cat \$TK/.keybox_root 2>/dev/null)",
    "echo target=\$(grep -cvE '^[[:space:]]*(#|\$)' \$TK/target.txt 2>/dev/null)",
    // 覆盖检查容忍 `包名!` / `包名?` 与行尾空白：引擎把模式后缀当同一目标，逐字比较会误报。
    "echo checker=\$(grep -qE '^[[:space:]]*wu\\.keyChain\\.test[!?]?[[:space:]]*\$' \$TK/target.txt 2>/dev/null && echo yes || echo no)",
    "echo vvb=\$(grep -qE '^[[:space:]]*io\\.github\\.vvb2060\\.keyattestation[!?]?[[:space:]]*\$' \$TK/target.txt 2>/dev/null && echo yes || echo no)",
    // 设备侧自检结论（tricky_store/service.sh 的 tee_selfcheck 写的 KEY=VALUE 文件）：
    // 它回答的是"现在到底拦不拦得住"，而上面那些行只回答"各部分看起来怎么样"。
    // 一行读回来（用 | 拼），字段少、解析简单，避免在 root shell 里做多次往返。
    "SC=/data/adb/.xudc_secure/tee-selfcheck.txt",
    "echo sc=\$(sed -n '1,20p' \$SC 2>/dev/null | tr '\\n' '|')",
    "echo servrot=\$(printf '%s\\n' \"\$LC\" | grep -o 'Served RootOfTrust:.*' | tail -n 1)",
    "echo patcherr=\$(printf '%s\\n' \"\$LC\" | grep -c -E 'Failed to patch|No keybox found|Failed to modify hardware')",
    "echo lasterr=\$(printf '%s\\n' \"\$LC\" | grep -E ' E TEESimulator' | tail -n 1)",
    "echo vbs=\$(getprop ro.boot.verifiedbootstate)",
    "echo locked=\$(getprop ro.boot.flash.locked)",
    "echo devstate=\$(getprop ro.boot.vbmeta.device_state)",
    "echo zeromount=\$([ -d /data/adb/modules/meta-zeromount ] && [ ! -f /data/adb/modules/meta-zeromount/disable ] && echo yes || echo no)",
    // 拦截归属：外部 TEE 模块在不在位、内置模块有没有落地。这两个事实决定了
    // "引擎没起来"到底是"没装"还是"外部占位但它的引擎是坏的"（后者在模块列表里看不出来）。
    //
    // 判据与设备侧 tricky_store/service.sh 的 external_engine_active / engine_alive 对齐：
    // 目录名是一种，**进程名也是一种** —— 用户装的可能是上游 TEESimulator / TrickyStore，
    // 它的引擎进程叫 TEESimulator，模块目录名也不一定叫 tricky_store。只看目录名会把
    // "外部引擎正在拦"报成"内置被用户关掉了"。
    "EXT=no",
    "[ -f /data/adb/modules/tricky_store/module.prop ] && [ ! -f /data/adb/modules/tricky_store/disable ] && [ ! -f /data/adb/modules/tricky_store/remove ] && EXT=yes",
    "[ -n \"\$(pidof TEESimulator 2>/dev/null)\" ] && EXT=yes",
    "echo ext=\$EXT",
    "echo extver=\$(sed -n 's/^versionCode=//p' /data/adb/modules/tricky_store/module.prop 2>/dev/null | head -n 1)",
    "echo builtin=\$([ -f /dev/.xudc_hidden/tricky_store/service.sh ] && echo yes || echo no)",
    // 用户开关（检测页「内置模块」那两个开关写的文件）。**缺文件/缺键 = 开启**
    //（v31121 的行为，用户 2026-10-16 裁决；只有明确的 0/false/off/no 才算关闭）。
    // 关掉内置 TEE 之后"引擎没在跑"不再是故障而是用户的选择，这一行让看板能把两者分开说。
    "echo teeon=\$(grep -qiE '^[[:space:]]*tee[[:space:]]*=[[:space:]]*(0|false|off|no|disable|disabled)[[:space:]]*\$' /data/adb/.xudc_secure/builtin.conf 2>/dev/null && echo no || echo yes)",
    // 注入是否**真的**成功：keystore2 的 maps 里有没有我们的 .so。这是整条链唯一的
    // 硬证据 —— 引擎进程活着不等于在拦（守护进程注入失败后会 5 次重试然后 exit，
    // supervisor 再拉起，采样点撞上就误报"运行中"）。
    "KSP=\$(pidof keystore2 2>/dev/null | awk '{print \$1}')",
    "if [ -n \"\$KSP\" ] && grep -q libTEESimulator /proc/\$KSP/maps 2>/dev/null; then echo injected=yes; else echo injected=no; fi",
    // 启动诊断：service.sh 把"走到哪一步、结果如何"写进 engine-start.txt（开机/手动/修复
    // 三种模式都写）。引擎起不来时这一行就是答案。
    "echo start=\$(sed -n '1,20p' /data/adb/.xudc_secure/engine-start.txt 2>/dev/null | tr '\\n' '|')",
).joinToString("\n")

/**
 * 立即把锁态属性写成回锁视图。属性类检测方（读 getprop 的那些）当场就能通过；
 * 证书类检测方（密钥认证）靠的还是引擎伪造 RootOfTrust，这一步与它无关。
 */
private val RELOCK_SCRIPT = listOf(
    "K=/data/adb/ksu/bin/ksud; [ -x \$K ] || K=/data/adb/xudc; [ -x \$K ] || K=ksud",
    "for p in ro.boot.verifiedbootstate=green ro.boot.flash.locked=1 ro.boot.vbmeta.device_state=locked ro.boot.veritymode=enforcing; do",
    "  \$K resetprop -n \${p%%=*} \${p#*=} >/dev/null 2>&1",
    "done",
    "echo relock=\$(getprop ro.boot.verifiedbootstate)/\$(getprop ro.boot.flash.locked)/\$(getprop ro.boot.vbmeta.device_state)",
).joinToString("\n")

/**
 * 「自检并修复」：**不在管理器里重算**，而是给设备侧一个触发文件，让模块的自检循环
 * （tricky_store/service.sh 的 tee_selfcheck）立刻跑一轮 —— 引擎重启、keybox 换回、
 * 名单补齐、属性回写都在那一边，管理器只负责按开关和读结论。
 *
 * 这样做的理由：root shell 里拼 awk/sha256sum/logcat 出错是**静默**的，一旦管理器
 * 自己算，设备上就有两份"自检实现"，两边结论不一致时用户只会更迷糊。
 */
private val SELFCHECK_SCRIPT = listOf(
    "SC=/data/adb/.xudc_secure/tee-selfcheck.txt",
    "T=/data/adb/.xudc_secure/.tee-selfcheck-now",
    "touch \$T 2>/dev/null",
    "i=0",
    "while [ \$i -lt 15 ]; do",
    "  sleep 1",
    "  i=\$((i + 1))",
    "  [ -f \$T ] || break",
    "done",
    "sed -n 's/^verdict=/verdict=/p' \$SC 2>/dev/null",
).joinToString("\n")

private data class TeeStatus(
    val engine: String = "",
    val engineFilesOk: Boolean = false,
    val deviceId: String = "",
    val kbPerm: String = "",
    val kbRoot: String = "",
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
    val externalModule: Boolean = false,
    val externalVersion: String = "",
    val builtinPresent: Boolean = false,
    /** 用户开关：检测页「内置模块」里的内置 TEE 是不是开着（/data/adb/.xudc_secure/builtin.conf）。 */
    val builtinEnabled: Boolean = false,
    /** 注入是否成功：keystore2 的 maps 里有我们的 libTEESimulator.so（唯一硬证据）。 */
    val injected: Boolean = false,
    /** service.sh 写的启动诊断（engine-start.txt）的 step/rc/detail。 */
    val startStep: String = "",
    val startRc: String = "",
    val startDetail: String = "",
    /**
     * 设备侧自检（`tee-selfcheck.txt`）的全部字段。
     *
     * 上面那些行是"各部分看起来怎么样"，这张表是设备自己算出来的**结论**：
     * verdict 直接回答"现在拦不拦得住"，served 回答"引擎到底服务过请求没有"，
     * repair 回答"它自己修了什么"。
     */
    val selfcheck: Map<String, String> = emptyMap(),
) {
    val engineAlive: Boolean get() = engine.isNotEmpty() && engine != "none"

    /**
     * 最坏形态：外部模块占住了注入面（内置按设计让位），但它的引擎根本没起来 ——
     * 于是全设备零拦截，检测方拿到的永远是真实 TEE 证明（未锁定 + 未知信任根）。
     */
    val externalDeadlock: Boolean get() = externalModule && !engineAlive

    /**
     * 内置引擎该在跑却没跑。
     *
     * 与 [externalDeadlock] 的区别：这一种是**没有任何外部模块占位**时引擎自己没起来
     * （资产没落盘、supervisor 崩了、注入失败、被系统杀掉而看护还没轮到）。用户看到的表现
     * 与前者完全一样（验机报"引导加载程序已解锁 / 信任根异常"，而模块列表里一切正常），
     * 但原来看板只在"外部模块占位"时才给一键接管按钮 —— 这一种形态**没有任何按钮可点**，
     * 只能干瞪眼。所以按钮的判据改成"引擎没跑且内置资产在位"。
     */
    val builtinDown: Boolean get() = builtinPresent && !engineAlive

    /**
     * 内置 TEE 被用户自己关掉了（检测页「内置模块」的开关，默认就是关）。
     *
     * 这一种"引擎没在跑"不是故障：用户选了不拦截。看板必须把它与
     * [builtinDown]（该跑却没跑）分开说，否则用户会看到一个永远修不好的红行。
     *
     * 还要排除第三种情况：**别的引擎正在跑**（用户装的 TEESimulator 之类，进程名不是
     * keymint_svc）。那时"内置关着"只是背景，拦截是有人在做的，不能报成"按你的选择停用"。
     */
    val builtinDisabled: Boolean get() = !builtinEnabled && !externalModule && !engineAlive

    /**
     * 引擎在跑，但**注入没成功**（keystore2 的 maps 里没有我们的 .so）。
     *
     * 这是最容易被误判成"已经好了"的形态：进程活着、pidof 有值，但 keystore 完全没被
     * 接管，检测方拿到的仍是真实 TEE 证明。守护进程注入失败后会重试 5 次然后 exit(1)，
     * supervisor 再把它拉起来 —— 采样点撞上就报"运行中"，撞不上就报"引擎没跑"，
     * 两种都不说明原因。
     */
    val notInjected: Boolean get() = engineAlive && !injected
    /** 引擎日志里那句 "Served RootOfTrust: deviceLocked=true verifiedBootState=0 …"。 */
    val servedLocked: Boolean get() = servedRootOfTrust.contains("deviceLocked=true")
    val servedVerified: Boolean get() = servedRootOfTrust.contains("verifiedBootState=0")

    /** .keybox_root 的第一个字段：google / expired / other / missing / unknown。 */
    val rootState: String get() = kbRoot.trim().split(' ').firstOrNull().orEmpty()
    /** "命中/总数"，例如 2/2。 */
    val rootRatio: String get() = kbRoot.trim().split(' ').getOrNull(1).orEmpty()

    /** 设备自检的结论：ok / engine_down / keybox_expired / coverage_missing / locked_props_missing … */
    val scVerdict: String get() = selfcheck["verdict"].orEmpty()
    /** 引擎服务过的证明请求数（-1 = 自检文件还没生成）。 */
    val scServed: Int get() = selfcheck["served"]?.toIntOrNull() ?: -1
    /** 设备侧这一轮自己修了什么（逗号分隔；none = 没修）。 */
    val scRepair: String get() = selfcheck["repair"].orEmpty()
    /** 自检文件的时间戳（秒）；0 = 没有。 */
    val scAt: Long get() = selfcheck["at"]?.toLongOrNull() ?: 0L
    val scPresent: Boolean get() = scVerdict.isNotEmpty()
}

@Composable
fun TeeStatusCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TeeStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 管理器自带那份 ksud（APK 里的 libxeccli.so）：设备侧那份是内核带着装的，
    // 只更新管理器时它可能还是旧的，不认 `builtin` 子命令。
    val cli = remember { getKsuDaemonPath() }

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
        // 设备侧写的都是 KEY=VALUE 换行文件，这里统一用 `|` 拼成一行再解析。
        fun backfill(raw: String): Map<String, String> = raw.split('|')
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
            }
            .toMap()
        val startMap = backfill(map["start"].orEmpty())

        status = TeeStatus(
            engine = map["engine"].orEmpty(),
            engineFilesOk = map["enginefiles"] == "ok",
            deviceId = map["deviceid"].orEmpty(),
            kbPerm = map["kbperm"].orEmpty(),
            targetCount = map["target"].orEmpty().toIntOrNull() ?: 0,
            checkerCovered = map["checker"] == "yes",
            vvbCovered = map["vvb"] == "yes",
            kbRoot = map["kbroot"].orEmpty(),
            servedRootOfTrust = map["servrot"].orEmpty(),
            patchErrors = map["patcherr"].orEmpty().toIntOrNull() ?: 0,
            lastEngineError = map["lasterr"].orEmpty(),
            verifiedBoot = map["vbs"].orEmpty(),
            flashLocked = map["locked"].orEmpty(),
            deviceState = map["devstate"].orEmpty(),
            zeroMount = map["zeromount"] == "yes",
            externalModule = map["ext"] == "yes",
            externalVersion = map["extver"].orEmpty(),
            builtinPresent = map["builtin"] == "yes",
            builtinEnabled = map["teeon"] == "yes",
            injected = map["injected"] == "yes",
            startStep = startMap["step"].orEmpty(),
            startRc = startMap["rc"].orEmpty(),
            startDetail = startMap["detail"].orEmpty(),
            selfcheck = backfill(map["sc"].orEmpty()),
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
                        ShellUtils.fastCmd(this, DetectTargets.ADD_CHECKER_SCRIPT)
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

    /**
     * 跑一段修复脚本（接管引擎 / 回锁属性），把最后一行有效输出弹给用户，然后重读数。
     * 失败时退回一句通用文案，绝不让用户对着一个没反应的按钮猜。
     */
    fun runRepair(script: String, fallback: Int) {
        if (busy) return
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) { ShellUtils.fastCmd(this, script) }
                }.getOrDefault("")
            }
            busy = false
            val line = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            Toast.makeText(
                context,
                line ?: context.getString(fallback),
                Toast.LENGTH_LONG,
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

            // 设备侧自检结论放最上面：它是唯一一句"现在到底拦不拦得住"的答案，
            // 下面那些行都是它的证据。用户按验机工具的红字来查时，先看这一行。
            if (current.scPresent) {
                StatusRow(
                    label = stringResource(R.string.detect_tee_selfcheck),
                    value = when (current.scVerdict) {
                        "ok" -> if (current.scServed == 0) {
                            // 引擎活着、各项都正常，但一条证明请求都没服务过：
                            // 说明还没有任何白名单应用查过，不能算"已验证通过"。
                            stringResource(R.string.detect_tee_sc_idle)
                        } else {
                            stringResource(R.string.detect_tee_sc_ok, current.scServed)
                        }
                        // 用户关掉了内置 TEE：设备侧自检会写这个结论（service.sh --stop），
                        // 它是"按你的选择停用"，不是故障，所以不标红。
                        "disabled" -> stringResource(R.string.detect_tee_sc_disabled)
                        "engine_down" -> stringResource(R.string.detect_tee_sc_engine_down)
                        // 引擎在跑却没注入：keystore2 没被接管，检测方拿到的仍是真实 TEE。
                        "not_injected" -> stringResource(R.string.detect_tee_sc_not_injected)
                        "keybox_expired" -> stringResource(R.string.detect_tee_sc_keybox_expired)
                        "keybox_other", "keybox_missing", "keybox_unknown" ->
                            stringResource(R.string.detect_tee_sc_keybox_bad, current.scVerdict)
                        "coverage_missing" -> stringResource(R.string.detect_tee_sc_coverage)
                        "locked_props_missing" -> stringResource(R.string.detect_tee_sc_props)
                        else -> current.scVerdict
                    },
                    bad = current.scVerdict != "ok" && current.scVerdict != "disabled",
                )
                if (current.scRepair.isNotEmpty() && current.scRepair != "none") {
                    StatusRow(
                        label = stringResource(R.string.detect_tee_selfcheck_repair),
                        value = current.scRepair.trimEnd(','),
                        bad = false,
                    )
                }
            } else {
                StatusRow(
                    label = stringResource(R.string.detect_tee_selfcheck),
                    // 内置 TEE 被用户关掉时，"没有自检记录"不是待办事项，而是它的自然状态。
                    value = if (current.builtinDisabled) {
                        stringResource(R.string.detect_tee_sc_disabled)
                    } else {
                        stringResource(R.string.detect_tee_sc_absent)
                    },
                    bad = !current.builtinDisabled,
                )
            }

            // 引擎进程：不在 = keystore 完全没被接管，任何 keybox / 属性伪装都无意义。
            // 用户主动关掉内置 TEE 时这一行不标红：那是他选的，不是故障。
            StatusRow(
                label = stringResource(R.string.detect_tee_engine),
                value = if (current.engineAlive) {
                    stringResource(R.string.detect_tee_running) + " · pid " + current.engine
                } else if (current.builtinDisabled) {
                    stringResource(R.string.detect_builtin_off_short)
                } else {
                    stringResource(R.string.detect_tee_not_running)
                },
                bad = !current.engineAlive && !current.builtinDisabled,
            )
            // 注入：**这才是"到底拦不拦得住"的第一现场**。进程活着但 maps 里没有我们的
            // .so = 零拦截（守护进程注入失败后会重试 5 次再 exit，supervisor 又拉起，
            // 只看 pidof 会时红时绿，指不到真正的原因）。
            StatusRow(
                label = stringResource(R.string.detect_tee_inject),
                value = when {
                    current.injected -> stringResource(R.string.detect_tee_injected)
                    current.builtinDisabled ->
                        stringResource(R.string.detect_builtin_off_short)
                    current.engineAlive -> stringResource(R.string.detect_tee_not_injected)
                    else -> stringResource(R.string.detect_tee_inject_unknown)
                },
                bad = !current.injected && !current.builtinDisabled,
            )
            // 启动诊断：service.sh 记的"走到哪一步、结果如何"（引擎起不来时的答案）。
            if (current.startStep.isNotEmpty()) {
                StatusRow(
                    label = stringResource(R.string.detect_tee_start_diag),
                    value = "step=${current.startStep} rc=${current.startRc} ${current.startDetail}".trim(),
                    bad = current.startRc == "1" && !current.builtinDisabled,
                )
            }
            // 拦截归属：引擎没起来时，这一行回答"为什么"。外部模块占位（内置按设计
            // 让位）而它的引擎没跑，就是"模块列表看着正常、实际零拦截"的最坏形态。
            StatusRow(
                label = stringResource(R.string.detect_tee_owner),
                value = when {
                    // 用户自己把内置 TEE 关了：这是选择，不是异常，先说清楚再说别的。
                    current.builtinDisabled -> stringResource(R.string.detect_tee_owner_builtin_disabled)
                    current.externalDeadlock -> stringResource(
                        R.string.detect_tee_owner_deadlock,
                        current.externalVersion.ifEmpty { "?" },
                    )
                    current.externalModule -> stringResource(
                        R.string.detect_tee_owner_external,
                        current.externalVersion.ifEmpty { "?" },
                    )
                    // 引擎活着但没注入：归属这一行必须说清"它在跑，但没接管 keystore"。
                    current.engineAlive && !current.injected ->
                        stringResource(R.string.detect_tee_owner_builtin_noinject)
                    current.engineAlive -> stringResource(R.string.detect_tee_owner_builtin)
                    // 内置资产在位但引擎没跑：没有外部模块背锅，就是它自己没起来。
                    current.builtinPresent -> stringResource(R.string.detect_tee_owner_builtin_down)
                    else -> stringResource(R.string.detect_tee_owner_none)
                },
                bad = !current.engineAlive && !current.builtinDisabled,
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
            // 根证书：引擎签链用的是 keybox 的链，链末那张就是检测方眼里的"根"。
            // 内置验机工具只拿链末证书的公钥与硬编码的 Google/AOSP/Knox 根公钥比对，
            // 对不上就报"未知根证书"（而链照样签得出来 → 同时显示"已锁定"）。
            // 判据由设备侧的 shell 层算好写进 .keybox_root，这里只展示。
            StatusRow(
                label = stringResource(R.string.detect_tee_root),
                value = when {
                    // 内置 TEE 关着、也没有外部模块：这一行读到的是上一次留下的状态，
                    // 说"无 keybox / 非 Google 根"只会让人误以为坏了。
                    current.builtinDisabled && current.rootState != "google" ->
                        stringResource(R.string.detect_builtin_off_short)
                    current.rootState == "google" ->
                        stringResource(R.string.detect_tee_root_google, current.rootRatio)
                    current.rootState == "expired" ->
                        stringResource(R.string.detect_tee_root_expired, current.rootRatio)
                    current.rootState == "other" ->
                        stringResource(R.string.detect_tee_root_other, current.rootRatio)
                    current.rootState == "missing" -> stringResource(R.string.detect_tee_root_missing)
                    else -> stringResource(R.string.detect_tee_root_unknown)
                },
                bad = current.rootState != "google" && !current.builtinDisabled,
            )
            // 白名单覆盖：这是"检测方到底会不会被拦截"的直接答案。
            StatusRow(
                label = stringResource(R.string.detect_tee_coverage),
                value = if (current.builtinDisabled) {
                    stringResource(R.string.detect_builtin_off_short)
                } else if (current.checkerCovered) {
                    stringResource(R.string.detect_tee_covered_fmt, current.targetCount)
                } else {
                    stringResource(R.string.detect_tee_not_covered_fmt, current.targetCount)
                },
                bad = !current.checkerCovered && !current.builtinDisabled,
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
                value = when {
                    current.engineFilesOk -> stringResource(R.string.detect_tee_files_ok)
                    // 关掉内置 TEE 之后运行目录会被清掉，"文件缺失"是预期结果。
                    current.builtinDisabled -> stringResource(R.string.detect_builtin_off_short)
                    else -> stringResource(R.string.detect_tee_files_missing)
                },
                bad = !current.engineFilesOk && !current.builtinDisabled,
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
                // 关掉之后 logcat 里留着上一轮的错误行，别把它当现状报红。
                bad = current.patchErrors > 0 && !current.builtinDisabled,
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
                    // 用户关掉了内置 TEE：不要用"引擎未运行"那种故障文案吓人，
                    // 直接说明这是选择、以及在哪里能改回来。
                    current.builtinDisabled -> stringResource(R.string.detect_tee_disabled_note)
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
                // 自检并修复：设备侧跑（引擎重启 / 换箱子 / 补名单 / 回写属性），这里只按开关。
                TextButton(
                    text = stringResource(R.string.detect_tee_selfcheck_run),
                    onClick = { runRepair(SELFCHECK_SCRIPT, R.string.detect_tee_selfcheck_failed) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                if (!current.checkerCovered) {
                    TextButton(
                        text = stringResource(R.string.detect_tee_add),
                        onClick = { addChecker() },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                // 接管引擎：没有任何引擎在跑、而用户也没把内置 TEE 关掉时才给 ——
                // 关掉之后"接管"就不是用户想要的语义了（按钮里会先把开关打开）。
                if (!current.engineAlive && !current.builtinDisabled) {
                    TextButton(
                        text = stringResource(R.string.detect_tee_flash),
                        onClick = {
                            scope.launch {
                                ExternalModules.install(scope, context, ExternalModules.TEE)
                                refresh()
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )

                                refresh()
                            }
                        }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                // 修复引擎：引擎在跑却没注入（或用户刚打开开关）时给 —— 重打 keystore 侧
                // sepolicy + 重新解密 + 干净重启注入链，这是运行期开引擎最容易漏的一步。
                if (current.notInjected || (current.builtinEnabled && !current.injected)) {
                    TextButton(
                        text = stringResource(R.string.detect_tee_flash_again),
                        onClick = {
                            scope.launch {
                                ExternalModules.install(scope, context, ExternalModules.TEE)
                                refresh()
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )

                                refresh()
                            }
                        }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                if (current.verifiedBoot != "green" || current.flashLocked != "1") {
                    TextButton(
                        text = stringResource(R.string.detect_tee_relock),
                        onClick = { runRepair(RELOCK_SCRIPT, R.string.detect_tee_relock_failed) },
                        enabled = !busy,
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

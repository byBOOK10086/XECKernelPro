package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TEE / 密钥库体检：引擎、keybox 校验、锁态、拦截模式。只读。 */
object TeeDiagnostics {

    private const val TS = "/data/adb/tricky_store"
    private const val SEC = "/data/adb/.xudc_secure"
    private const val BIN = "$SEC/ta-enhanced/bin/ta-enhanced"
    private const val LOG = "$SEC/ta-enhanced/logs/main.log"

    /**
     * 引擎可能落地的位置：**内置那份在最前面**。
     *
     * 2026-11-11 产品裁定：管理器随包分发的内置引擎优先，`/data/adb/modules` 下的
     * 副本一律让位。检测顺序必须跟着这个优先级走，否则 UI 会把外部副本当成"当前
     * 引擎"，后续动作（重启 / 一键修复）全都打到那份不会运行的副本上。
     */
    private val ENGINE_DIRS = listOf(
        "/data/adb/ksu/builtin/tricky_store",
        "/data/adb/modules/tricky_store",
        "/data/adb/modules/.tricky_store",
    )

    /** 厂商真修复工具（RKP 重签）的候选路径。没有它就只能软件伪造，见 [realRepairTool]。 */
    private val RKP_TOOLS = listOf(
        "/vendor/bin/KmInstallKeybox",
        "/vendor/bin/km_install_keybox",
        "/system_ext/bin/KmInstallKeybox",
    )

    data class KeyboxReport(
        val ok: Boolean,
        val deviceId: String?,
        val notAfter: String?,
        val rootType: String?,
        val validityOk: Boolean?,
        val chainValid: Boolean?,
        val revocation: String?,
        val keyCount: Int,
        val errors: List<String>,
    )

    data class Snapshot(
        val engineDir: String?,
        val engineEnabled: Boolean,
        val enginePid: Int,
        /** keystore2 的 maps 里是否已有 libTEESimulator：目录被删/模块被卸后，旧注入仍会残留到进程重启。 */
        val engineInjected: Boolean,
        val daemonBinary: Boolean,
        val daemonPid: Int,
        val keyboxPresent: Boolean,
        val keyboxBytes: Long,
        val keyboxMtimeMs: Long?,
        val keyboxCompat: Boolean,
        val keyboxGood: Boolean,
        val targetCount: Int,
        /** target.txt 里三种模式各自的条目数：`!`=强制 FORGE（软件生成）、`?`=强制 PATCH、其余=AUTO。 */
        val targetForge: Int,
        val targetPatch: Int,
        val targetAuto: Int,
        /** 内置验机工具（wu.keyChain.test）是否在拦截表里 —— 不在表里它看到的是真实 TEE。 */
        val targetHasVerifier: Boolean,
        val patchLine: String?,
        val bootStateProp: String?,
        val flashLockedProp: String?,
        val deviceStateProp: String?,
        val warrantyProp: String?,
        val logLines: List<String>,
        /** 远程配置优先（补丁 2h / keybox 自动拉取由本项目钉住）是否开启。 */
        val remotePriority: Boolean,
        /** security_patch.txt 里按应用覆盖的段数（`[包名]`）。 */
        val patchPerPackage: Int,
        val keybox: KeyboxReport?,
        val realRepairTool: String?,
        val errors: List<String>,
    ) {
        /** keybox 剩余天数（负数=已过期）。拿不到到期时间时返回 null。 */
        val keyboxDaysLeft: Long?
            get() {
                val raw = keybox?.notAfter ?: return null
                val ts = TeeDiagnostics.parseCertTime(raw) ?: return null
                return (ts - System.currentTimeMillis()) / 86_400_000L
            }
    }

    // ------------------------------------------------------------------ 探测

    suspend fun probe(): Snapshot = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) {
            return@withContext empty("没有 root，读不到 /data/adb（请先确认管理器已授权）")
        }
        val errors = ArrayList<String>()
        val values = LinkedHashMap<String, String>()
        val logLines = ArrayList<String>()

        val script = buildString {
            append("E='';")
            ENGINE_DIRS.forEach { append(" [ -d '$it' ] && [ -z \"\$E\" ] && E='$it';") }
            append(" echo \"ENGINE_DIR=\$E\";")
            append(" if [ -n \"\$E\" ] && [ -f \"\$E/disable\" ]; then echo 'ENGINE_DISABLED=1'; else echo 'ENGINE_DISABLED=0'; fi;")
            append(" echo \"ENGINE_PID=\$(pidof TEESimulator 2>/dev/null | cut -d' ' -f1)\";")
            // 2026-11-10 真机反馈："检测页 TEE 注入是绿的，但工具箱说没有引擎"。
            // 两者可以同时成立：模块目录被删/被卸后，keystore2 里旧的注入仍然残留，
            // 直到 keystore2 重启才失效。把这件事显式探出来，UI 才能说人话。
            append(" KPID=\$(pidof keystore2 2>/dev/null | cut -d' ' -f1);")
            append(" if [ -n \"\$KPID\" ] && grep -q libTEESimulator /proc/\$KPID/maps 2>/dev/null; then echo 'ENGINE_INJECTED=1'; else echo 'ENGINE_INJECTED=0'; fi;")
            append(" if [ -x '$BIN' ]; then echo 'DAEMON_BIN=1'; else echo 'DAEMON_BIN=0'; fi;")
            append(" echo \"DAEMON_PID=\$(pidof ta-enhanced 2>/dev/null | cut -d' ' -f1)\";")
            append(" if [ -f '$TS/keybox.xml' ]; then echo 'KEYBOX=1';")
            append("   echo \"KEYBOX_SIZE=\$(stat -c %s '$TS/keybox.xml' 2>/dev/null)\";")
            append("   echo \"KEYBOX_MTIME=\$(stat -c %Y '$TS/keybox.xml' 2>/dev/null)\";")
            append(" else echo 'KEYBOX=0'; fi;")
            append(" if [ -f '$SEC/keybox.xml' ]; then echo 'KEYBOX_COMPAT=1'; else echo 'KEYBOX_COMPAT=0'; fi;")
            append(" if [ -f '$SEC/keybox/good.xml' ]; then echo 'KEYBOX_GOOD=1'; else echo 'KEYBOX_GOOD=0'; fi;")
            append(" echo \"TARGET_COUNT=\$(grep -cvE '^[[:space:]]*(#|\$)' '$TS/target.txt' 2>/dev/null)\";")
            append(" echo \"TARGET_FORGE=\$(grep -cE '!\$' '$TS/target.txt' 2>/dev/null)\";")
            append(" echo \"TARGET_PATCH=\$(grep -cE '\\?\$' '$TS/target.txt' 2>/dev/null)\";")
            append(" if grep -qxF 'wu.keyChain.test' '$TS/target.txt' 2>/dev/null; then echo 'TARGET_VERIFIER=1'; else echo 'TARGET_VERIFIER=0'; fi;")
            append(" echo \"PATCH_LINE=\$(head -n1 '$TS/security_patch.txt' 2>/dev/null)\";")
            append(" echo \"P_VBS=\$(getprop ro.boot.verifiedbootstate)\";")
            append(" echo \"P_FLASH_LOCKED=\$(getprop ro.boot.flash.locked)\";")
            append(" echo \"P_DEVICE_STATE=\$(getprop ro.boot.vbmeta.device_state)\";")
            append(" echo \"P_WARRANTY=\$(getprop ro.boot.warranty_bit)\";")
            RKP_TOOLS.forEachIndexed { index, path ->
                append(" [ -x '$path' ] && echo 'RKP_TOOL_$index=$path';")
            }
            append(" if [ -f '$SEC/remote_priority_off' ]; then echo 'REMOTE_PRIO=0'; else echo 'REMOTE_PRIO=1'; fi;")
            append(" echo \"PATCH_PKGS=\$(grep -cE '^[[:space:]]*\\[[A-Za-z0-9_.-]+\\][[:space:]]*\$' '$TS/security_patch.txt' 2>/dev/null)\";")
            append(" echo '---LOG---';")
            append(" grep -iE 'ERROR|WARN' '$LOG' 2>/dev/null | tail -n 4;")
        }

        val result = runCatching { shell.newJob().add(script).exec() }
            .getOrElse {
                return@withContext empty("执行体检脚本失败：${it.javaClass.simpleName}")
            }
        var inLog = false
        for (raw in result.out) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line == "---LOG---") {
                inLog = true
                continue
            }
            if (inLog) {
                logLines += line
                continue
            }
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            values[line.substring(0, idx)] = line.substring(idx + 1)
        }
        if (!result.isSuccess && errors.isEmpty()) {
            errors += "体检脚本有非零退出（部分字段可能是空的）"
        }

        val engineDir = values["ENGINE_DIR"]?.takeIf { it.isNotEmpty() }
        val keyboxPresent = values["KEYBOX"] == "1"
        val daemonBinary = values["DAEMON_BIN"] == "1"

        val report = if (keyboxPresent && daemonBinary) validateKeybox(shell, errors) else null

        Snapshot(
            engineDir = engineDir,
            engineEnabled = engineDir != null && values["ENGINE_DISABLED"] != "1",
            enginePid = values["ENGINE_PID"]?.toIntOrNull() ?: 0,
            engineInjected = values["ENGINE_INJECTED"] == "1",
            daemonBinary = daemonBinary,
            daemonPid = values["DAEMON_PID"]?.toIntOrNull() ?: 0,
            keyboxPresent = keyboxPresent,
            keyboxBytes = values["KEYBOX_SIZE"]?.toLongOrNull() ?: 0L,
            keyboxMtimeMs = values["KEYBOX_MTIME"]?.toLongOrNull()?.times(1000L),
            keyboxCompat = values["KEYBOX_COMPAT"] == "1",
            keyboxGood = values["KEYBOX_GOOD"] == "1",
            targetCount = values["TARGET_COUNT"]?.toIntOrNull() ?: 0,
            targetForge = values["TARGET_FORGE"]?.toIntOrNull() ?: 0,
            targetPatch = values["TARGET_PATCH"]?.toIntOrNull() ?: 0,
            targetAuto = ((values["TARGET_COUNT"]?.toIntOrNull() ?: 0) -
                (values["TARGET_FORGE"]?.toIntOrNull() ?: 0) -
                (values["TARGET_PATCH"]?.toIntOrNull() ?: 0)).coerceAtLeast(0),
            targetHasVerifier = values["TARGET_VERIFIER"] == "1",
            patchLine = values["PATCH_LINE"]?.takeIf { it.isNotEmpty() },
            bootStateProp = values["P_VBS"]?.takeIf { it.isNotEmpty() },
            flashLockedProp = values["P_FLASH_LOCKED"]?.takeIf { it.isNotEmpty() },
            deviceStateProp = values["P_DEVICE_STATE"]?.takeIf { it.isNotEmpty() },
            warrantyProp = values["P_WARRANTY"]?.takeIf { it.isNotEmpty() },
            logLines = logLines,
            remotePriority = values["REMOTE_PRIO"] != "0",
            patchPerPackage = values["PATCH_PKGS"]?.toIntOrNull() ?: 0,
            keybox = report,
            realRepairTool = RKP_TOOLS.indices.firstNotNullOfOrNull { values["RKP_TOOL_$it"] },
            errors = errors,
        )
    }

    private fun empty(reason: String) = Snapshot(
        engineDir = null,
        engineEnabled = false,
        enginePid = 0,
        engineInjected = false,
        daemonBinary = false,
        daemonPid = 0,
        keyboxPresent = false,
        keyboxBytes = 0,
        keyboxMtimeMs = null,
        keyboxCompat = false,
        keyboxGood = false,
        targetCount = 0,
        targetForge = 0,
        targetPatch = 0,
        targetAuto = 0,
        targetHasVerifier = false,
        patchLine = null,
        bootStateProp = null,
        flashLockedProp = null,
        deviceStateProp = null,
        warrantyProp = null,
        logLines = emptyList(),
        remotePriority = true,
        patchPerPackage = 0,
        keybox = null,
        realRepairTool = null,
        errors = listOf(reason),
    )

    /** 让守护进程自己校验现场 keybox */
    private fun validateKeybox(shell: Shell, errors: MutableList<String>): KeyboxReport? {
        val result = runCatching { shell.newJob().add("'$BIN' keybox validate 2>&1").exec() }
            .getOrElse {
                errors += "keybox validate 执行失败：${it.javaClass.simpleName}"
                return null
            }
        val text = result.out.joinToString("\n").trim()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            errors += "keybox validate 没有输出 JSON：${text.take(120)}"
            return null
        }
        return runCatching {
            val json = JSONObject(text.substring(start, end + 1))
            val keys = json.optJSONArray("keys")
            val first = if (keys != null && keys.length() > 0) keys.optJSONObject(0) else null
            val list = ArrayList<String>()
            val errArray = json.optJSONArray("errors")
            if (errArray != null) {
                for (i in 0 until errArray.length()) list += errArray.optString(i)
            }
            KeyboxReport(
                ok = json.optBoolean("ok", false),
                deviceId = first?.optString("device_id")?.takeIf { it.isNotEmpty() },
                notAfter = first?.optString("not_after")?.takeIf { it.isNotEmpty() },
                rootType = first?.optString("root_type")?.takeIf { it.isNotEmpty() },
                validityOk = if (first?.has("validity_ok") == true) first.optBoolean("validity_ok") else null,
                chainValid = if (first?.has("chain_valid") == true) first.optBoolean("chain_valid") else null,
                revocation = first?.optString("revocation_reason")?.takeIf { it.isNotEmpty() },
                keyCount = keys?.length() ?: 0,
                errors = list,
            )
        }.getOrElse {
            errors += "keybox validate 的 JSON 解析不了：${it.javaClass.simpleName}"
            null
        }
    }

    // ------------------------------------------------------------------ 动作

    /** 「一键修复」里的一步：UI 逐行显示，失败也能看出停在哪一步。 */
    data class RepairStep(val label: String, val ok: Boolean, val detail: String)

    /** 引擎进程那一步的固定标签 —— [restartEngine] 靠它把结果折回 Boolean。 */
    private const val STEP_ENGINE = "引擎进程"

    /** 管理器自带的 xudc 路径（= APK 的 `libxeccli.so`）：内置引擎载荷就编在它里面。 */
    private fun bundledKsud(): String = getKsuDaemonPath()

    /**
     * 强制把引擎装起来并**当场启动**（「一键修复」的真实现）。
     *
     * 以前的一键修复只有"刷新 keybox + 重启引擎"：引擎目录根本不存在时两步必然失败，
     * 用户看到的就是"一键修复不能用"。而引擎载荷其实一直随 APK 分发：`libxeccli.so`
     * 用 rust-embed 把 `userspace/ksud/builtin/` 编进了二进制，所以
     * `xudc builtin start` 能把它释放到 `/data/adb/ksu/builtin`、跑 stage 脚本、
     * 回读进程与注入 —— 不刷内核、不重启，也不依赖内核认不认这个管理器。
     *
     * 开机自动拉起仍走 ksud 的 post-fs-data（那条链有 Magisk 残留 / 安全模式 /
     * boot 事务 / UAPI 四道闸门）；这里强制生效的是**当前这次**，重启后需要再点一次
     * 或把闸门清掉。
     */
    suspend fun repairEngine(
        onPhase: (String) -> Unit = {},
    ): Result<List<RepairStep>> = withContext(Dispatchers.IO) {
        onPhase("正在确认 root 与自启开关")
        val shell = awaitRootShell(timeoutMs = 20_000L)
        if (!shell.isRoot) {
            return@withContext Result.failure(
                IllegalStateException("等了 20 秒还是拿不到 root shell：先在「授权」里给管理器 root"),
            )
        }

        // 2026-11-11: isRoot only records that the shell was built with `su`; it does
        // not prove the shell works. A dead shell still reports isRoot=true and then
        // answers every command with empty output and exit -1, which the UI can only
        // render as "命令没有任何输出". Probe uid first so the two failures separate.
        val uidProbe = runCatching { shell.newJob().add("id -u").exec() }.getOrNull()
        val shellUid = uidProbe?.out?.firstOrNull()?.trim()
        if (shellUid != "0") {
            return@withContext Result.failure(
                IllegalStateException(
                    "root shell 建起来了但不可用：id -u 返回 " + (shellUid ?: "<空>") +
                        "，退出码 " + (uidProbe?.code ?: -1) +
                        "。这条通道不通时，下面每条命令都会静默返回空。",
                ),
            )
        }
        val steps = ArrayList<RepairStep>()
        // 先给"重启后强制自启"排队：这个开关只有**带钩子的 KMI 内核**才认，所以这里
        // 顺手探一次 /data/adb/xudc（内核侧的 daemon）到底认不认，别让用户以为
        // 旧内核重启一下就会自己起来。
        val kernelXudc = "/data/adb/xudc"
        val arm = runCatching {
            shell.newJob().add(
                "'${bundledKsud()}' builtin force-boot on >/dev/null 2>&1; " +
                    "V=\$('$kernelXudc' builtin force-boot 2>/dev/null); echo __K=\$V",
            ).exec().out
        }.getOrNull().orEmpty()
        val kernelState = arm.firstOrNull { it.startsWith("__K=") }?.removePrefix("__K=")?.trim()
        steps += RepairStep(
            label = "重启后强制自启",
            ok = kernelState == "on",
            detail = when (kernelState) {
                "on" -> "开关已写入；/data/adb/xudc 认它，下次开机会自动拉起引擎"
                "off" -> "开关已写入，但 /data/adb/xudc 读回是 off"
                else -> "开关已预先写入；/data/adb/xudc 还是旧版（不认这个开关）—— " +
                    "点「刷新开机守护进程」换成新版即可，不用刷内核"
            },
        )
        // 装 + 起在同一条命令里。timeout 兜底：内核不是本项目时，脚本环境采集里
        // 的内核调用可能拖住，绝不能让 UI 一直转圈。
        onPhase("正在释放内置引擎并启动（最长约 2 分钟）")
        val result = runCatching {
            shell.newJob().add(
                "timeout 240 '${bundledKsud()}' builtin start 2>&1; echo __XEC_EXIT=\$?",
            ).exec()
        }.getOrElse { return@withContext Result.failure(it) }
        val lines = result.out.map { it.trim() }.filter { it.isNotEmpty() }
        val exit = lines.lastOrNull { it.startsWith("__XEC_EXIT=") }
            ?.substringAfter('=')?.toIntOrNull()
        if (lines.none { it.startsWith("engine_dir=") }) {
            val why = lines.filterNot { it.startsWith("__XEC_EXIT=") }
                .takeLast(4).joinToString(" / ").ifEmpty { "命令没有任何输出" }
            return@withContext Result.failure(
                IllegalStateException("内置引擎没起来（退出码 ${exit ?: -1}）：${("command: timeout 240 <xudc> builtin start | output: " + why.take(600).ifEmpty { "<empty>" })}"),
            )
        }
        val modules = value(lines, "modules")?.toIntOrNull()
        val rewritten = value(lines, "rewritten")?.toIntOrNull()
        steps += RepairStep(
            label = "释放内置引擎",
            ok = true,
            detail = when {
                modules == null -> "已释放"
                rewritten == 0 -> "$modules 个内置树都是最新的，没重写文件"
                else -> "$modules 个内置树，重写 $rewritten 个"
            },
        )
        for (line in lines.filter { it.startsWith("yielded=") }) {
            steps += RepairStep(
                label = "外部引擎让位",
                ok = true,
                detail = line.removePrefix("yielded=").take(160),
            )
        }
        for (line in lines.filter { it.startsWith("script=") }) {
            val body = line.removePrefix("script=")
            val name = body.substringBefore(" spawned=")
            val ok = body.substringAfter("spawned=").trim() == "1"
            steps += RepairStep(
                label = "启动 ${name.substringBefore('/')} 的 ${name.substringAfter('/')}",
                ok = ok,
                detail = if (ok) "已拉起" else "没起来（脚本缺失或 spawn 失败）",
            )
        }
        // keybox：TA 守护每 15s 会镜像一次，这里主动拉一次，省一轮等待。
        refreshKeybox().fold(
            onSuccess = { steps += RepairStep("重新拉取 keybox", true, it.take(160)) },
            onFailure = { steps += RepairStep("重新拉取 keybox", false, it.message.orEmpty().take(160)) },
        )
        // 回读：注入是异步的，最多再等 18 秒。
        onPhase("正在回读进程与 keystore2 注入")
        var pid = 0
        var injected = false
        var waited = 0
        while (waited < 24) {
            val probe = runCatching {
                shell.newJob().add(
                    "echo P=\$(pidof TEESimulator 2>/dev/null | cut -d' ' -f1); " +
                        // keystore2 重启窗口里可能有多个 pid：逐个看，别只看第一个 ——
                        // 只看第一个会把"只注入到将要退出的那个"报成成功。
                        "I=0; for K in \$(pidof keystore2 2>/dev/null); do " +
                        "grep -q libTEESimulator /proc/\$K/maps 2>/dev/null && I=1; done; " +
                        "echo I=\$I",
                ).exec().out
            }.getOrNull().orEmpty()
            pid = probe.firstOrNull { it.startsWith("P=") }
                ?.removePrefix("P=")?.trim()?.toIntOrNull() ?: 0
            injected = probe.any { it.trim() == "I=1" }
            if (pid > 0 && injected) break
            waited++
            Thread.sleep(750)
        }
        steps += RepairStep(
            label = STEP_ENGINE,
            ok = pid > 0,
            detail = if (pid > 0) "TEESimulator pid $pid" else "18 秒内没看到 TEESimulator 进程",
        )
        steps += RepairStep(
            label = "keystore2 注入",
            ok = injected,
            detail = if (injected) "libTEESimulator 已在 keystore2 的 maps 里" else "keystore2 里还没有注入（引擎没接管）",
        )
        Result.success(steps)
    }

    private fun value(lines: List<String>, key: String): String? =
        lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    /** 让守护进程拉一次 keybox（它自己带镜像/校验/信任锚判定），成功后再重启引擎。 */
    suspend fun refreshKeybox(): Result<String> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val result = runCatching { shell.newJob().add("'$BIN' keybox fetch 2>&1").exec() }
            .getOrElse { return@withContext Result.failure(it) }
        val text = result.out.joinToString(" ").trim()
        if (result.isSuccess) {
            Result.success(text.ifEmpty { "已拉取" })
        } else {
            Result.failure(IllegalStateException(text.ifEmpty { "拉取失败（可能是网络）" }))
        }
    }

    /** 清空 AndroidKeyStore 与引擎生成的持久密钥 */
    suspend fun clearKeyStore(): Result<Unit> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val script = buildString {
            append("rm -rf $TS/persistent_keys/* 2>/dev/null; ")
            append("rm -rf /data/misc/keystore/* 2>/dev/null; ")
            append("echo done")
        }
        val result = runCatching { shell.newJob().add(script).exec() }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("执行失败"))
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.err.joinToString(" ").ifBlank { "清理失败" }))
    }

    /**
     * 刷新开机真正执行的那个守护进程 `/data/adb/xudc`。
     *
     * 开机 rc 跑的是 `/data/adb/xudc`（`kernel/ksud.h` 的 `KSUD_PATH`），而它是 APK 里的
     * `libxeccli.so` 在 `xudc install` 时拷过去的 —— 所以「重启后强制自启」认不认，
     * 取决于这个文件是不是新版本，**跟内核镜像无关**。换它不需要重启，也不需要刷机。
     */
    suspend fun refreshBootDaemon(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell(timeoutMs = 20_000L)
        if (!shell.isRoot) {
            return@withContext Result.failure(
                IllegalStateException("没有 root，换不了 /data/adb/xudc"),
            )
        }
        val info = context.applicationInfo
        val libadbroot = File(info.nativeLibraryDir, "libadbroot.so").absolutePath
        val args = buildString {
            append("install")
            if (File(libadbroot).isFile) append(" --libadbroot '$libadbroot'")
            append(" --data-path '${info.deviceProtectedDataDir}'")
        }
        val result = runCatching { shell.newJob().add("'${bundledKsud()}' $args 2>&1").exec() }
            .getOrElse { return@withContext Result.failure(it) }
        val text = result.out.map { it.trim() }.filter { it.isNotEmpty() }
            .takeLast(3).joinToString(" / ")
        if (!result.isSuccess) {
            return@withContext Result.failure(
                IllegalStateException("刷新失败：${text.ifEmpty { "退出码 ${result.code}" }}"),
            )
        }
        // 回读：新守护进程必须认识 `builtin force-boot`，否则等于没换上。
        val state = runCatching {
            shell.newJob().add("'/data/adb/xudc' builtin force-boot 2>&1").exec()
        }.getOrNull()?.out?.map { it.trim() }?.firstOrNull { it == "on" || it == "off" }
        if (state == null) {
            Result.success("已替换；新守护进程还没答上开关状态，重启后再看一次")
        } else {
            Result.success("已替换：/data/adb/xudc 现在认这个开关（当前 $state）")
        }
    }

    /**
     * 一键修复：引擎不在就**强制从安装包装出来并启动**。
     *
     * 2026-11-11 产品裁定：内置引擎优先，`/data/adb/modules` 下的副本一律让位。所以
     * 这里不再按 engineDir 分流——旧逻辑在探到外部副本时只重拉 keybox + 重启那份模块，
     * `builtin start` 永远得不到调用，真机表现就是"一键修复怎么点都修不好"。
     */
    suspend fun oneTapRepair(
        engineDir: String?,
        onPhase: (String) -> Unit = {},
    ): Result<List<RepairStep>> =
        withContext(Dispatchers.IO) {
            val steps = ArrayList<RepairStep>()
            // 产品策略（2026-11-11）：内置引擎优先。以前这里按 engineDir 分流——只要探到
            // `/data/adb/modules/...` 的副本就只刷新 keybox + 重启那份外部模块，`builtin
            // start` 永远得不到调用，外部副本也就永远不会让位。真机就是这个死循环：一键
            // 修复"执行成功"，引擎却一直没起来。现在统一走 [repairEngine]：它执行的
            // `builtin start` 会先把外部副本搬走 / 压制，再释放并拉起内置引擎。
            if (engineDir != null && !engineDir.startsWith("/data/adb/ksu/builtin/")) {
                steps += RepairStep("外部副本让位", true, engineDir)
            }
            onPhase("正在拉取 keybox")
            refreshKeybox().fold(
                onSuccess = { steps += RepairStep("重新拉取 keybox", true, it.take(160)) },
                onFailure = { steps += RepairStep("重新拉取 keybox", false, it.message.orEmpty().take(160)) },
            )
            repairEngine(onPhase).map { repairSteps -> steps + repairSteps }
        }

    /**
     * 重启引擎。
     *
     * 统一走内置的 `builtin start`：内置引擎优先，外部副本由它自己让位。以前这里对
     * 外部模块目录直接 `sh $dir/service.sh`，而那份脚本在新规则下会 stand down，
     * 于是"重启"永远看不到进程。
     */
    suspend fun restartEngine(engineDir: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell(timeoutMs = 15_000L)
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val lines = runCatching {
            shell.newJob().add("timeout 240 '${bundledKsud()}' builtin start 2>&1")
                .exec().out
        }.getOrElse { return@withContext Result.failure(it) }.map { it.trim() }
        val pid = lines.firstOrNull { it.startsWith("engine_pid=") }
            ?.substringAfter('=')?.toIntOrNull() ?: 0
        if (pid > 0) return@withContext Result.success(true)
        // `builtin start` 自己等了 15 秒；这里再兜一轮，别把"还在起"报成"没起"。
        repeat(20) {
            val alive = runCatching {
                shell.newJob().add("pidof TEESimulator 2>/dev/null").exec()
                    .out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            }.getOrDefault(0)
            if (alive > 0) return@withContext Result.success(true)
            Thread.sleep(600)
        }
        Result.failure(
            IllegalStateException(
                "引擎没起来：${lines.takeLast(3).joinToString(" / ").take(240)}",
            ),
        )
    }

    /** 远程配置优先开关（用户的硬性裁定：补丁/指纹/远程密钥这套逻辑必须压过 TEE 引擎那套） */
    suspend fun setRemotePriority(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val cmd = if (enabled) {
            "rm -f '$SEC/remote_priority_off'; mkdir -p '$SEC'; : > '$SEC/remote_priority'; echo done"
        } else {
            "mkdir -p '$SEC'; : > '$SEC/remote_priority_off'; echo done"
        }
        val result = runCatching { shell.newJob().add(cmd).exec() }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("执行失败"))
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.err.joinToString(" ").ifBlank { "写入开关失败" }))
    }

    /** 把内置验机工具（`wu */
    suspend fun addVerifierToTarget(): Result<Unit> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val script =
            "T='$TS/target.txt'; mkdir -p '$TS'; touch \"\$T\"; " +
                "if ! grep -qxF 'wu.keyChain.test' \"\$T\"; then echo 'wu.keyChain.test' >> \"\$T\"; fi; " +
                "chmod 644 \"\$T\" 2>/dev/null; echo done"
        val result = runCatching { shell.newJob().add(script).exec() }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("执行失败"))
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(IllegalStateException(result.err.joinToString(" ").ifBlank { "写入 target.txt 失败" }))
    }

    // ------------------------------------------------------------------ 格式化

    fun parseCertTime(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "MMM d HH:mm:ss yyyy 'GMT'",
        )
        val text = raw.trim()
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun formatTime(ms: Long?): String {
        val v = ms ?: return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(v))
    }
}

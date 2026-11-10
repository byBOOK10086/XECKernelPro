package me.weishu.kernelsu.ui.screen.settings

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TEE / 密钥库体检：引擎、keybox 校验、锁态、拦截模式。只读。 */
object TeeDiagnostics {

    private const val TS = "/data/adb/tricky_store"
    private const val SEC = "/data/adb/.xudc_secure"
    private const val BIN = "$SEC/ta-enhanced/bin/ta-enhanced"
    private const val LOG = "$SEC/ta-enhanced/logs/main.log"

    /** 引擎模块可能落地的三个位置：正常模块、隐藏模块、ksud 内置源目录。 */
    private val ENGINE_DIRS = listOf(
        "/data/adb/modules/tricky_store",
        "/data/adb/modules/.tricky_store",
        "/data/adb/ksu/builtin/tricky_store",
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

    /** 重启引擎进程（不改配置）。主要用于"改了 target.txt/keybox 后让引擎重新加载"。 */
    suspend fun restartEngine(engineDir: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        val dir = engineDir ?: return@withContext Result.failure(
            IllegalStateException("找不到引擎目录（tricky_store 没装？）"),
        )
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(IllegalStateException("没有 root"))
        val script = "killall TEESimulator 2>/dev/null; sleep 1; sh '$dir/service.sh' >/dev/null 2>&1 &"
        val result = runCatching { shell.newJob().add(script).exec() }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("执行失败"))
        // 等进程真的起来（最多 12 秒），别让 UI 报"已重启"但其实没起。
        repeat(24) {
            val pid = runCatching {
                shell.newJob().add("pidof TEESimulator 2>/dev/null").exec()
                    .out.firstOrNull()?.trim()?.toIntOrNull()
            }.getOrNull() ?: 0
            if (pid > 0) return@withContext Result.success(true)
            Thread.sleep(500)
        }
        Result.success(result.isSuccess)
    }

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

    /** 一键软件修复：按"换箱后遗症 / 引擎没接管"的处置顺序串起来 */
    suspend fun oneTapRepair(engineDir: String?): Result<String> = withContext(Dispatchers.IO) {
        val steps = ArrayList<String>()
        refreshKeybox().fold(
            onSuccess = { steps += "keybox 已刷新（$it）" },
            onFailure = { steps += "keybox 刷新失败：${it.message}" },
        )
        restartEngine(engineDir).fold(
            onSuccess = { ok -> steps += if (ok) "引擎已重启" else "引擎重启命令已发出，但进程没在 12 秒内出现" },
            onFailure = { steps += "引擎重启失败：${it.message}" },
        )
        Result.success(steps.joinToString("；"))
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

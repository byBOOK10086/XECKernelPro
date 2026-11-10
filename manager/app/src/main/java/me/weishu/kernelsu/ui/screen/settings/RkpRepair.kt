package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真·RKP 重签（2026-11-10）。
 *
 * **这是唯一一条能"真修"TEE 的路径**：把 keybox 通过厂商工具真正写回 TEE/RKP 存储，
 * 而不是像 FORGE 模式那样在软件层伪造证明。它只在厂商随系统提供了
 * `KmInstallKeybox`（Pixel 与部分 OEM 有，多数国产 ROM 没有）时才可用 —— 没有工具时
 * 这里**不会**给出假按钮，只如实说明"这台机器只能软件伪造"。
 *
 * 安全约束（每一条都对应一种真实的翻车方式）：
 *  1. 工具本身必须是 root 属主、且组/其他人不可写（`stat -c '%U %a'`）。否则等于以
 *     root 执行一个可以被别的应用替换掉的程序。
 *  2. keybox 文件必须先落盘并通过结构检查（XML 里有 `Keybox` 与 `DeviceID`），
 *     `DeviceID` 直接取文件里的值 —— 传错 ID 会让写进去的箱子对不上设备。
 *  3. 动手前把现有 `/data/adb/tricky_store/keybox.xml` 备份成 `.bak-<时间>`。
 *  4. 工具调用带超时（120s），失败原样回显 stdout/stderr，不吞成"已修复"。
 *  5. 写回之后重启证明引擎并**等它真的起来**，再让 UI 重新取证。
 */
object RkpRepair {

    /** 厂商工具候选路径。真修复只有这一条入口。 */
    private val TOOL_PATHS = listOf(
        "/vendor/bin/KmInstallKeybox",
        "/vendor/bin/km_install_keybox",
        "/system_ext/bin/KmInstallKeybox",
    )

    private const val LIVE_KEYBOX = "/data/adb/tricky_store/keybox.xml"
    private const val ENGINE_DIRS = "/data/adb/modules/tricky_store /data/adb/modules/.tricky_store /data/adb/ksu/builtin/tricky_store"
    private const val TIMEOUT_SECS = 120

    class Refused(message: String) : IllegalStateException(message)

    data class Tool(
        val path: String,
        val owner: String,
        val mode: String,
        val usable: Boolean,
        val reason: String?,
    )

    /** 找厂商工具并核对属主/权限。没有就返回 null（UI 不显示入口）。 */
    suspend fun probeTool(): Tool? = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext null
        for (path in TOOL_PATHS) {
            val out = runCatching {
                shell.newJob()
                    .add("if [ -x '$path' ]; then stat -c '%U %a' '$path'; fi")
                    .exec().out.joinToString(" ").trim()
            }.getOrNull().orEmpty()
            if (out.isEmpty()) continue
            val parts = out.split(Regex("\\s+"))
            val owner = parts.getOrNull(0).orEmpty()
            val mode = parts.getOrNull(1).orEmpty()
            val usable = owner == "root" && mode.isNotEmpty() && !mode.takeLast(2).any { it in '2'..'7' }
            return@withContext Tool(
                path = path,
                owner = owner,
                mode = mode,
                usable = usable,
                reason = when {
                    owner != "root" -> "工具属主是 $owner，不是 root"
                    !usable -> "工具权限 $mode 允许组/其他人写入，拒绝用它提权"
                    else -> null
                },
            )
        }
        null
    }

    /** 从设备上一个路径重签。 */
    suspend fun installFromPath(context: Context, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            val local = File(context.cacheDir, "rkp_${System.currentTimeMillis()}.xml")
            val shell = awaitRootShell()
            if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root"))
            val copied = shell.newJob()
                .add("cp -f '$path' '${local.absolutePath}' 2>/dev/null || cat '$path' > '${local.absolutePath}'")
                .exec()
            try {
                if (!copied.isSuccess || !local.isFile || local.length() == 0L) {
                    return@withContext Result.failure(Refused("读不到 $path"))
                }
                install(context, local, sourceName = path)
            } finally {
                local.delete()
            }
        }

    /** 从用户选的文件重签。 */
    suspend fun installFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val local = File(context.cacheDir, "rkp_${System.currentTimeMillis()}.xml")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                local.outputStream().use { input.copyTo(it) }
            } != null && local.length() > 0L
        }.getOrDefault(false)
        try {
            if (!ok) return@withContext Result.failure(Refused("读取你选的文件失败"))
            install(context, local, sourceName = uri.lastPathSegment ?: "所选文件")
        } finally {
            local.delete()
        }
    }

    /** 用当前在用的 keybox 重签（最常见：换了一张箱子，想真正写进 TEE）。 */
    suspend fun installLive(): Result<String> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root"))
        if (!shell.newJob().add("test -f '$LIVE_KEYBOX'").exec().isSuccess) {
            return@withContext Result.failure(Refused("$LIVE_KEYBOX 不存在"))
        }
        runInstall(shell, LIVE_KEYBOX)
    }

    // ------------------------------------------------------------------ 主流程

    private suspend fun install(context: Context, jsonFile: File, sourceName: String): Result<String> {
        val shell = awaitRootShell()
        if (!shell.isRoot) return Result.failure(Refused("没有 root"))
        val text = runCatching { jsonFile.readText().take(8192) }
            .getOrElse { return Result.failure(Refused("读文件失败")) }
        if (!text.contains("<Keybox") && !text.contains("<AndroidAttestation")) {
            return Result.failure(Refused("这不像 keybox XML（找不到 <Keybox> 元素）"))
        }
        val staged = "/data/adb/.xudc_secure/rkp_install.xml"
        val put = shell.newJob().add(
            "mkdir -p /data/adb/.xudc_secure && cp -f '${jsonFile.absolutePath}' '$staged' && " +
                "chmod 600 '$staged'",
        ).exec()
        if (!put.isSuccess) return Result.failure(Refused("把 keybox 放进 /data/adb 失败"))
        return runInstall(shell, staged, sourceName)
    }

    private suspend fun runInstall(shell: Shell, keyboxPath: String, sourceName: String? = null): Result<String> {
        val tool = probeTool()
            ?: return Result.failure(Refused("这台机器没有厂商重签工具（KmInstallKeybox），只能软件伪造"))
        if (!tool.usable) {
            return Result.failure(Refused("厂商工具不可用：${tool.reason}"))
        }
        val deviceId = shell.newJob()
            .add("sed -n 's/.*DeviceID=\"\\([^\"]*\\)\".*/\\1/p' '$keyboxPath' 2>/dev/null | head -n1")
            .exec().out.firstOrNull()?.trim().orEmpty()
        if (deviceId.isEmpty()) {
            return Result.failure(Refused("keybox 里没有 DeviceID，厂商工具需要它"))
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backup = "$LIVE_KEYBOX.bak-$stamp"
        shell.newJob().add("if [ -f '$LIVE_KEYBOX' ]; then cp -f '$LIVE_KEYBOX' '$backup'; fi").exec()

        // 厂商工具的调用约定（与 OhMyKeymint 的 rkp_repair 相同）：
        //   KmInstallKeybox <keybox_file> <device_id> <is_stock=true> <rkp>
        // 带超时：工具卡住时不能让管理器的 root shell 一直挂着。
        val timeout = if (shell.newJob().add("command -v timeout >/dev/null 2>&1").exec().isSuccess) {
            "timeout $TIMEOUT_SECS "
        } else {
            ""
        }
        val run = shell.newJob().add(
            "${timeout}'${tool.path}' '$keyboxPath' '$deviceId' true rkp 2>&1; echo \"XEC_EXIT=\$?\"",
        ).exec()
        val output = run.out.joinToString("\n").trim()
        val exit = Regex("XEC_EXIT=(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (exit != 0) {
            return Result.failure(
                Refused(
                    "厂商工具返回 ${exit ?: "?"}：" +
                        output.replace(Regex("XEC_EXIT=\\d+"), "").trim().take(240).ifEmpty { "无输出" },
                ),
            )
        }
        // 写回后拉起引擎（引擎要在开机后重新接管 keystore）
        val restart = TeeDiagnostics.restartEngine(probeEngineDir(shell))
        val note = if (restart.isSuccess) "，引擎已重启" else "，但引擎没能重启（下次开机会拉起）"
        return Result.success(
            "已用 ${tool.path} 把 keybox 写回 TEE（DeviceID=$deviceId" +
                (sourceName?.let { "，来源 $it" } ?: "") + "）$note；旧箱子备份在 $backup",
        )
    }

    private fun probeEngineDir(shell: Shell): String? =
        ENGINE_DIRS.split(' ').firstOrNull { dir ->
            runCatching { shell.newJob().add("test -d '$dir'").exec().isSuccess }.getOrDefault(false)
        }
}

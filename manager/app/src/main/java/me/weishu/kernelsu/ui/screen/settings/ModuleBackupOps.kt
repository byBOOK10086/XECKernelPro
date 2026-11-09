package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块备份 / 恢复（2026-11-09）。
 *
 * 能力来自 KernelSU-Next 的 `BackupRestore.kt`（Apache-2.0，见 THIRD_PARTY_NOTICES.md
 * 「模块备份与恢复」一节）：把 `/data/adb/modules` 打成一个 tar 交给用户，恢复时**解到
 * `modules_update` 而不是直接覆盖 `modules`** —— 后者会在下一次开机时由 ksud 合并，
 * 于是"恢复"是可撤销的：刷完先重启才生效，中途反悔只要不开机就还在。
 *
 * 与上游实现的两处差异（都是按本树实际情况改的，不是简化）：
 *  1. 不用 `/data/adb/ksu/bin/busybox` 做唯一 tar 实现 —— 本树不保证任何模块装了
 *     busybox，而 Android 10+ 的 toybox 自带 tar。这里按 toybox -> busybox 的顺序
 *     探测，两个都没有就直接失败并说清楚，不静默返回一个空包。
 *  2. tar 先写到 app 私有 cacheDir，再流进用户选的 URI：SAF 的 Uri 不能直接给
 *     root shell 用，反过来 root shell 也只能写它自己能看见的路径。
 */
object ModuleBackupOps {

    private const val MODULES_DIR = "/data/adb/modules"
    private const val MODULES_UPDATE_DIR = "/data/adb/modules_update"
    private const val KSU_DIR = "/data/adb/ksu"
    private const val ALLOWLIST = ".allowlist"
    private const val BUSYBOX = "/data/adb/ksu/bin/busybox"

    /** 备份结果：打包了多少个模块目录（0 表示没有可备份的东西）。 */
    data class BackupResult(val count: Int, val bytes: Long)

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 找一个能用的 tar。返回完整命令行前缀（含 busybox 调用形式）。 */
    private fun tarCmd(): String {
        val probes = listOf(
            "toybox tar",
            "/system/bin/toybox tar",
            "tar",
            "$BUSYBOX tar",
        )
        for (probe in probes) {
            val ok = Shell.cmd("$probe --help >/dev/null 2>&1 || $probe -h >/dev/null 2>&1").exec().isSuccess
            if (ok) return probe
        }
        return ""
    }

    private suspend fun packToCache(context: Context, tar: String, label: String, args: String): File? =
        withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, "${label}_${stamp()}.tar")
            val cmd = "$tar -cf '${out.absolutePath}' $args"
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess || !out.isFile || out.length() == 0L) {
                out.delete()
                null
            } else {
                out
            }
        }

    /** 把 cache 里那个 tar 流进用户选的 Uri，然后删掉临时文件。 */
    private suspend fun streamToUri(context: Context, file: File, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    file.inputStream().use { it.copyTo(sink) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false).also { file.delete() }
        }

    /** 备份 `/data/adb/modules` 到 [uri]。 */
    suspend fun backupModules(context: Context, uri: Uri): Result<BackupResult> =
        withContext(Dispatchers.IO) {
            val tar = tarCmd()
            if (tar.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("no tar found (toybox/busybox)"))
            }
            val count = Shell.cmd("ls -1 $MODULES_DIR | wc -l").exec().out
                .firstOrNull()?.trim()?.toIntOrNull() ?: 0
            if (count <= 0) {
                return@withContext Result.failure(IllegalStateException("$MODULES_DIR is empty"))
            }
            val packed = packToCache(context, tar, "modules", "-C $MODULES_DIR .")
                ?: return@withContext Result.failure(IllegalStateException("tar failed"))
            val bytes = packed.length()
            if (!streamToUri(context, packed, uri)) {
                return@withContext Result.failure(IllegalStateException("write to the picked file failed"))
            }
            Result.success(BackupResult(count, bytes))
        }

    /** 备份超级用户授权表（`/data/adb/ksu/.allowlist`）。 */
    suspend fun backupAllowlist(context: Context, uri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            val tar = tarCmd()
            if (tar.isEmpty()) return@withContext Result.failure(IllegalStateException("no tar found"))
            val packed = packToCache(context, tar, "allowlist", "-C $KSU_DIR $ALLOWLIST")
                ?: return@withContext Result.failure(IllegalStateException("tar failed"))
            val bytes = packed.length()
            if (!streamToUri(context, packed, uri)) {
                return@withContext Result.failure(IllegalStateException("write failed"))
            }
            Result.success(bytes)
        }

    /**
     * 从 [uri] 恢复模块：**解到 `modules_update`**，开机时由 ksud 合并。
     * 这样恢复失败/恢复错了都还能靠"不重启"撤回。
     */
    suspend fun restoreModules(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val tar = tarCmd()
        if (tar.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("no tar found (toybox/busybox)"))
        }
        val staged = File(context.cacheDir, "restore_${stamp()}.tar")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || staged.length() == 0L) {
            staged.delete()
            return@withContext Result.failure(IllegalStateException("read the picked file failed"))
        }
        val cmd = "mkdir -p '$MODULES_UPDATE_DIR' && $tar -xf '${staged.absolutePath}' -C $MODULES_UPDATE_DIR"
        val result = Shell.cmd(cmd).exec()
        staged.delete()
        if (!result.isSuccess) {
            return@withContext Result.failure(IllegalStateException(result.err.joinToString("\n").ifBlank { "tar failed" }))
        }
        val count = Shell.cmd("ls -1 $MODULES_UPDATE_DIR | wc -l").exec().out
            .firstOrNull()?.trim()?.toIntOrNull() ?: 0
        Result.success(count)
    }

    /** 从 [uri] 恢复授权表；直接写回 `/data/adb/ksu`（它不参与开机合并）。 */
    suspend fun restoreAllowlist(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val tar = tarCmd()
        if (tar.isEmpty()) return@withContext Result.failure(IllegalStateException("no tar found"))
        val staged = File(context.cacheDir, "restore_allow_${stamp()}.tar")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || staged.length() == 0L) {
            staged.delete()
            return@withContext Result.failure(IllegalStateException("read the picked file failed"))
        }
        val result = Shell.cmd("$tar -xf '${staged.absolutePath}' -C $KSU_DIR").exec()
        staged.delete()
        if (!result.isSuccess) {
            return@withContext Result.failure(IllegalStateException("tar failed"))
        }
        Result.success(Unit)
    }

    /** 恢复/刷模块后重启，让 ksud 在开机时合并 modules_update。 */
    fun reboot() {
        Shell.cmd("svc power reboot || reboot").exec()
    }
}

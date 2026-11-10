package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** 模块 / 授权表备份恢复。恢复先解到暂存区，再由 ksud 在下次开机合并。 */
object ModuleBackupOps {

    private const val MODULES_DIR = "/data/adb/modules"
    private const val MODULES_UPDATE_DIR = "/data/adb/modules_update"
    private const val KSU_DIR = "/data/adb/ksu"
    private const val ALLOWLIST = ".allowlist"
    private const val BUSYBOX = "/data/adb/ksu/bin/busybox"

    /** 解包暂存区根目录 */
    private const val STAGE_ROOT = "/data/adb/xec/tmp"
    private const val ALLOW_STAGE_PREFIX = "$KSU_DIR/.xec_allow_stage"

    // kernel/policy/allowlist.c:29 `#define FILE_MAGIC 0x7f4b5355`；uapi app_profile.h 版本上限。
    private const val ALLOW_MAGIC = 0x7f4b5355L
    private const val ALLOW_VERSION_MIN = 2L
    private const val ALLOW_VERSION_MAX = 4L

    /** 单次恢复允许复制的最大字节数（2 GiB）。 */
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024

    /** 备份结果：模块 id 列表与归档大小（0 个模块时不会走到成功分支）。 */
    data class BackupResult(val modules: List<String>, val bytes: Long)

    /** 恢复结果：真正搬进 `modules_update` 的模块，以及被跳过的（附原因）。 */
    data class RestoreResult(val installed: List<String>, val skipped: List<Skipped>) {
        data class Skipped(val id: String, val reason: String)
    }

    /** 带人话的失败原因，UI 直接显示，不再显示英文异常类名。 */
    class Refused(message: String) : IllegalStateException(message)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun exec(shell: Shell, cmd: String): Shell.Result = shell.newJob().add(cmd).exec()

    /** 把 root shell 的报错原样带出来（截断），不要只留一句"失败"。 */
    private fun detail(result: Shell.Result, what: String): String {
        val text = (result.err + result.out)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
        return if (text.isEmpty()) what else "$what：${text.take(240)}"
    }

    private fun firstNumber(text: String): Long? =
        Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** 找一个能用的 tar。返回完整命令行前缀（含 busybox 调用形式）；都不行返回 null。 */
    private fun tarCmd(shell: Shell): String? {
        for (probe in listOf("toybox tar", "/system/bin/toybox tar", "tar", "$BUSYBOX tar")) {
            if (exec(shell, "$probe --help >/dev/null 2>&1 || $probe -h >/dev/null 2>&1").isSuccess) {
                return probe
            }
        }
        return null
    }

    private suspend fun rootShell(): Shell = awaitRootShell()

    /** 把用户选的 Uri 复制进应用私有目录，**带总字节上限** */
    private fun copyUriTo(context: Context, uri: Uri, dest: File, maxBytes: Long): Boolean = runCatching {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { sink ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return@runCatching false
                    sink.write(buf, 0, n)
                }
            }
        } != null && dest.length() > 0L
    }.getOrDefault(false)

    /** 这次恢复最多允许多大：2 GiB，且不超过当前可用空间的一半（别把 /data 写满）。 */
    private fun archiveCap(context: Context): Long {
        val free = context.cacheDir.usableSpace
        val half = if (free > 0) free / 2 else MAX_ARCHIVE_BYTES
        return minOf(MAX_ARCHIVE_BYTES, maxOf(half, 1L * 1024 * 1024))
    }

    private suspend fun streamToUri(context: Context, file: File, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    file.inputStream().use { it.copyTo(sink) }
                } != null
            }.getOrDefault(false).also { file.delete() }
        }

    /** `id1、id2、id3 等 N 个`，用于提示语。 */
    // ------------------------------------------------------------------ 备份

    /** 备份 `/data/adb/modules` 到 [uri]。 */
    suspend fun backupModules(context: Context, uri: Uri): Result<BackupResult> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root（等不到提权），模块目录读不了"))
            }
            val tar = tarCmd(shell)
                ?: return@withContext Result.failure(Refused("这个系统上没有可用的 tar（toybox/busybox 都没有）"))
            if (!exec(shell, "test -d '$MODULES_DIR'").isSuccess) {
                return@withContext Result.failure(Refused("$MODULES_DIR 不存在：这台机器还没装过模块"))
            }
            // 空间预检：模块目录动辄几百 MB，写满 /data 会把系统拖垮。
            val needKb = exec(shell, "du -sk '$MODULES_DIR' 2>/dev/null").out
                .firstOrNull()?.let { firstNumber(it) }
            if (needKb != null && needKb > 0) {
                val freeKb = context.cacheDir.usableSpace / 1024L
                if (needKb > freeKb) {
                    return@withContext Result.failure(
                        Refused(
                            "$MODULES_DIR 约 ${needKb / 1024} MB，比临时目录可用的 " +
                                "${freeKb / 1024} MB 大，打包会中途写满存储 —— 先清一点空间再备份",
                        ),
                    )
                }
            }
            // 临时归档必须在**任何**退出路径上清掉（包括 TarScan 抛 IOException、
            // streamToUri 之前被取消），所以从创建那一刻就进 try/finally。
            val out = File(context.cacheDir, "modules_${stamp()}_${System.nanoTime() % 100000}.tar")
            try {
                val packed = exec(shell, "$tar -cf '${out.absolutePath}' -C '$MODULES_DIR' .")
                if (!packed.isSuccess || !out.isFile || out.length() == 0L) {
                    return@withContext Result.failure(Refused(detail(packed, "tar 打包失败")))
                }
                ensureActive()
                // 以归档本身为准：能解析出模块 id 才算备份成功，"ls | wc -l" 不再参与判断。
                val entries = TarScan.readEntries(out)
                    ?: return@withContext Result.failure(
                        Refused("打出来的 tar 我们自己读不了（tar 实现不兼容），已放弃这次备份"),
                    )
                val ids = TarScan.moduleIds(entries)
                if (ids.isEmpty()) {
                    return@withContext Result.failure(
                        Refused("$MODULES_DIR 里没有模块目录（只有散文件或空目录）"),
                    )
                }
                val bytes = out.length()
                if (!streamToUri(context, out, uri)) {
                    return@withContext Result.failure(Refused("写入你选的文件失败"))
                }
                Result.success(BackupResult(ids, bytes))
            } finally {
                out.delete()
            }
        }

    /** 备份超级用户授权表（`/data/adb/ksu/.allowlist`）。 */
    suspend fun backupAllowlist(context: Context, uri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root，授权表读不了"))
            }
            val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
            if (!exec(shell, "test -f '$KSU_DIR/$ALLOWLIST'").isSuccess) {
                return@withContext Result.failure(Refused("$KSU_DIR/$ALLOWLIST 不存在：这台机器还没有任何授权记录"))
            }
            val out = File(context.cacheDir, "allowlist_${stamp()}_${System.nanoTime() % 100000}.tar")
            try {
                val packed = exec(shell, "$tar -cf '${out.absolutePath}' -C '$KSU_DIR' '$ALLOWLIST'")
                if (!packed.isSuccess || !out.isFile || out.length() == 0L) {
                    return@withContext Result.failure(Refused(detail(packed, "tar 打包授权表失败")))
                }
                val bytes = out.length()
                if (!streamToUri(context, out, uri)) {
                    return@withContext Result.failure(Refused("写入你选的文件失败"))
                }
                Result.success(bytes)
            } finally {
                out.delete()
            }
        }

    // ------------------------------------------------------------------ 恢复

    /** 从 [uri] 恢复模块：先校验 tar 条目，再解到暂存区，最后把完整模块目录搬进 `modules_update`，开机时由 ksud 合并 */
    suspend fun restoreModules(context: Context, uri: Uri): Result<RestoreResult> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root，无法写入 $MODULES_UPDATE_DIR"))
            }
            val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
            val staged = File(context.cacheDir, "restore_${stamp()}_${System.nanoTime() % 100000}.tar")
            if (!copyUriTo(context, uri, staged, archiveCap(context))) {
                staged.delete()
                return@withContext Result.failure(Refused("读取你选的文件失败（可能不是本地文件，或已被移动）"))
            }
            try {
                val entries = TarScan.readEntries(staged)
                    ?: return@withContext Result.failure(
                        Refused(
                            "这不是有效的 tar，或里面有越界条目（绝对路径 / ../ / 硬链接 / " +
                                "设备节点 / 逃出根目录的软链接），已拒绝导入",
                        ),
                    )
                val ids = TarScan.moduleIds(entries)
                if (ids.isEmpty()) {
                    return@withContext Result.failure(
                        Refused(
                            "这个 tar 里没有模块目录 —— 顶层应当是一个个模块目录（像 " +
                                "$MODULES_DIR 那样），不是散文件",
                        ),
                    )
                }
                // 每次恢复用**独立**暂存目录：固定路径会让两次恢复（或上一个任务被杀后
                var stageDir = ""
                var attempt = 0
                while (attempt < 4 && stageDir.isEmpty()) {
                    attempt++
                    val candidate = "$STAGE_ROOT/restore_${stamp()}_${Random.nextInt(100000, 999999)}"
                    val made = exec(
                        shell,
                        "mkdir -p '$STAGE_ROOT' && mkdir '$candidate' 2>/dev/null && mkdir -p '$MODULES_UPDATE_DIR'",
                    )
                    if (made.isSuccess && exec(shell, "test -d '$candidate'").isSuccess) {
                        stageDir = candidate
                    }
                }
                if (stageDir.isEmpty()) {
                    return@withContext Result.failure(Refused("占不到临时目录（$STAGE_ROOT 不可写？）"))
                }
                val installed = ArrayList<String>()
                val skipped = ArrayList<RestoreResult.Skipped>()
                try {
                    val unpack = exec(shell, "$tar -xf '${staged.absolutePath}' -C '$stageDir'")
                    if (!unpack.isSuccess) {
                        return@withContext Result.failure(Refused(detail(unpack, "解包失败")))
                    }
                    ensureActive()
                    for (id in ids) {
                        ensureActive()
                        // id 已经过 TarScan.MODULE_ID 白名单（只含 [A-Za-z0-9._-] 且字母开头），
                        // 拼命令安全；这里再判一次，纯粹是别让"以后有人放宽正则"变成命令注入。
                        if (!TarScan.MODULE_ID.matches(id)) {
                            skipped += RestoreResult.Skipped(id, "目录名不是合法的模块 id")
                            continue
                        }
                        // 真解出来了才搬：否则 modules_update 里会出现一个 ksud 认不出的垃圾目录。
                        val hasProp = exec(shell, "test -d '$stageDir/$id' && test -f '$stageDir/$id/module.prop'")
                        if (!hasProp.isSuccess) {
                            skipped += RestoreResult.Skipped(id, "解包里没有这个目录或没有 module.prop")
                            continue
                        }
                        // ksud 的 list 以 module.prop 的 id= 为准，而合并/脚本执行按**目录名**走
                        val propText = exec(shell, "cat '$stageDir/$id/module.prop' 2>/dev/null")
                            .out.joinToString("\n")
                        val propId = TarScan.modulePropId(propText)
                        if (propId.isNullOrEmpty()) {
                            skipped += RestoreResult.Skipped(id, "module.prop 里没有（有效的）id= 行")
                            continue
                        }
                        if (propId != id) {
                            skipped += RestoreResult.Skipped(id, "module.prop 的 id=$propId 与目录名不一致")
                            continue
                        }
                        // 安全替换：`modules_update/<id>` 里可能已经躺着一个待更新模块（用户刚刷过
                        val aside = "$STAGE_ROOT/prev_${id}_${Random.nextInt(100000, 999999)}"
                        val mv = exec(
                            shell,
                            "U='$MODULES_UPDATE_DIR/$id'; B='$aside'; S='$stageDir/$id'; " +
                                "if [ -e \"\$B\" ]; then echo XEC_FAIL; exit 0; fi; " +
                                "if [ -e \"\$U\" ]; then mv \"\$U\" \"\$B\" || { echo XEC_FAIL; exit 0; }; fi; " +
                                "if mv \"\$S\" \"\$U\"; then rm -rf \"\$B\"; " +
                                "  if [ -d \"\$U\" ] && [ ! -e \"\$B\" ]; then echo XEC_OK; else echo XEC_FAIL; fi; " +
                                "else [ -e \"\$B\" ] && mv \"\$B\" \"\$U\"; echo XEC_FAIL; fi",
                        )
                        if (mv.out.any { it.trim() == "XEC_OK" }) {
                            installed += id
                        } else {
                            skipped += RestoreResult.Skipped(id, "搬进 $MODULES_UPDATE_DIR 失败（原有的待更新已放回原处）")
                        }
                    }
                } finally {
                    exec(shell, "rm -rf '$stageDir'")
                }
                if (installed.isEmpty()) {
                    val why = skipped.joinToString("；") { "${it.id}（${it.reason}）" }
                    return@withContext Result.failure(
                        Refused(
                            "解包成功，但没有一个模块能搬进 $MODULES_UPDATE_DIR" +
                                if (why.isEmpty()) "" else "：$why",
                        ),
                    )
                }
                Result.success(RestoreResult(installed, skipped))
            } finally {
                staged.delete()
            }
        }

    /** 从 [uri] 恢复授权表：校验 → 解到暂存目录 → 校验文件头 → 原子 rename 覆盖 */
    suspend fun restoreAllowlist(context: Context, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root，无法写入 $KSU_DIR"))
            }
            val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
            val staged = File(context.cacheDir, "restore_allow_${stamp()}_${System.nanoTime() % 100000}.tar")
            if (!copyUriTo(context, uri, staged, archiveCap(context))) {
                staged.delete()
                return@withContext Result.failure(Refused("读取你选的文件失败"))
            }
            try {
                val entries = TarScan.readEntries(staged)
                    ?: return@withContext Result.failure(Refused("这不是有效的 tar，已拒绝导入"))
                val hasAllowlist = entries.any {
                    it.name == ALLOWLIST || it.name.endsWith("/$ALLOWLIST")
                }
                if (!hasAllowlist) {
                    return@withContext Result.failure(Refused("这个 tar 里没有 $ALLOWLIST"))
                }
                // 每次恢复用独立暂存目录，并且用**不带 -p 的 mkdir 原子占用**：固定路径或
                // "先 rm -rf 再 mkdir" 都可能把另一个任务正在用的目录删掉。
                var allowStage = ""
                var attempt = 0
                while (attempt < 4 && allowStage.isEmpty()) {
                    attempt++
                    val candidate = "${ALLOW_STAGE_PREFIX}_${Random.nextInt(100000, 999999)}"
                    if (exec(shell, "mkdir '$candidate' 2>/dev/null").isSuccess) {
                        allowStage = candidate
                    }
                }
                if (allowStage.isEmpty()) {
                    return@withContext Result.failure(Refused("占不到授权表暂存目录（$KSU_DIR 不可写？）"))
                }
                val unpack = exec(shell, "$tar -xf '${staged.absolutePath}' -C '$allowStage'")
                if (!unpack.isSuccess) {
                    exec(shell, "rm -rf '$allowStage'")
                    return@withContext Result.failure(Refused(detail(unpack, "解包失败")))
                }
                val stagedAllow = "$allowStage/$ALLOWLIST"
                // 只认普通文件：tar 里把 .allowlist 做成软链接，`mv` 搬过去的就是链接本身，
                // 内核开机读到的会是链接目标（magic 一验不过，整表丢弃 = 全丢 root）。
                if (!exec(shell, "[ -f '$stagedAllow' ] && [ ! -L '$stagedAllow' ]").isSuccess) {
                    exec(shell, "rm -rf '$allowStage'")
                    return@withContext Result.failure(
                        Refused("备份里的 $ALLOWLIST 不是普通文件（软链接/目录），已拒绝导入"),
                    )
                }
                // 内核读文件时先看 magic 再看 version（allowlist.c:537/548）：
                // 任一项不对，整张表会被直接丢掉 —— 等于重启后所有 root 都没了。
                val head = exec(shell, "od -An -tu4 -N8 '$stagedAllow'").out
                    .joinToString(" ")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                val magic = head.getOrNull(0)?.toLongOrNull()
                val version = head.getOrNull(1)?.toLongOrNull()
                if (magic != ALLOW_MAGIC) {
                    exec(shell, "rm -rf '$allowStage'")
                    return@withContext Result.failure(
                        Refused("这不是本内核的授权表（magic 对不上），写进去重启后整表会被丢弃"),
                    )
                }
                if (version == null || version < ALLOW_VERSION_MIN || version > ALLOW_VERSION_MAX) {
                    exec(shell, "rm -rf '$allowStage'")
                    return@withContext Result.failure(
                        Refused("授权表版本 $version 当前内核不支持（只支持 $ALLOW_VERSION_MIN..$ALLOW_VERSION_MAX）"),
                    )
                }
                val backupName = "$KSU_DIR/$ALLOWLIST.bak-${stamp()}"
                val install = exec(
                    shell,
                    "chown root:root '$stagedAllow' 2>/dev/null; chmod 600 '$stagedAllow' && " +
                        "if [ -f '$KSU_DIR/$ALLOWLIST' ]; then cp -f '$KSU_DIR/$ALLOWLIST' '$backupName'; fi && " +
                        "mv -f '$stagedAllow' '$KSU_DIR/$ALLOWLIST'",
                )
                exec(shell, "rm -rf '$allowStage'")
                if (!install.isSuccess) {
                    return@withContext Result.failure(Refused("写入授权表失败（已放弃，原文件未改动）"))
                }
                Result.success(Unit)
            } finally {
                staged.delete()
            }
        }

    /** 恢复/刷模块后重启，让 ksud 在开机时合并 modules_update */
    suspend fun reboot() = withContext(Dispatchers.IO) {
        val shell = getRootShell()
        if (shell.isRoot) exec(shell, "svc power reboot || reboot")
        Unit
    }
}

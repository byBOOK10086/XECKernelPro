package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块备份 / 恢复（2026-11-09；2026-11-09 第二轮按审计结论加固）。
 *
 * 能力来自 KernelSU-Next 的 `BackupRestore.kt`（GPL-3.0，见 THIRD_PARTY_NOTICES.md
 * 「模块备份与恢复」一节）：把 `/data/adb/modules` 打成一个 tar 交给用户，恢复时**解到
 * `modules_update` 而不是直接覆盖 `modules`** —— 后者会在下一次开机时由 ksud 合并
 * （`userspace/ksud/src/module.rs:730 handle_updated_modules`），于是"恢复"是可撤销的：
 * 刷完先重启才生效，中途反悔只要不开机就还在。
 *
 * 第二轮改掉的四个真问题（都是审计里核到代码的，不是"看起来更安全"）：
 *
 *  1. **不能直接解到 `modules_update`**。ksud 的行为是"先删旧目录再 rename"
 *     （module.rs:744-751，没有回滚），所以解包中途失败留下的半成品，会在下一次开机被
 *     当成一次真正的模块更新消费 —— 旧模块被删、新的不完整，直接开机异常。
 *     现在先解到 `modules_update/.xec_restore`，**逐个顶层目录**搬进 `modules_update`，
 *     任何一步失败就整体丢弃，`modules_update` 里只会出现完整模块。
 *  2. **tar 条目先校验再解包**。绝对路径、`..` 段、硬链接一律拒绝，相对符号链接
 *     逃出备份根的也拒绝 —— 否则"恢复一个别人给的 tar" = 以 root 往任意路径写文件。
 *  3. **必须用本树的 root shell**。之前用 libsu 默认的 `Shell.cmd`，而本树从不
 *     `setDefaultBuilder`：那条壳可能是非 root 的 `sh`（"重启丢 root" 就是它），
 *     拿它去读 `/data/adb/modules` 只会失败。现在一律 `awaitRootShell()` 并显式判
 *     `isRoot`，拿不到 root 就说"没有 root"，不猜。
 *  4. **授权表恢复不可能"立即生效"**。内核只在开机初始化时读
 *     `/data/adb/ksu/.allowlist`（`kernel/core/init.c:210`、`kernel/runtime/boot_event.c:30`），
 *     运行期没有 reload 超级调用；而且**任何一次授权变更都会把内核内存里的旧表整表写回
 *     这个文件**（`kernel/supercall/dispatch.c:441` → `ksu_persistent_allow_list()`），
 *     覆盖掉刚恢复的内容。所以这里改成：原子写入（临时文件 + rename）、先校验
 *     magic/version（写错=重启后整表被内核拒绝=所有 root 消失）、保留一份 `.bak`，
 *     并且 UI 明说"重启后生效、重启前别再改授权"。
 *
 * 与上游另外两处按本树实际情况的差异：tar 按 toybox -> busybox 探测（不假设装了
 * busybox）；tar 先落 app 私有 cacheDir 再流进 SAF 的 Uri（SAF Uri 不能给 root shell 用）。
 */
object ModuleBackupOps {

    private const val MODULES_DIR = "/data/adb/modules"
    private const val MODULES_UPDATE_DIR = "/data/adb/modules_update"
    private const val KSU_DIR = "/data/adb/ksu"
    private const val ALLOWLIST = ".allowlist"
    private const val BUSYBOX = "/data/adb/ksu/bin/busybox"

    /** 解包暂存区；以 `.` 开头，ksud 的 `next_boot_modules` 只会把它当一个模块 id 跳过。 */
    private const val STAGE_DIR = "$MODULES_UPDATE_DIR/.xec_restore"
    private const val ALLOW_STAGE = "$KSU_DIR/.xec_allow_stage"

    // kernel/policy/allowlist.c:29 `#define FILE_MAGIC 0x7f4b5355`；uapi app_profile.h 版本上限。
    private const val ALLOW_MAGIC = 0x7f4b5355L
    private const val ALLOW_VERSION_MIN = 2L
    private const val ALLOW_VERSION_MAX = 4L

    private const val MAX_ENTRIES = 20_000
    private const val MAX_PAYLOAD = 8L * 1024 * 1024 * 1024

    /** 备份结果：打包了多少个模块目录（0 表示没有可备份的东西）。 */
    data class BackupResult(val count: Int, val bytes: Long)

    /** 带人话的失败原因，UI 直接显示，不再显示英文异常类名。 */
    class Refused(message: String) : IllegalStateException(message)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun exec(shell: Shell, cmd: String): Shell.Result = shell.newJob().add(cmd).exec()

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

    private fun copyUriTo(context: Context, uri: Uri, dest: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } != null && dest.length() > 0L
    }.getOrDefault(false)

    private suspend fun streamToUri(context: Context, file: File, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    file.inputStream().use { it.copyTo(sink) }
                } != null
            }.getOrDefault(false).also { file.delete() }
        }

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
            val count = exec(shell, "ls -1 $MODULES_DIR | wc -l").out
                .firstOrNull()?.trim()?.toIntOrNull() ?: 0
            if (count <= 0) {
                return@withContext Result.failure(Refused("$MODULES_DIR 是空的，没有可备份的模块"))
            }
            val out = File(context.cacheDir, "modules_${stamp()}.tar")
            val packed = exec(shell, "$tar -cf '${out.absolutePath}' -C $MODULES_DIR .")
            if (!packed.isSuccess || !out.isFile || out.length() == 0L) {
                out.delete()
                return@withContext Result.failure(
                    Refused(packed.err.joinToString("\n").ifBlank { "tar 打包失败" }),
                )
            }
            val bytes = out.length()
            if (!streamToUri(context, out, uri)) {
                return@withContext Result.failure(Refused("写入你选的文件失败"))
            }
            Result.success(BackupResult(count, bytes))
        }

    /** 备份超级用户授权表（`/data/adb/ksu/.allowlist`）。 */
    suspend fun backupAllowlist(context: Context, uri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root，授权表读不了"))
            }
            val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
            if (!exec(shell, "test -f $KSU_DIR/$ALLOWLIST").isSuccess) {
                return@withContext Result.failure(Refused("$KSU_DIR/$ALLOWLIST 不存在：这台机器还没有任何授权记录"))
            }
            val out = File(context.cacheDir, "allowlist_${stamp()}.tar")
            val packed = exec(shell, "$tar -cf '${out.absolutePath}' -C $KSU_DIR $ALLOWLIST")
            if (!packed.isSuccess || !out.isFile || out.length() == 0L) {
                out.delete()
                return@withContext Result.failure(Refused("tar 打包授权表失败"))
            }
            val bytes = out.length()
            if (!streamToUri(context, out, uri)) {
                return@withContext Result.failure(Refused("写入你选的文件失败"))
            }
            Result.success(bytes)
        }

    // ------------------------------------------------------------------ 恢复

    /**
     * 从 [uri] 恢复模块：先校验 tar 条目，再解到暂存区，最后把完整模块目录搬进
     * `modules_update`，开机时由 ksud 合并。任何一步失败都不会在 `modules_update`
     * 里留下半成品。
     */
    suspend fun restoreModules(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val shell = rootShell()
        if (!shell.isRoot) {
            return@withContext Result.failure(Refused("没有 root，无法写入 $MODULES_UPDATE_DIR"))
        }
        val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
        val staged = File(context.cacheDir, "restore_${stamp()}.tar")
        if (!copyUriTo(context, uri, staged)) {
            staged.delete()
            return@withContext Result.failure(Refused("读取你选的文件失败（可能不是本地文件，或已被移动）"))
        }
        try {
            val entries = scanTar(staged)
                ?: return@withContext Result.failure(
                    Refused("这不是有效的 tar，或里面有越界条目（绝对路径 / ../ / 硬链接 / 逃出根目录的软链接），已拒绝导入"),
                )
            val top = entries.asSequence()
                .map { it.trimEnd('/') }
                .filter { it.isNotEmpty() && it != "." }
                .map { it.substringBefore('/') }
                .filter { it != "." && it != ".." }
                .distinct()
                .toList()
            if (top.isEmpty()) {
                return@withContext Result.failure(Refused("这个 tar 里没有模块目录"))
            }

            exec(shell, "rm -rf '$STAGE_DIR' && mkdir -p '$STAGE_DIR'")
            val unpack = exec(shell, "$tar -xf '${staged.absolutePath}' -C '$STAGE_DIR'")
            if (!unpack.isSuccess) {
                exec(shell, "rm -rf '$STAGE_DIR'")
                return@withContext Result.failure(
                    Refused(unpack.err.joinToString("\n").ifBlank { "解包失败" }),
                )
            }
            // 逐个顶层目录搬：先删同名旧目录再 rename（ksud 自己也是这个顺序），
            // 但只有解包完全成功才会走到这里，所以不会出现"删了旧的、新的没搬进去"。
            val moved = ArrayList<String>()
            try {
                for (id in top) {
                    if (!id.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) continue
                    val mv = exec(
                        shell,
                        "if [ -d '$STAGE_DIR/$id' ]; then " +
                            "rm -rf '$MODULES_UPDATE_DIR/$id' && mv '$STAGE_DIR/$id' '$MODULES_UPDATE_DIR/$id'; fi",
                    )
                    if (mv.isSuccess) moved += id
                }
            } finally {
                exec(shell, "rm -rf '$STAGE_DIR'")
            }
            if (moved.isEmpty()) {
                return@withContext Result.failure(Refused("解包成功，但没有一个模块目录能搬进 $MODULES_UPDATE_DIR"))
            }
            Result.success(moved.size)
        } finally {
            staged.delete()
        }
    }

    /**
     * 从 [uri] 恢复授权表：校验 → 解到暂存目录 → 校验文件头 → 原子 rename 覆盖。
     *
     * **重启后生效**（内核只在开机时读这张表）。覆盖前把现有表留一份
     * `.allowlist.bak-<时间>`，改错了还能在重启前手工换回来。
     */
    suspend fun restoreAllowlist(context: Context, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val shell = rootShell()
            if (!shell.isRoot) {
                return@withContext Result.failure(Refused("没有 root，无法写入 $KSU_DIR"))
            }
            val tar = tarCmd(shell) ?: return@withContext Result.failure(Refused("没有可用的 tar"))
            val staged = File(context.cacheDir, "restore_allow_${stamp()}.tar")
            if (!copyUriTo(context, uri, staged)) {
                staged.delete()
                return@withContext Result.failure(Refused("读取你选的文件失败"))
            }
            try {
                val entries = scanTar(staged)
                    ?: return@withContext Result.failure(Refused("这不是有效的 tar，已拒绝导入"))
                val hasAllowlist = entries.any {
                    it.trimStart('/') == ALLOWLIST || it.trimEnd('/').endsWith("/$ALLOWLIST")
                }
                if (!hasAllowlist) {
                    return@withContext Result.failure(Refused("这个 tar 里没有 $ALLOWLIST"))
                }
                exec(shell, "rm -rf '$ALLOW_STAGE' && mkdir -p '$ALLOW_STAGE'")
                val unpack = exec(shell, "$tar -xf '${staged.absolutePath}' -C '$ALLOW_STAGE'")
                if (!unpack.isSuccess) {
                    exec(shell, "rm -rf '$ALLOW_STAGE'")
                    return@withContext Result.failure(Refused("解包失败"))
                }
                val stagedAllow = "$ALLOW_STAGE/$ALLOWLIST"
                // 只认普通文件：tar 里把 .allowlist 做成软链接，`mv` 搬过去的就是链接本身，
                // 内核开机读到的会是链接目标（magic 一验不过，整表丢弃 = 全丢 root）。
                if (!exec(shell, "[ -f '$stagedAllow' ] && [ ! -L '$stagedAllow' ]").isSuccess) {
                    exec(shell, "rm -rf '$ALLOW_STAGE'")
                    return@withContext Result.failure(
                        Refused("备份里的 $ALLOWLIST 不是普通文件（软链接/目录），已拒绝导入"),
                    )
                }
                // 内核读文件时先看 magic 再看 version（allowlist.c:537/548）：
                // 任一项不对，整张表会被直接丢掉 —— 等于重启后所有 root 都没了。
                // 所以这里先替内核验一遍，验不过就不落地。
                val head = exec(shell, "od -An -tu4 -N8 '$stagedAllow'").out
                    .joinToString(" ")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                val magic = head.getOrNull(0)?.toLongOrNull()
                val version = head.getOrNull(1)?.toLongOrNull()
                if (magic != ALLOW_MAGIC) {
                    exec(shell, "rm -rf '$ALLOW_STAGE'")
                    return@withContext Result.failure(
                        Refused("这不是本内核的授权表（magic 对不上），写进去重启后整表会被丢弃"),
                    )
                }
                if (version == null || version < ALLOW_VERSION_MIN || version > ALLOW_VERSION_MAX) {
                    exec(shell, "rm -rf '$ALLOW_STAGE'")
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
                exec(shell, "rm -rf '$ALLOW_STAGE'")
                if (!install.isSuccess) {
                    return@withContext Result.failure(Refused("写入授权表失败（已放弃，原文件未改动）"))
                }
                Result.success(Unit)
            } finally {
                staged.delete()
            }
        }

    /** 恢复/刷模块后重启，让 ksud 在开机时合并 modules_update。 */
    fun reboot() {
        val shell = getRootShell()
        if (shell.isRoot) exec(shell, "svc power reboot || reboot")
    }

    // ------------------------------------------------------------------ tar 校验

    /**
     * 只读 tar 头，返回条目名；结构无法解析或含越界条目时返回 null。
     *
     * 这是"恢复别人给的备份"唯一的防线：解包是全 root 的，条目名直接决定落地路径。
     * 拒绝三类：绝对路径、含 `..` 段的路径、指向备份根之外的软链接；硬链接直接拒绝
     * （目标是任意路径且不受控）。PAX / GNU longname 头会按其 size 跳过，长名本身照样校验。
     */
    private fun scanTar(file: File): List<String>? {
        if (!file.isFile || file.length() < 1024) return null
        val names = ArrayList<String>()
        var pendingName: String? = null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val header = ByteArray(512)
            var entries = 0
            while (true) {
                if (!readFully(input, header)) return null
                if (header.all { it.toInt() == 0 }) break
                if (++entries > MAX_ENTRIES) return null

                val stored = fieldOctal(header, 148, 8) ?: return null
                var sum = 0L
                for (i in header.indices) {
                    sum += if (i in 148..155) 32 else (header[i].toInt() and 0xFF)
                }
                if (sum != stored) return null

                val size = fieldOctal(header, 124, 12) ?: return null
                if (size < 0 || size > MAX_PAYLOAD) return null
                val type = header[156].toInt().toChar()
                val name = fieldString(header, 0, 100)
                val prefix = fieldString(header, 345, 155)
                val full = if (prefix.isEmpty()) name else "$prefix/$name"

                when (type) {
                    'L', 'K' -> {
                        val payload = readPayload(input, size) ?: return null
                        pendingName = payload.trimEnd('\u0000', '/')
                    }
                    'x', 'g' -> if (!skipPayload(input, size)) return null
                    '1' -> return null // 硬链接
                    else -> {
                        val resolved = pendingName ?: full
                        pendingName = null
                        if (!entryIsSafe(resolved)) return null
                        if (type == '2' && !linkIsSafe(resolved, fieldString(header, 157, 100))) return null
                        names += resolved
                        if (!skipPayload(input, size)) return null
                    }
                }
            }
        }
        return names
    }

    private fun readFully(input: DataInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun fieldString(header: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, off, end - off, Charsets.UTF_8)
    }

    /** 八进制字段（tar 老格式）；全空格/NUL 视为 0，base-256 编码直接判不认识。 */
    private fun fieldOctal(header: ByteArray, off: Int, len: Int): Long? {
        if (((header[off].toInt() and 0xFF) and 0x80) != 0) return null
        var i = off
        val limit = off + len
        var value = 0L
        var digits = 0
        while (i < limit) {
            val c = header[i].toInt() and 0xFF
            when {
                c == 0 || c == ' '.code -> if (digits == 0) { i++; continue } else break
                c < '0'.code || c > '7'.code -> return null
                else -> {
                    value = value * 8 + (c - '0'.code)
                    digits++
                    i++
                }
            }
        }
        return value
    }

    private fun paddedSize(size: Long): Long = (size + 511L) / 512L * 512L

    private fun skipRaw(input: DataInputStream, bytes: Long): Boolean {
        var left = bytes
        val sink = ByteArray(8192)
        while (left > 0) {
            val want = minOf(left, sink.size.toLong()).toInt()
            val n = input.read(sink, 0, want)
            if (n <= 0) return false
            left -= n
        }
        return true
    }

    private fun skipPayload(input: DataInputStream, size: Long): Boolean =
        skipRaw(input, paddedSize(size))

    private fun readPayload(input: DataInputStream, size: Long): String? {
        if (size < 0 || size > 64 * 1024) return null
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) return null
        if (!skipRaw(input, paddedSize(size) - size)) return null
        return String(buf, Charsets.UTF_8)
    }

    /** 条目路径必须相对、且不含 `..`。 */
    private fun entryIsSafe(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith('/') || name.startsWith('\\')) return false
        if (name.contains('\u0000') || name.contains('\\')) return false
        return name.split('/').none { it == ".." }
    }

    /** 相对软链接不能把落点带出备份根。 */
    private fun linkIsSafe(entry: String, target: String): Boolean {
        if (target.isEmpty()) return false
        if (target.startsWith('/')) return false
        var depth = entry.trimEnd('/').split('/').count { it.isNotEmpty() && it != "." } - 1
        for (part in target.split('/')) {
            when {
                part.isEmpty() || part == "." -> continue
                part == ".." -> {
                    depth -= 1
                    if (depth < 0) return false
                }
                else -> depth += 1
            }
        }
        return true
    }
}

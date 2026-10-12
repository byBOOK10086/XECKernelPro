@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package me.weishu.kernelsu.ui.screen.terminal

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.util.getRootShell
import com.topjohnwu.superuser.Shell
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/*
 * MT 管理器式可视化文件管理（替换原先只出名字的 ls -1Ap 列表）。
 * 详细列表 + 面包屑 + 多选 + 复制/剪切/粘贴 + 重命名/新建/删除 + 权限(rwx+八进制)/
 * 属主 + 属性(含 SHA-256) + 递归搜索 + 书签 + 排序/反序 + 隐藏开关 + 系统应用打开/
 * 分享(cacheDir+FileProvider) + 安装 APK + 执行 + 解压 + 文本查看/编辑(base64 读写)。
 * 后端走 libsu 持久 root shell，路径一律单引号转义。
 */

private const val FM_PREFS = "xec_file_manager"
private const val FM_KEY_BOOKMARKS = "bookmarks"
private const val FM_KEY_LAST_DIR = "last_dir"
private const val FM_KEY_SORT = "sort"
private const val FM_KEY_REVERSE = "reverse"
private const val FM_KEY_HIDDEN = "show_hidden"
private const val FM_TEXT_LIMIT = 512 * 1024

internal enum class FmSort { NAME, SIZE, TIME }

internal data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val linkTarget: String?,
    val size: Long,
    val perms: String,
    val octal: Int,
    val owner: String,
    val group: String,
    val date: String,
)

private data class FmClip(val cut: Boolean, val paths: List<String>)

/** 列目录结果：code!=0 表示命令失败；note 非空表示走了兜底路径（界面要显式提示）。 */
internal data class FmListing(val code: Int, val entries: List<FsEntry>, val note: String?)

// `ls -l` 的日期列有两种形态：Android(toybox) 是 %F %H:%M（2026-10-12 06:00），
// busybox/老工具箱是 POSIX 的 %b %e %H:%M（Oct 12 06:00）或 %b %e  %Y（Oct 12  2025）；
// 设备文件的大小列是 "major, minor"。以前只有一条严格正则，解析不出来的行被
// `?: continue` 静默丢掉 —— 只要输出格式不是预期那一种，界面就是"一个文件都显示不出来"。
private val FM_LS_ISO = Regex(
    "^([-dlbcps][rwxSsTt-]{9}[.+@]?)[ \\t]+\\d+[ \\t]+(\\S+)[ \\t]+(\\S+)[ \\t]+((?:\\d+,\\s*\\d+)|\\d+)" +
        "[ \\t]+(\\d{4}-\\d{2}-\\d{2})[ \\t]+(\\d{1,2}:\\d{2}(?::\\d{2})?)[ \\t]+(.+)$"
)

private val FM_LS_POSIX = Regex(
    "^([-dlbcps][rwxSsTt-]{9}[.+@]?)[ \\t]+\\d+[ \\t]+(\\S+)[ \\t]+(\\S+)[ \\t]+((?:\\d+,\\s*\\d+)|\\d+)" +
        "[ \\t]+([A-Za-z]{3})[ \\t]+(\\d{1,2})[ \\t]+(\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{4})[ \\t]+(.+)$"
)

internal object FsShell {

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 执行脚本。先走 libsu 的静态全局 Shell（`Shell.cmd`，与终端页/检测页同源，
     * 现网已验证可用），失败再走应用自己的 root shell（`KsuCli.SHELL`）。
     * 两条都失败时返回 -1 + 每一路的失败原因，让界面能把真实原因显示出来，
     * 而不是让用户对着一个空列表猜。
     *
     * `retry=false` 用于**有副作用的任意脚本**（例如"在终端里执行这个文件"）：
     * 那种命令重放一次可能产生两次副作用，宁可只试一遍。
     */
    suspend fun exec(script: String, retry: Boolean = true): Pair<Int, List<String>> =
        withContext(Dispatchers.IO) {
        val problems = ArrayList<String>()
        val attempts: List<Pair<String, () -> Shell.Result>> = if (retry) {
            listOf(
                "global" to { Shell.cmd(script).exec() },
                "ksu" to { getRootShell().newJob().add(script).exec() },
            )
        } else {
            listOf("ksu" to { getRootShell().newJob().add(script).exec() })
        }
        for ((label, run) in attempts) {
            val outcome = runCatching { run() }
            val result = outcome.getOrNull()
            if (result == null) {
                problems.add("$label: " + (outcome.exceptionOrNull()?.message ?: "failed"))
                continue
            }
            val lines = result.out + result.err
            if (result.code == 0) return@withContext 0 to lines
            val firstBad = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            problems.add("$label: exit ${result.code}" + if (firstBad.isEmpty()) "" else " · $firstBad")
        }
        -1 to problems
    }

    fun firstError(lines: List<String>): String = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun joinPath(dir: String, name: String): String =
        if (dir == "/") "/$name" else dir.trimEnd('/') + "/" + name

    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/').ifEmpty { "/" }
        if (trimmed == "/") return "/"
        return trimmed.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun octalOf(perms: String): Int {
        var value = 0
        val bits = intArrayOf(4, 2, 1)
        for (i in 0 until 9) {
            val c = perms.getOrNull(i) ?: '-'
            if (c != '-' && c != 'S' && c != 'T') value = value or (bits[i % 3] shl (6 - (i / 3) * 3))
        }
        if (perms.getOrNull(2) == 's' || perms.getOrNull(2) == 'S') value = value or 0x800
        if (perms.getOrNull(5) == 's' || perms.getOrNull(5) == 'S') value = value or 0x400
        if (perms.getOrNull(8) == 't' || perms.getOrNull(8) == 'T') value = value or 0x200
        return value
    }

    /** 一行 `ls -l` → FsEntry；两种日期形态都试，试不出来返回 null（交给 stat 兜底）。 */
    private fun parseLine(dir: String, line: String): FsEntry? {
        FM_LS_ISO.find(line)?.let { m ->
            return buildEntry(
                dir = dir,
                mode = m.groupValues[1],
                owner = m.groupValues[2],
                group = m.groupValues[3],
                size = m.groupValues[4],
                date = m.groupValues[5] + " " + m.groupValues[6],
                rest = m.groupValues[7],
            )
        }
        FM_LS_POSIX.find(line)?.let { m ->
            return buildEntry(
                dir = dir,
                mode = m.groupValues[1],
                owner = m.groupValues[2],
                group = m.groupValues[3],
                size = m.groupValues[4],
                date = m.groupValues[5] + " " + m.groupValues[6] + " " + m.groupValues[7],
                rest = m.groupValues[8],
            )
        }
        return null
    }

    private fun buildEntry(
        dir: String,
        mode: String,
        owner: String,
        group: String,
        size: String,
        date: String,
        rest: String,
    ): FsEntry? {
        if (mode.length < 10) return null
        val type = mode[0]
        val perms = mode.substring(1, 10)
        val isLink = type == 'l'
        val arrow = rest.indexOf(" -> ")
        val name = (if (isLink && arrow >= 0) rest.substring(0, arrow) else rest).trimEnd()
        if (name.isEmpty() || name == "." || name == "..") return null
        val target = if (isLink && arrow >= 0) rest.substring(arrow + 4).trim() else null
        // 设备文件的大小列是 "major, minor"，没有可比大小，记 0
        val sizeValue = size.replace(" ", "").substringBefore(',').toLongOrNull() ?: 0L
        return FsEntry(
            name = name,
            path = joinPath(dir, name),
            isDir = type == 'd',
            isLink = isLink,
            linkTarget = target,
            size = sizeValue,
            perms = perms,
            octal = octalOf(perms),
            owner = owner,
            group = group,
            date = date,
        )
    }

    /**
     * `ls -l` 一行都没解析出来时的兜底：先用 `ls -1A` 拿到名字，再一次性 `stat` 补属性。
     * 只要目录里真的有东西，这条路就一定能把文件列出来（慢一点也认了）。
     */
    private suspend fun statEntries(dir: String, names: List<String>): List<FsEntry> {
        val out = ArrayList<FsEntry>(names.size)
        for (chunk in names.chunked(64)) {
            val args = chunk.joinToString(" ") { quote(it) }
            val script = "cd -- " + quote(dir) + " 2>/dev/null || exit 3\n" +
                "LC_ALL=C stat -c '%A|%a|%U|%G|%s|%Y|%F|%N' -- " + args + " 2>&1\n"
            val (code, lines) = exec(script)
            if (code != 0 && lines.none { it.contains('|') }) continue
            for (line in lines) {
                // 文件名里可能有 '|'，只在最后一段（%N）里保留
                val parts = line.split('|', limit = 8)
                if (parts.size < 8) continue
                val perms = parts[0].trim()
                val octal = parts[1].trim().toIntOrNull(8) ?: octalOf(perms)
                val type = parts[6].trim()
                val nameField = parts[7].trim()
                val arrow = nameField.indexOf(" -> ")
                val rawName = (if (arrow >= 0) nameField.substring(0, arrow) else nameField).trim().trim('\'')
                val name = rawName.trimEnd('/').substringAfterLast('/').ifEmpty { rawName }
                if (name.isEmpty() || name == "." || name == "..") continue
                out.add(
                    FsEntry(
                        name = name,
                        path = joinPath(dir, name),
                        isDir = type.equals("directory", true),
                        isLink = arrow >= 0 || type.contains("link", true),
                        linkTarget = if (arrow >= 0) nameField.substring(arrow + 4).trim().trim('\'') else null,
                        size = parts[4].trim().toLongOrNull() ?: 0L,
                        perms = perms,
                        octal = octal,
                        owner = parts[2].trim(),
                        group = parts[3].trim(),
                        date = parts[5].trim(),
                    )
                )
            }
        }
        return out
    }

    private fun sorted(entries: List<FsEntry>, sort: FmSort, reverse: Boolean): List<FsEntry> {
        val base = when (sort) {
            FmSort.NAME -> entries.sortedBy { it.name.lowercase() }
            FmSort.SIZE -> entries.sortedByDescending { it.size }
            FmSort.TIME -> entries.sortedByDescending { it.date.toLongOrNull() ?: 0L }
        }
        return if (reverse) base.reversed() else base
    }

    suspend fun list(dir: String, sort: FmSort, reverse: Boolean, showHidden: Boolean): FmListing {
        val flags = buildString {
            append("-l")
            if (showHidden) append("A")
            when (sort) {
                FmSort.NAME -> Unit
                FmSort.SIZE -> append("S")
                FmSort.TIME -> append("t")
            }
            if (reverse) append("r")
        }
        val script = "cd -- " + quote(dir) + " 2>/dev/null || exit 3\nLC_ALL=C ls " + flags + " 2>&1\n"
        val (code, lines) = exec(script)
        if (code != 0) {
            return FmListing(code, emptyList(), lines.take(4).joinToString("\n").trim().ifEmpty { null })
        }
        val rows = ArrayList<FsEntry>(lines.size)
        for (line in lines) {
            if (line.isBlank() || line.startsWith("total ")) continue
            parseLine(dir, line)?.let { rows.add(it) }
        }
        if (rows.isNotEmpty()) return FmListing(0, rows, null)

        // ls -l 一行都没解析出来：可能是空目录，也可能是输出形态和我们对不上。
        // 用名字列表判断到底空不空；有东西就 stat 兜底列出来，绝不显示成空目录。
        val nameFlags = if (showHidden) "-1A" else "-1"
        val (nameCode, nameLines) = exec(
            "cd -- " + quote(dir) + " 2>/dev/null || exit 3\nLC_ALL=C ls " + nameFlags + " 2>&1\n"
        )
        if (nameCode != 0) {
            return FmListing(nameCode, emptyList(), nameLines.take(4).joinToString("\n").trim().ifEmpty { null })
        }
        val names = nameLines.map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        if (names.isEmpty()) return FmListing(0, emptyList(), null)

        val viaStat = sorted(statEntries(dir, names), sort, reverse)
        val raw = lines.firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
        val note = if (viaStat.isEmpty()) {
            "ls -l 输出无法解析（${lines.size} 行），stat 兜底也失败；原始输出首行：$raw"
        } else {
            "ls -l 输出无法解析，已用 stat 兜底列出 ${viaStat.size} 项（首行：$raw）"
        }
        return FmListing(0, viaStat, note)
    }

    suspend fun testDir(path: String): Boolean =
        exec("[ -d " + quote(path) + " ] && echo yes || echo no").second.any { it.trim() == "yes" }

    suspend fun statInfo(path: String): List<String> =
        exec("stat -c '%A|%a|%U|%G|%s|%y' -- " + quote(path) + " 2>&1").second

    suspend fun sha256(path: String): String {
        val (code, lines) = exec("sha256sum -- " + quote(path) + " 2>&1")
        if (code != 0) return firstError(lines)
        return lines.firstOrNull()?.trim()?.substringBefore(' ').orEmpty()
    }

    suspend fun mkdir(path: String): String {
        val (code, lines) = exec("mkdir -p -- " + quote(path) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "mkdir exit $code" }
    }

    suspend fun touch(path: String): String {
        val (code, lines) = exec(": > " + quote(path) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "create exit $code" }
    }

    suspend fun rename(from: String, to: String): String {
        val (code, lines) = exec("mv -f -- " + quote(from) + " " + quote(to) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "mv exit $code" }
    }

    private fun pathList(verb: String, paths: List<String>, extra: String?): String {
        val sb = StringBuilder(verb)
        for (p in paths) sb.append(' ').append(quote(p))
        if (extra != null) sb.append(' ').append(quote(extra))
        sb.append(" 2>&1")
        return sb.toString()
    }

    suspend fun delete(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val (code, lines) = exec(pathList("rm -rf --", paths, null))
        return if (code == 0) "" else firstError(lines).ifEmpty { "rm exit $code" }
    }

    suspend fun copy(paths: List<String>, destDir: String): String {
        if (paths.isEmpty()) return ""
        val (code, lines) = exec(pathList("cp -a --", paths, destDir))
        return if (code == 0) "" else firstError(lines).ifEmpty { "cp exit $code" }
    }

    suspend fun move(paths: List<String>, destDir: String): String {
        if (paths.isEmpty()) return ""
        val (code, lines) = exec(pathList("mv -f --", paths, destDir))
        return if (code == 0) "" else firstError(lines).ifEmpty { "mv exit $code" }
    }

    suspend fun chmod(path: String, octal: String, recursive: Boolean): String {
        val flag = if (recursive) "-R " else ""
        val (code, lines) = exec("chmod $flag" + octal.trim() + " -- " + quote(path) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "chmod exit $code" }
    }

    suspend fun chown(path: String, spec: String, recursive: Boolean): String {
        if (spec.isBlank()) return ""
        val flag = if (recursive) "-R " else ""
        val (code, lines) = exec("chown $flag" + spec.trim() + " -- " + quote(path) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "chown exit $code" }
    }

    suspend fun readText(path: String): Pair<String?, String> {
        val (code, lines) = exec("base64 -- " + quote(path) + " 2>&1")
        if (code != 0) return null to firstError(lines).ifEmpty { "read exit $code" }
        return runCatching {
            String(Base64.decode(lines.joinToString(""), Base64.DEFAULT), Charsets.UTF_8) to ""
        }.getOrElse { null to (it.message ?: "decode failed") }
    }

    suspend fun writeText(path: String, text: String): String {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val (code, lines) = exec("printf '%s' " + quote(encoded) + " | base64 -d > " + quote(path) + " 2>&1")
        return if (code == 0) "" else firstError(lines).ifEmpty { "write exit $code" }
    }

    suspend fun find(dir: String, query: String): List<FsEntry> {
        val script = "find " + quote(dir) + " -maxdepth 6 -iname " + quote("*$query*") + " 2>/dev/null | head -n 300\n"
        val (_, lines) = exec(script)
        val out = ArrayList<FsEntry>()
        for (line in lines) {
            val p = line.trim()
            if (p.isEmpty() || p == dir) continue
            val name = p.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) continue
            out.add(
                FsEntry(
                    name = name,
                    path = p,
                    isDir = testDir(p),
                    isLink = false,
                    linkTarget = null,
                    size = 0L,
                    perms = "",
                    octal = 0,
                    owner = "",
                    group = "",
                    date = parentOf(p),
                )
            )
        }
        return out
    }

    suspend fun installApk(path: String): String {
        val (code, lines) = exec("pm install -r -d --user 0 " + quote(path) + " 2>&1")
        if (code == 0 && lines.none { it.contains("Failure", true) }) return ""
        return firstError(lines).ifEmpty { "pm install exit $code" }
    }

    suspend fun extractZip(path: String, destDir: String): String {
        val script = "mkdir -p -- " + quote(destDir) + " 2>&1\n" +
            "if command -v unzip >/dev/null 2>&1; then\n" +
            "  unzip -o -- " + quote(path) + " -d " + quote(destDir) + " 2>&1\n" +
            "elif command -v busybox >/dev/null 2>&1; then\n" +
            "  busybox unzip -o -- " + quote(path) + " -d " + quote(destDir) + " 2>&1\n" +
            "else\n  echo 'no unzip on this device'\n  exit 9\nfi\n"
        val (code, lines) = exec(script)
        return if (code == 0) "" else firstError(lines).ifEmpty { "unzip exit $code" }
    }

    suspend fun execute(path: String): String {
        val dir = parentOf(path)
        val name = path.trimEnd('/').substringAfterLast('/')
        // 任意脚本：不重放（retry=false），避免副作用执行两遍
        val (code, lines) = exec(
            "cd -- " + quote(dir) + " 2>/dev/null || exit 3\nsh -c " + quote("./" + name) + " 2>&1 | tail -n 400\n",
            retry = false,
        )
        return if (code == 0) "" else firstError(lines).ifEmpty { "exec exit $code" }
    }
}

private val FM_QUICK_JUMPS = listOf(
    "/data/adb", "/data/adb/modules", "/data/adb/tricky_store", "/sdcard", "/sdcard/Download", "/data/local/tmp",
)

private fun fmHumanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f G".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f M".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f K".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun fmExt(name: String): String = name.substringAfterLast('.', "").lowercase()

private fun fmIsText(name: String): Boolean = fmExt(name) in setOf(
    "txt", "md", "json", "xml", "conf", "cfg", "ini", "prop", "sh", "rc", "log", "csv", "yaml", "yml",
    "kt", "java", "rs", "c", "h", "cpp", "py", "js", "ts", "html", "css", "toml", "bak", "list", "te",
) || !name.contains('.')

private fun fmIsImage(name: String): Boolean =
    fmExt(name) in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")

private fun fmIsMedia(name: String): Boolean =
    fmExt(name) in setOf("mp3", "mp4", "m4a", "flac", "wav", "ogg", "opus", "aac", "mkv", "avi", "mov", "webm")

private fun fmIsArchive(name: String): Boolean =
    fmExt(name) in setOf("zip", "tar", "gz", "xz", "bz2", "7z", "jar")

private fun fmMimeOf(name: String): String = when (fmExt(name)) {
    "apk" -> "application/vnd.android.package-archive"
    "zip" -> "application/zip"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "txt", "log", "md", "json", "xml", "sh", "conf", "prop" -> "text/plain"
    else -> "*/*"
}

@Composable
private fun FmIconFor(entry: FsEntry) {
    val image: ImageVector
    val tint: Color
    when {
        entry.isDir -> {
            image = Icons.Rounded.Folder
            tint = Color(0xFFFFB300)
        }

        entry.isLink -> {
            image = Icons.Rounded.Code
            tint = Color(0xFF90A4AE)
        }

        entry.name.endsWith(".apk", true) -> {
            image = Icons.Rounded.GetApp
            tint = Color(0xFF66BB6A)
        }

        fmIsImage(entry.name) -> {
            image = Icons.Rounded.Wallpaper
            tint = Color(0xFFAB47BC)
        }

        fmIsMedia(entry.name) -> {
            image = Icons.Rounded.PlayArrow
            tint = Color(0xFF42A5F5)
        }

        fmIsArchive(entry.name) -> {
            image = Icons.AutoMirrored.Rounded.Article
            tint = Color(0xFF26A69A)
        }

        fmIsText(entry.name) -> {
            image = Icons.Rounded.Description
            tint = Color(0xFF90A4AE)
        }

        else -> {
            image = Icons.Rounded.InsertDriveFile
            tint = colorScheme.onSurfaceVariantSummary
        }
    }
    Icon(imageVector = image, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
private fun FmChip(label: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (selected) colorScheme.primary.copy(alpha = 0.18f) else colorScheme.surfaceContainerHigh,
                RoundedCornerShape(50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) colorScheme.primary else colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun FmDialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                ),
            colors = CardDefaults.defaultColors(color = colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun FmTitle(text: String) {
    Text(text = text, fontSize = 15.sp, color = colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun FmButtons(cancelLabel: String, confirmLabel: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        FmChip(label = cancelLabel, onClick = onCancel)
        FmChip(label = confirmLabel, selected = true, onClick = onConfirm)
    }
}

@Composable
private fun FmMenuItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (danger) Color(0xFFEF5350) else colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FmRow(entry: FsEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val searchHit = entry.date.startsWith("/")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        FmIconFor(entry)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 13.sp,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = when {
                searchHit -> entry.date
                entry.isDir -> stringResource(R.string.terminal_fm_type_dir)
                else -> fmHumanSize(entry.size) + if (entry.owner.isNotEmpty()) "  " + entry.owner else ""
            }
            Text(
                text = detail,
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.linkTarget != null) {
                Text(
                    text = "-> " + entry.linkTarget,
                    fontSize = 10.sp,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!searchHit && entry.perms.isNotEmpty()) {
            Text(
                text = entry.perms,
                fontSize = 9.sp,
                color = colorScheme.onSurfaceVariantSummary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (!searchHit && entry.date.isNotEmpty()) {
            Text(
                text = entry.date,
                fontSize = 9.sp,
                color = colorScheme.onSurfaceVariantSummary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FmTripleRow(
    label: String,
    a: Boolean,
    b: Boolean,
    c: Boolean,
    onA: (Boolean) -> Unit,
    onB: (Boolean) -> Unit,
    onC: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(text = label, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(64.dp))
        FmChip(label = "r", selected = a) { onA(!a) }
        Spacer(Modifier.width(6.dp))
        FmChip(label = "w", selected = b) { onB(!b) }
        Spacer(Modifier.width(6.dp))
        FmChip(label = "x", selected = c) { onC(!c) }
    }
}

@Composable
private fun FmPermissionsDialog(entry: FsEntry, onDismiss: () -> Unit, onApply: (String, String, Boolean) -> Unit) {
    var rOwner by remember { mutableStateOf(entry.perms.getOrNull(0) == 'r') }
    var wOwner by remember { mutableStateOf(entry.perms.getOrNull(1) == 'w') }
    var xOwner by remember { mutableStateOf(entry.perms.getOrNull(2) == 'x' || entry.perms.getOrNull(2) == 's') }
    var rGroup by remember { mutableStateOf(entry.perms.getOrNull(3) == 'r') }
    var wGroup by remember { mutableStateOf(entry.perms.getOrNull(4) == 'w') }
    var xGroup by remember { mutableStateOf(entry.perms.getOrNull(5) == 'x' || entry.perms.getOrNull(5) == 's') }
    var rOther by remember { mutableStateOf(entry.perms.getOrNull(6) == 'r') }
    var wOther by remember { mutableStateOf(entry.perms.getOrNull(7) == 'w') }
    var xOther by remember { mutableStateOf(entry.perms.getOrNull(8) == 'x' || entry.perms.getOrNull(8) == 't') }
    var octal by remember { mutableStateOf(String.format("%o", entry.octal and 0xFFF)) }
    var ownerSpec by remember { mutableStateOf(entry.owner.ifEmpty { "0" } + ":" + entry.group.ifEmpty { "0" }) }
    var recursive by remember { mutableStateOf(false) }

    fun bitsOf(r: Boolean, w: Boolean, x: Boolean): Int =
        (if (r) 4 else 0) or (if (w) 2 else 0) or (if (x) 1 else 0)

    fun syncOctal() {
        val value = (bitsOf(rOwner, wOwner, xOwner) shl 6) or
            (bitsOf(rGroup, wGroup, xGroup) shl 3) or bitsOf(rOther, wOther, xOther)
        octal = String.format("%o", value)
    }

    FmDialogCard(onDismiss = onDismiss) {
        FmTitle(stringResource(R.string.terminal_fm_perms))
        Text(text = entry.path, fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        FmTripleRow(
            stringResource(R.string.terminal_perm_owner), rOwner, wOwner, xOwner,
            { rOwner = it; syncOctal() }, { wOwner = it; syncOctal() }, { xOwner = it; syncOctal() },
        )
        FmTripleRow(
            stringResource(R.string.terminal_perm_group), rGroup, wGroup, xGroup,
            { rGroup = it; syncOctal() }, { wGroup = it; syncOctal() }, { xGroup = it; syncOctal() },
        )
        FmTripleRow(
            stringResource(R.string.terminal_perm_others), rOther, wOther, xOther,
            { rOther = it; syncOctal() }, { wOther = it; syncOctal() }, { xOther = it; syncOctal() },
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = octal,
            onValueChange = { octal = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.terminal_perm_octal),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = ownerSpec,
            onValueChange = { ownerSpec = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.terminal_perm_owner_field),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FmChip(label = stringResource(R.string.terminal_fm_recursive), selected = recursive) { recursive = !recursive }
            Spacer(Modifier.weight(1f))
            FmChip(label = stringResource(R.string.terminal_fm_apply), selected = true) {
                onApply(octal, ownerSpec, recursive)
            }
        }
    }
}

@Composable
private fun FmPropRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        if (label.isNotEmpty()) {
            Text(text = label, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(64.dp))
        }
        Text(
            text = value,
            fontSize = 11.sp,
            color = colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FmPropertiesDialog(entry: FsEntry, extra: String?, onDismiss: () -> Unit, onSha256: () -> Unit) {
    var statLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.path) {
        statLine = FsShell.statInfo(entry.path).firstOrNull { it.isNotBlank() }
    }
    FmDialogCard(onDismiss = onDismiss) {
        FmTitle(stringResource(R.string.terminal_fm_properties))
        FmPropRow(stringResource(R.string.terminal_fm_path), entry.path)
        FmPropRow(
            stringResource(R.string.terminal_fm_type),
            if (entry.isDir) stringResource(R.string.terminal_fm_type_dir) else stringResource(R.string.terminal_fm_type_file),
        )
        FmPropRow(stringResource(R.string.terminal_fm_size), fmHumanSize(entry.size))
        FmPropRow(stringResource(R.string.terminal_fm_date), entry.date)
        FmPropRow(stringResource(R.string.terminal_fm_mode), entry.perms + "  " + String.format("%o", entry.octal))
        FmPropRow(stringResource(R.string.terminal_fm_owner), entry.owner + ":" + entry.group)
        statLine?.let { FmPropRow("stat", it) }
        extra?.let { FmPropRow("", it) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!entry.isDir) FmChip(label = stringResource(R.string.terminal_fm_sha256)) { onSha256() }
            Spacer(Modifier.weight(1f))
            FmChip(label = stringResource(R.string.terminal_fm_ok)) { onDismiss() }
        }
    }
}

@Composable
private fun FmViewerDialog(
    entry: FsEntry,
    text: String,
    editable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(text) }
    FmDialogCard(onDismiss = onDismiss) {
        FmTitle(
            (if (editable) stringResource(R.string.terminal_fm_edit) else stringResource(R.string.terminal_fm_open)) +
                " - " + entry.name
        )
        TextField(
            value = value,
            onValueChange = { if (editable && it.length <= FM_TEXT_LIMIT) value = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            label = stringResource(R.string.terminal_fm_text_hint),
            maxLines = 200,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            FmChip(label = stringResource(R.string.terminal_cancel)) { onDismiss() }
            if (editable) FmChip(label = stringResource(R.string.terminal_fm_save), selected = true) { onSave(value) }
        }
    }
}

@Composable
private fun FmCenterText(text: String, danger: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (danger) Color(0xFFEF5350) else colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun FileManagerPanel(startDir: String, onOpenTerminal: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences(FM_PREFS, Context.MODE_PRIVATE) }

    fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    var path by rememberSaveable { mutableStateOf(prefs.getString(FM_KEY_LAST_DIR, startDir) ?: startDir) }
    var sort by rememberSaveable {
        mutableStateOf(
            runCatching { FmSort.valueOf(prefs.getString(FM_KEY_SORT, FmSort.NAME.name) ?: FmSort.NAME.name) }
                .getOrDefault(FmSort.NAME)
        )
    }
    var reverse by rememberSaveable { mutableStateOf(prefs.getBoolean(FM_KEY_REVERSE, false)) }
    var showHidden by rememberSaveable { mutableStateOf(prefs.getBoolean(FM_KEY_HIDDEN, false)) }
    var bookmarks by remember {
        mutableStateOf(prefs.getString(FM_KEY_BOOKMARKS, "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
    }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 兜底路径的提示（例如 ls -l 解析不了、已改用 stat 列出）：一直挂在列表上方，
    // 不用弹窗，也方便用户截图反馈。
    var note by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var clip by remember { mutableStateOf<FmClip?>(null) }
    var promptKind by remember { mutableStateOf<String?>(null) }
    var promptText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<String>?>(null) }
    var permTarget by remember { mutableStateOf<FsEntry?>(null) }
    var propsTarget by remember { mutableStateOf<FsEntry?>(null) }
    var propsExtra by remember { mutableStateOf<String?>(null) }
    var viewerTarget by remember { mutableStateOf<FsEntry?>(null) }
    var viewerText by remember { mutableStateOf("") }
    var viewerEditable by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<FsEntry?>(null) }
    var searchResults by remember { mutableStateOf<List<FsEntry>?>(null) }

    fun persist() {
        prefs.edit()
            .putString(FM_KEY_LAST_DIR, path)
            .putString(FM_KEY_SORT, sort.name)
            .putBoolean(FM_KEY_REVERSE, reverse)
            .putBoolean(FM_KEY_HIDDEN, showHidden)
            .apply()
    }

    fun afterWrite(message: String = "") {
        selected = emptySet()
        if (message.isNotEmpty()) info = message
        refreshToken++
    }

    LaunchedEffect(path, refreshToken, sort, reverse, showHidden) {
        entries = null
        error = null
        note = null
        persist()
        val listing = FsShell.list(path, sort, reverse, showHidden)
        entries = listing.entries
        note = listing.note
        if (listing.code != 0) {
            // 失败时把 exec 收集到的两路失败原因原样显示，别再让用户猜。
            error = listing.note ?: text(R.string.terminal_fm_op_failed, "exit ${listing.code}")
        }
    }

    fun shareWith(entry: FsEntry, view: Boolean) {
        scope.launch {
            val cacheDir = java.io.File(context.cacheDir, "fm")
            cacheDir.mkdirs()
            val target = java.io.File(cacheDir, entry.name.replace('/', '_'))
            val err = FsShell.copy(listOf(entry.path), cacheDir.absolutePath)
            if (err.isNotEmpty()) {
                info = text(R.string.terminal_fm_op_failed, err)
                return@launch
            }
            runCatching {
                val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", target)
                val intent = if (view) {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, fmMimeOf(entry.name))
                } else {
                    Intent(Intent.ACTION_SEND).setType(fmMimeOf(entry.name)).putExtra(Intent.EXTRA_STREAM, uri)
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure { info = text(R.string.terminal_fm_op_failed, it.message ?: "no activity") }
        }
    }

    fun readInto(entry: FsEntry, editable: Boolean) {
        scope.launch {
            val (content, err) = FsShell.readText(entry.path)
            if (content == null) {
                info = text(R.string.terminal_fm_op_failed, err)
            } else {
                viewerTarget = entry
                viewerText = content
                viewerEditable = editable
            }
        }
    }

    fun openEntry(entry: FsEntry) {
        when {
            entry.isDir -> path = entry.path
            entry.isLink -> scope.launch { if (FsShell.testDir(entry.path)) path = entry.path else readInto(entry, false) }
            entry.name.endsWith(".apk", true) -> scope.launch {
                val err = FsShell.installApk(entry.path)
                afterWrite(
                    if (err.isEmpty()) text(R.string.terminal_fm_installed)
                    else text(R.string.terminal_fm_op_failed, err)
                )
            }

            fmIsText(entry.name) -> readInto(entry, false)
            else -> shareWith(entry, view = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { path = FsShell.parentOf(path) }) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.terminal_parent_dir),
                        tint = colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FmChip(label = "/", selected = path == "/") { path = "/" }
                    var acc = ""
                    for (segment in path.trimEnd('/').split('/').filter { it.isNotEmpty() }) {
                        acc += "/$segment"
                        val target = acc
                        Text(text = " \u203a ", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                        FmChip(label = segment, selected = target == path) { path = target }
                    }
                }
                IconButton(onClick = { refreshToken++ }) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, tint = colorScheme.onSurface)
                }
                IconButton(onClick = { promptKind = "path"; promptText = path }) {
                    Icon(imageVector = Icons.Rounded.Settings, contentDescription = null, tint = colorScheme.onSurface)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FmChip(label = stringResource(R.string.terminal_fm_new_folder), icon = Icons.Rounded.Add) {
                    promptKind = "newdir"
                    promptText = ""
                }
                FmChip(label = stringResource(R.string.terminal_fm_new_file)) {
                    promptKind = "newfile"
                    promptText = ""
                }
                val currentClip = clip
                if (currentClip != null) {
                    FmChip(
                        label = stringResource(R.string.terminal_fm_paste) + " (" + currentClip.paths.size + ")",
                        icon = Icons.Rounded.ContentPaste,
                        selected = true,
                    ) {
                        scope.launch {
                            val err = if (currentClip.cut) {
                                FsShell.move(currentClip.paths, path)
                            } else {
                                FsShell.copy(currentClip.paths, path)
                            }
                            if (err.isEmpty()) {
                                if (currentClip.cut) clip = null
                                afterWrite(text(R.string.terminal_fm_paste_done))
                            } else {
                                info = text(R.string.terminal_fm_op_failed, err)
                            }
                        }
                    }
                }
                FmChip(label = stringResource(R.string.terminal_fm_search)) {
                    promptKind = "search"
                    promptText = ""
                }
                FmChip(
                    label = stringResource(R.string.terminal_fm_bookmark),
                    icon = Icons.Rounded.Pin,
                    selected = bookmarks.contains(path),
                ) {
                    bookmarks = if (bookmarks.contains(path)) bookmarks - path else bookmarks + path
                    prefs.edit().putString(FM_KEY_BOOKMARKS, bookmarks.joinToString("\n")).apply()
                }
                FmChip(
                    label = stringResource(
                        when (sort) {
                            FmSort.NAME -> R.string.terminal_fm_sort_name
                            FmSort.SIZE -> R.string.terminal_fm_sort_size
                            FmSort.TIME -> R.string.terminal_fm_sort_time
                        }
                    ) + if (reverse) " v" else " ^",
                ) {
                    sort = when (sort) {
                        FmSort.NAME -> FmSort.SIZE
                        FmSort.SIZE -> FmSort.TIME
                        FmSort.TIME -> FmSort.NAME
                    }
                    reverse = false
                }
                FmChip(label = stringResource(if (reverse) R.string.terminal_fm_reverse else R.string.terminal_fm_forward)) {
                    reverse = !reverse
                }
                FmChip(
                    label = stringResource(
                        if (showHidden) R.string.terminal_fm_hidden_on else R.string.terminal_fm_hidden_off
                    ),
                    selected = showHidden,
                ) { showHidden = !showHidden }
                FmChip(label = stringResource(R.string.terminal_fm_open_terminal), icon = Icons.Rounded.Code) {
                    onOpenTerminal(path)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FmChip(label = stringResource(R.string.terminal_fm_home), icon = Icons.Rounded.Cottage) { path = "/" }
                for (jump in FM_QUICK_JUMPS) {
                    FmChip(label = jump, selected = path == jump) { path = jump }
                }
                for (mark in bookmarks) {
                    FmChip(label = mark, selected = path == mark) { path = mark }
                    FmChip(label = "x") {
                        bookmarks = bookmarks - mark
                        prefs.edit().putString(FM_KEY_BOOKMARKS, bookmarks.joinToString("\n")).apply()
                    }
                }
            }

            val noteText = note
            if (noteText != null) {
                Text(
                    text = noteText,
                    fontSize = 10.sp,
                    color = Color(0xFFEF5350),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 当前目录与条目数常显：这样"空目录"和"列不出来"一眼能区分，
            // 用户截图反馈时也自带现场信息。
            val shownCount = (searchResults ?: entries)?.size
            if (shownCount != null) {
                Text(
                    text = stringResource(R.string.terminal_fm_count, shownCount) + "  " + path,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .xGlassBody(backdrop = null, shape = Xc.shapes.md),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                val shown = searchResults ?: entries
                when {
                    shown == null -> FmCenterText(stringResource(R.string.terminal_fm_loading))
                    error != null -> FmCenterText(error ?: "", danger = true)
                    shown.isEmpty() -> FmCenterText(
                        (if (searchResults != null) stringResource(R.string.terminal_fm_search_empty)
                        else stringResource(R.string.terminal_files_empty)) + "\n" + path
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        // key 里带下标：万一 ls 输出里有重名（同一个名字出现两次），
                        // 纯 path 做 key 会让 Compose 直接抛异常、整页空白。
                        itemsIndexed(shown, key = { index, entry -> entry.path + "#" + index }) { _, entry ->
                            FmRow(
                                entry = entry,
                                selected = selected.contains(entry.path),
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (selected.contains(entry.path)) {
                                            selected - entry.path
                                        } else {
                                            selected + entry.path
                                        }
                                    } else {
                                        openEntry(entry)
                                    }
                                },
                                onLongClick = {
                                    if (selected.isEmpty()) selected = selected + entry.path else menuFor = entry
                                },
                            )
                        }
                    }
                }
            }

            if (searchResults != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.terminal_fm_search_results, searchResults?.size ?: 0),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    FmChip(label = stringResource(R.string.terminal_fm_close)) { searchResults = null }
                }
            }

            val chosen = selected.toList()
            if (chosen.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.terminal_fm_selected, chosen.size),
                        fontSize = 12.sp,
                        color = colorScheme.primary,
                    )
                    val single = (entries ?: emptyList()).firstOrNull { it.path == chosen.first() }
                    FmChip(label = stringResource(R.string.terminal_fm_select_all)) {
                        selected = (entries ?: emptyList()).map { it.path }.toSet()
                    }
                    FmChip(label = stringResource(R.string.terminal_fm_copy), icon = Icons.Rounded.ContentPaste) {
                        clip = FmClip(cut = false, paths = chosen)
                        selected = emptySet()
                    }
                    FmChip(label = stringResource(R.string.terminal_fm_cut)) {
                        clip = FmClip(cut = true, paths = chosen)
                        selected = emptySet()
                    }
                    FmChip(label = stringResource(R.string.terminal_fm_delete), icon = Icons.Rounded.Delete) {
                        deleteTargets = chosen
                    }
                    if (chosen.size == 1 && single != null) {
                        FmChip(label = stringResource(R.string.terminal_fm_rename), icon = Icons.Rounded.Create) {
                            renameTarget = single
                            promptKind = "rename"
                            promptText = single.name
                        }
                        FmChip(label = stringResource(R.string.terminal_fm_perms), icon = Icons.Rounded.Security) {
                            permTarget = single
                        }
                        FmChip(label = stringResource(R.string.terminal_fm_properties), icon = Icons.Rounded.Info) {
                            propsTarget = single
                            propsExtra = null
                        }
                        FmChip(label = stringResource(R.string.terminal_fm_share), icon = Icons.Rounded.Share) {
                            shareWith(single, view = false)
                        }
                    }
                    FmChip(label = stringResource(R.string.terminal_fm_copy_path)) {
                        clipboard.setText(AnnotatedString(chosen.joinToString("\n")))
                        selected = emptySet()
                    }
                    FmChip(label = stringResource(R.string.terminal_fm_clear_selection), icon = Icons.Rounded.Close) {
                        selected = emptySet()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        val kind = promptKind
        if (kind != null) {
            val titleRes = when (kind) {
                "rename" -> R.string.terminal_fm_rename
                "newdir" -> R.string.terminal_fm_new_folder
                "newfile" -> R.string.terminal_fm_new_file
                "path" -> R.string.terminal_fm_jump
                else -> R.string.terminal_fm_search
            }
            FmDialogCard(onDismiss = { promptKind = null }) {
                FmTitle(stringResource(titleRes))
                TextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(
                        if (kind == "search") R.string.terminal_fm_search_hint else R.string.terminal_fm_name_hint
                    ),
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                FmButtons(
                    cancelLabel = stringResource(R.string.terminal_cancel),
                    confirmLabel = stringResource(R.string.terminal_fm_ok),
                    onCancel = { promptKind = null },
                    onConfirm = {
                        val value = promptText.trim()
                        promptKind = null
                        when (kind) {
                            "rename" -> {
                                val target = renameTarget
                                if (target != null && value.isNotEmpty()) {
                                    scope.launch {
                                        val err = FsShell.rename(
                                            target.path,
                                            FsShell.joinPath(FsShell.parentOf(target.path), value),
                                        )
                                        if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                                    }
                                }
                            }

                            "newdir" -> if (value.isNotEmpty()) scope.launch {
                                val err = FsShell.mkdir(FsShell.joinPath(path, value))
                                if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                            }

                            "newfile" -> if (value.isNotEmpty()) scope.launch {
                                val err = FsShell.touch(FsShell.joinPath(path, value))
                                if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                            }

                            "path" -> if (value.startsWith("/")) path = value

                            "search" -> if (value.isNotEmpty()) scope.launch {
                                searchResults = FsShell.find(path, value)
                            }
                        }
                    },
                )
            }
        }

        deleteTargets?.let { targets ->
            FmDialogCard(onDismiss = { deleteTargets = null }) {
                FmTitle(stringResource(R.string.terminal_fm_delete))
                Text(
                    text = stringResource(R.string.terminal_fm_delete_confirm, targets.size),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                FmButtons(
                    cancelLabel = stringResource(R.string.terminal_cancel),
                    confirmLabel = stringResource(R.string.terminal_fm_delete),
                    onCancel = { deleteTargets = null },
                    onConfirm = {
                        deleteTargets = null
                        scope.launch {
                            val err = FsShell.delete(targets)
                            if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                        }
                    },
                )
            }
        }

        permTarget?.let { entry ->
            FmPermissionsDialog(
                entry = entry,
                onDismiss = { permTarget = null },
                onApply = { octal, ownerSpec, recursive ->
                    permTarget = null
                    scope.launch {
                        var err = FsShell.chmod(entry.path, octal, recursive)
                        if (err.isEmpty() && ownerSpec.isNotBlank()) {
                            err = FsShell.chown(entry.path, ownerSpec, recursive)
                        }
                        if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                    }
                },
            )
        }

        propsTarget?.let { entry ->
            FmPropertiesDialog(
                entry = entry,
                extra = propsExtra,
                onDismiss = { propsTarget = null },
                onSha256 = {
                    scope.launch {
                        propsExtra = "SHA-256: " + FsShell.sha256(entry.path)
                    }
                },
            )
        }

        menuFor?.let { entry ->
            FmDialogCard(onDismiss = { menuFor = null }) {
                FmTitle(entry.name)
                FmMenuItem(stringResource(R.string.terminal_fm_open)) { menuFor = null; openEntry(entry) }
                if (fmIsText(entry.name)) {
                    FmMenuItem(stringResource(R.string.terminal_fm_edit)) { menuFor = null; readInto(entry, true) }
                }
                FmMenuItem(stringResource(R.string.terminal_fm_execute)) {
                    menuFor = null
                    scope.launch {
                        val err = FsShell.execute(entry.path)
                        if (err.isNotEmpty()) info = text(R.string.terminal_fm_op_failed, err)
                    }
                }
                if (entry.name.endsWith(".apk", true)) {
                    FmMenuItem(stringResource(R.string.terminal_fm_install)) {
                        menuFor = null
                        scope.launch {
                            val err = FsShell.installApk(entry.path)
                            afterWrite(
                                if (err.isEmpty()) text(R.string.terminal_fm_installed)
                                else text(R.string.terminal_fm_op_failed, err)
                            )
                        }
                    }
                }
                if (entry.name.endsWith(".zip", true)) {
                    FmMenuItem(stringResource(R.string.terminal_fm_extract)) {
                        val dest = FsShell.joinPath(FsShell.parentOf(entry.path), entry.name.substringBeforeLast('.'))
                        menuFor = null
                        scope.launch {
                            val err = FsShell.extractZip(entry.path, dest)
                            if (err.isEmpty()) afterWrite() else info = text(R.string.terminal_fm_op_failed, err)
                        }
                    }
                }
                FmMenuItem(stringResource(R.string.terminal_fm_open_with)) { menuFor = null; shareWith(entry, view = true) }
                FmMenuItem(stringResource(R.string.terminal_fm_share)) { menuFor = null; shareWith(entry, view = false) }
                FmMenuItem(stringResource(R.string.terminal_fm_perms)) { menuFor = null; permTarget = entry }
                FmMenuItem(stringResource(R.string.terminal_fm_properties)) {
                    menuFor = null
                    propsTarget = entry
                    propsExtra = null
                }
                FmMenuItem(stringResource(R.string.terminal_fm_copy_path)) {
                    menuFor = null
                    clipboard.setText(AnnotatedString(entry.path))
                }
                FmMenuItem(stringResource(R.string.terminal_fm_rename)) {
                    menuFor = null
                    renameTarget = entry
                    promptKind = "rename"
                    promptText = entry.name
                }
                FmMenuItem(stringResource(R.string.terminal_fm_delete), danger = true) {
                    menuFor = null
                    deleteTargets = listOf(entry.path)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    FmChip(label = stringResource(R.string.terminal_cancel)) { menuFor = null }
                }
            }
        }

        viewerTarget?.let { entry ->
            FmViewerDialog(
                entry = entry,
                text = viewerText,
                editable = viewerEditable,
                onDismiss = { viewerTarget = null; viewerText = "" },
                onSave = { value ->
                    scope.launch {
                        val err = FsShell.writeText(entry.path, value)
                        viewerTarget = null
                        viewerText = ""
                        if (err.isEmpty()) {
                            afterWrite(text(R.string.terminal_fm_saved))
                        } else {
                            info = text(R.string.terminal_fm_op_failed, err)
                        }
                    }
                },
            )
        }

        info?.let { message ->
            FmDialogCard(onDismiss = { info = null }) {
                FmTitle(stringResource(R.string.terminal_fm_notice))
                Text(text = message, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 8)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    FmChip(label = stringResource(R.string.terminal_fm_ok)) { info = null }
                }
            }
        }
    }
}

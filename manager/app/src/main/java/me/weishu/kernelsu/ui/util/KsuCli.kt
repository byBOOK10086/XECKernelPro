package me.weishu.kernelsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.core.tasks.BootKernelVersion
import me.weishu.kernelsu.core.tasks.ExtractImage
import me.weishu.kernelsu.core.tasks.ProbeResult
import me.weishu.kernelsu.core.utils.DataSourceChannel
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.mountstate.MagiskRoot
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

internal fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libxeccli.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

object KsuCli {
    // 不要在 object 初始化阶段创建 shell：任何 native/root 环境异常都会把
    // ExceptionInInitializerError 传播到 Application，表现为点击图标即闪退。
    // 延迟到真正使用时创建，启动阶段至少能进入诊断/安装页面。
    val SHELL: Shell by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createRootShell() }
    val GLOBAL_MNT_SHELL: Shell by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createRootShell(true)
    }
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    // 挂载态（<5.10，方案甲 = Magisk 路线）：内核里没有 KSU 驱动，`libxeccli.so debug su`
    // 这个进程永远拿不到 root（它只能靠内核驱动提权），再试一遍只会得到一个空壳。
    // 直接走设备上的 `su`，也就是 Magisk 的 su —— 管理器的 root 和其它 app 完全同一条
    // 通道：授权记在 /data/adb/magisk.db 的 policies 表里，Magisk 应用既能看到本管理器，
    // 也能随时收回它的 root；本管理器反过来也能改同一张表（超级用户页）。
    // 内核态一个字都不改：驱动在就还是先走 ksud。
    if (isMountState) {
        return try {
            if (globalMnt) builder.build("su", "-mm") else builder.build("su")
        } catch (e: Throwable) {
            Log.w(TAG, "magisk su failed: ", e)
            markUnprivileged(e)
            builder.build("sh")
        }
    }
    return try {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            if (globalMnt) {
                builder.build("su", "-mm")
            } else {
                builder.build("su")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            // 不许在这里抛：KsuCli 是 object 的静态初始化，抛出去会变成
            // ExceptionInInitializerError，整个管理器一起崩且不可恢复。
            // 退回普通 sh，并把失败状态记下来，让需要 root 的操作显式失败。
            markUnprivileged(e)
            builder.build("sh")
        }
    }
}

/** 非 root 兜底状态：为真时所有需要 root 的操作必须显式失败，不许静默当成功。 */
@Volatile
var isUnprivilegedFallback: Boolean = false
    private set

@Volatile
private var unprivilegedReason: String? = null

private fun markUnprivileged(error: Throwable) {
    isUnprivilegedFallback = true
    unprivilegedReason = error.message ?: error::class.java.simpleName
    Log.e(TAG, "root shell unavailable, falling back to unprivileged sh: $unprivilegedReason")
}

/** 最近一次建 root shell 失败的原因，供诊断页显示。 */
fun unprivilegedFallbackReason(): String? = unprivilegedReason

fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    if (isUnprivilegedFallback) {
        Log.e(TAG, "refuse to exec ksud without a privileged shell: $args")
        return false
    }
    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(globalMnt), "${getKsuDaemonPath()} $args")
    }
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install(): Boolean {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot --data-path ${ksuApp.applicationInfo.deviceProtectedDataDir}", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
    return result
}

fun listModules(): String {
    val out = runCatching {
        withNewRootShell {
            newJob().add("${getKsuDaemonPath()} module list").to(ArrayList<String>(), null).exec().out
        }
    }.getOrDefault(emptyList())
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    // 防格 KPM（antiwipe）已加载时，管理器自己的刷写必须放行；没加载时这条
    // control 会报错，忽略即可。结束立刻收回窗口，别给第三方脚本留时间。
    runCatching { execKsud("kpm control $ANTIWIPE_MODULE ${shellQuote("trust 600")}", true) }

    return try {
        withNewRootShell {
            newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
        }
    } finally {
        runCatching { execKsud("kpm control $ANTIWIPE_MODULE ${shellQuote("trust 0")}", true) }
    }
}

/** Runtime module identity, independent of the selected file name. */
const val ANTIWIPE_MODULE: String = "antiwipe"

fun loadAntiwipeKpm(
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult = loadBuiltinKpm("antiwipe.kpm", ANTIWIPE_MODULE, "", onStdout, onStderr)

/** Remove boot persistence first: even a failed runtime unload must not re-arm next boot. */
fun unloadAntiwipeKpm(): Boolean {
    val daemon = shellQuote(getKsuDaemonPath())
    val result = withNewRootShell {
        newJob().add("rm -f /data/adb/kpm/antiwipe.kpm /data/adb/kpm/antiwipe.kpm.args /data/adb/kpm/antiwipe.kpm.arm && " +
            "$daemon kpm control ${shellQuote(ANTIWIPE_MODULE)} ${shellQuote("off")}; " +
            "$daemon kpm unload ${shellQuote(ANTIWIPE_MODULE)}").to(ArrayList(), ArrayList()).exec()
    }
    return result.isSuccess && ANTIWIPE_MODULE !in kpmModuleNames()
}

/** Control takes ONE argument string, including the command's parameters. */
fun antiwipeTrust(seconds: Int): Boolean {
    require(seconds in 0..600)
    return execKsud("kpm control $ANTIWIPE_MODULE ${shellQuote("trust $seconds")}", true)
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    with(resolver.openInputStream(uri)) {
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }
        // 挂载态（<5.10，方案甲）：这台设备的 root / 模块挂载 / Zygisk 全是 Magisk 的，
        // 模块安装也必须走 Magisk 官方安装器 —— 模块的 customize.sh 用的是 Magisk 那套
        // ui_print / set_perm / replace 语义，交给内核态的 ksud 安装器会装出半成品。
        // 内核态一个字都不改：还是 ksud。
        val cmd = if (isMountState) {
            MagiskRoot.installModuleCommand(file.absolutePath)
        } else {
            "${getKsuDaemonPath()} module install ${file.absolutePath}"
        }
        val result = flashWithIO(cmd, onStdout, onStderr)
        Log.i("XECKernelPro", "install module $uri result: $result")

        file.delete()

        return FlashResult(result)
    }
}

private fun kpmQuery(command: String): String {
    val result = withNewRootShell {
        newJob().add("${shellQuote(getKsuDaemonPath())} kpm $command")
            .to(ArrayList(), ArrayList()).exec()
    }
    check(result.isSuccess) {
        result.err.joinToString("\n").ifBlank { "KPM $command failed (exit ${result.code})" }
    }
    return result.out.joinToString("\n").trim()
}

private fun kpmModuleNames(): Set<String> = kpmQuery("list").lineSequence()
    .map(String::trim).filter(String::isNotEmpty).toSet()

suspend fun kpmVersion(): String = withContext(Dispatchers.IO) {
    kpmQuery("version").also { check(it.isNotBlank()) { "KPM engine returned no version" } }
}

suspend fun kpmList(): String = withContext(Dispatchers.IO) { kpmQuery("list") }

/** All loads use a non-autoload staging directory; failed files never become boot inputs. */
private fun loadKpmStream(
    name: String,
    expectedName: String?,
    args: String,
    open: () -> InputStream,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val id = UUID.randomUUID().toString()
    val file = File(ksuApp.cacheDir, "kpm-$id.tmp")
    val staging = "/data/adb/kpm/.staging/$id.kpm"
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        .trim('.').ifBlank { "module-$id" }.let { if (it.endsWith(".kpm")) it else "$it.kpm" }
    var dest = "/data/adb/kpm/$safeName"
    val daemon = shellQuote(getKsuDaemonPath())
    fun root(command: String): Shell.Result = withNewRootShell {
        newJob().add(command).to(ArrayList(), ArrayList()).exec().also { result ->
            result.out.forEach(onStdout)
            result.err.forEach(onStderr)
        }
    }
    var loadedName: String? = null
    var persisted = false
    return try {
        open().use { input -> file.outputStream().use { input.copyTo(it) } }
        check(file.length() > 0) { "The selected KPM file is empty" }
        // Capability and list queries are read-only. Never load/arm/selftest while refreshing.
        check(kpmQuery("version").isNotBlank()) { "KPM engine returned no version" }
        val before = kpmModuleNames()
        check(expectedName == null || expectedName !in before) { "$expectedName is already loaded" }
        val staged = root("mkdir -p /data/adb/kpm/.staging && chmod 0700 /data/adb/kpm/.staging && " +
            "cp ${shellQuote(file.absolutePath)} ${shellQuote(staging)} && chmod 0600 ${shellQuote(staging)}")
        check(staged.isSuccess) { staged.err.joinToString("\n").ifBlank { "Cannot stage KPM file" } }
        val result = root("$daemon kpm load ${shellQuote(staging)} ${shellQuote(args)}")
        check(result.isSuccess) { result.err.joinToString("\n").ifBlank { "KPM load failed (exit ${result.code})" } }
        val added = kpmModuleNames() - before
        loadedName = expectedName?.takeIf { it in added } ?: added.singleOrNull()
        check(loadedName != null && (expectedName == null || loadedName == expectedName)) {
            "KPM load returned success but the loaded module could not be verified; boot persistence was not changed"
        }
        check(kpmQuery("info ${shellQuote(loadedName!!)}").isNotBlank()) { "KPM module info is empty" }
        check(loadedName!!.matches(Regex("[A-Za-z0-9_.-]+")) && loadedName != "." && loadedName != "..") {
            "KPM module identity cannot be persisted safely"
        }
        dest = "/data/adb/kpm/$loadedName.kpm"
        if (loadedName == ANTIWIPE_MODULE) {
            val armed = root("$daemon kpm control ${shellQuote(ANTIWIPE_MODULE)} ${shellQuote("arm")}")
            check(armed.isSuccess) { "Antiwipe loaded inert, but activation failed: ${armed.err.joinToString("\n")}" }
        }
        // Rename only after runtime verification. The .args sidecar is read as data by ksud.
        val saved = root("printf '%s' ${shellQuote(args)} > ${shellQuote("$staging.args")} && " +
            "chmod 0600 ${shellQuote("$staging.args")} && " +
            "mv -f ${shellQuote("$staging.args")} ${shellQuote("$dest.args")} && " +
            "mv -f ${shellQuote(staging)} ${shellQuote(dest)}")
        check(saved.isSuccess) { "KPM loaded, but saving boot persistence failed: ${saved.err.joinToString("\n")}" }
        if (loadedName == ANTIWIPE_MODULE) {
            check(root("touch ${shellQuote("$dest.arm")}").isSuccess) { "Cannot persist antiwipe activation" }
        }
        persisted = true
        FlashResult(0, "", false)
    } catch (e: Exception) {
        val message = e.message ?: "KPM installation failed"
        onStderr(message)
        FlashResult(-1, message, false)
    } finally {
        if (!persisted) loadedName?.let { name ->
            runCatching { root("$daemon kpm unload ${shellQuote(name)}") }
                .onFailure { onStderr("Failed to roll back runtime load: ${it.message}") }
        }
        runCatching { root("rm -f ${shellQuote(staging)} ${shellQuote("$staging.args")}") }
        file.delete()
    }
}

fun flashKpmModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    // file:// providers need not implement query(DISPLAY_NAME).
    val name = runCatching { uri.getFileName(ksuApp) }.getOrNull()
        ?: uri.lastPathSegment ?: "module.kpm"
    return loadKpmStream(name, null, "", {
        ksuApp.contentResolver.openInputStream(uri)
            ?: error("Cannot open the selected KPM file")
    }, onStdout, onStderr)
}

private fun loadBuiltinKpm(
    asset: String,
    module: String,
    args: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult = loadKpmStream(asset, module, args, {
    ksuApp.assets.open("kpm/$asset")
}, onStdout, onStderr)

fun flashBuiltinSusfsLite(
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult = loadBuiltinKpm(
    "susfs_lite.kpm", "susfs_lite",
    "allow_uids=${ksuApp.applicationInfo.uid};prefix=/data/adb,/debug_ramdisk",
    onStdout, onStderr,
)

private const val AK3_UPDATER_ENTRY = "META-INF/com/google/android/update-binary"

/**
 * 从 AnyKernel3 压缩包中取出 `update-binary`（AK3 的入口脚本）。
 */
private fun extractAnyKernelUpdater(zip: File, dest: File): Boolean {
    return try {
        ZipFile(zip).use { zipFile ->
            var entry = zipFile.getEntry(AK3_UPDATER_ENTRY)
            if (entry == null) {
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val candidate = entries.nextElement()
                    val name = candidate.name
                    if (name.equals("update-binary", ignoreCase = true) ||
                        name.endsWith("/update-binary", ignoreCase = true)
                    ) {
                        entry = candidate
                        break
                    }
                }
            }
            if (entry == null) return false
            zipFile.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dest.length() > 0L
    } catch (e: Throwable) {
        Log.e("XECKernelPro", "extract update-binary failed", e)
        false
    }
}

/**
 * 刷入第三方 AnyKernel3 包（例如第三方 GKI 内核）。
 *
 * 与 suk / KernelSU-Next 管理器一致：把 zip 拷进 app 私有目录，按 AK3 约定的
 * `update-binary <api> <outfd> <zipfile>` 调用它，并把 `AKHOME` / `POSTINSTALL`
 * 指向私有目录，让 AK3 在应用内完成刷写（脚本检测到 BOOTMODE 后会跳过
 * `setup_env` / `restore_env`，不会破坏当前挂载）。
 */
fun flashAnyKernel(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    val workDir = File(ksuApp.cacheDir, "anykernel3_${System.currentTimeMillis()}")
    if (!workDir.mkdirs() && !workDir.isDirectory) {
        return FlashResult(1, "Failed to create a temporary directory.", false)
    }

    val zipFile = File(workDir, "anykernel3.zip")
    return try {
        val input = resolver.openInputStream(uri)
            ?: return FlashResult(1, "Failed to open the selected file.", false)
        input.use { source ->
            zipFile.outputStream().use { output -> source.copyTo(output) }
        }
        if (zipFile.length() == 0L) {
            return FlashResult(1, "The selected file is empty.", false)
        }

        val updater = File(workDir, "update-binary")
        if (!extractAnyKernelUpdater(zipFile, updater)) {
            return FlashResult(1, "Not a valid AnyKernel3 package: update-binary not found.", false)
        }

        // AK3 脚本在解压阶段依赖 PATH 中的 unzip（Android 自带 shell 没有），
        // 所以先把内核自带的 busybox 软链成 unzip 并前置到 PATH。
        val workDirPath = workDir.absolutePath
        val zipPath = zipFile.absolutePath
        val cmd = "BB='/data/adb/ksu/bin/busybox'; " +
            "[ -f \"\$BB\" ] || BB='/data/adb/magisk/busybox'; " +
            "[ -f \"\$BB\" ] || BB='/system/bin/busybox'; " +
            "[ -f \"\$BB\" ] || BB='/system/xbin/busybox'; " +
            "[ -f \"\$BB\" ] || { echo 'busybox not found, cannot install AnyKernel3 package' >&2; exit 1; }; " +
            "W='$workDirPath'; " +
            "rm -rf \"\$W/tmp\"; mkdir -p \"\$W/tmp/bin\" \"\$W/bin\"; " +
            "ln -sf \"\$BB\" \"\$W/bin/unzip\"; " +
            "export POSTINSTALL=\"\$W\"; " +
            "export AKHOME=\"\$W/tmp\"; " +
            "export PATH=\"\$W/bin:/system/bin:/system/xbin:\$PATH\"; " +
            "\"\$BB\" ash \"\$W/update-binary\" 3 1 '$zipPath'"
        val result = flashWithIO(cmd, onStdout, onStderr)
        Log.i("XECKernelPro", "flash anykernel3 $uri result: $result")
        FlashResult(result)
    } finally {
        workDir.deleteRecursively()
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${shellQuote(getKsuDaemonPath())} module action ${shellQuote(moduleId)}")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("XECKernelPro", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private fun writeLkmFile(lkm: LkmSelection): File? {
    if (lkm !is LkmSelection.LkmUri) return null
    val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
    ksuApp.contentResolver.openInputStream(lkm.uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
    return file
}

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    if (ota) {
        cmd += " -u"
    }

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }

    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("XECKernelPro", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot && !install()) {
        val message = "Boot image flashed, but daemon installation failed. Install the daemon successfully before rebooting."
        onStderr(message)
        return FlashResult(-1, message, false)
    }
    return FlashResult(result, showReboot)
}

fun installBootKpm(
    bootUri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val bootFile = File.createTempFile("kpm-boot-", ".img", ksuApp.cacheDir)
    return try {
        val input = ksuApp.contentResolver.openInputStream(bootUri)
            ?: error("Cannot open the selected boot image")
        input.use { source -> bootFile.outputStream().use { source.copyTo(it) } }
        check(bootFile.length() > 0) { "The selected boot image is empty" }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(downloadsDir, "XECKernelPro_KPM_patched_${System.currentTimeMillis()}.img")
        val result = flashWithIO("${shellQuote(getKsuDaemonPath())} boot-patch-kpm " +
            "-b ${shellQuote(bootFile.absolutePath)} -o ${shellQuote(outFile.absolutePath)} --force", onStdout, onStderr)
        // This creates an image; it does not flash the running boot partition.
        FlashResult(result, false)
    } catch (e: Exception) {
        val message = e.message ?: "KPM boot patch failed"
        onStderr(message)
        FlashResult(-1, message, false)
    } finally {
        bootFile.delete()
    }
}

fun embedKpm(
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    // 一键嵌入：对当前 boot 分区直接打 KPatch-Next 补丁并写回，无需外部 boot 文件或 fastboot。
    // 但 kpimg 只服务 5.10 以下（非 GKI）内核：GKI 设备上已经有内置 KSU 与 LKM 两条
    // 受支持的安装路线，拿 kpimg 覆盖 boot 分区等于顶掉一个可用的安装。ksud 侧
    // （kpm_patch.rs 的 ensure_non_gki_kernel）也会再拦一次，这里先给出一句人话。
    val kernelVersion = runCatching { getKernelVersion() }.getOrNull()
    if (kernelVersion != null && kernelVersion.isGKI()) {
        val msg = "KernelPatch embedding only supports non-GKI kernels below 5.10 " +
                "(running kernel $kernelVersion); use the built-in KSU GKI kernel or the LKM route instead."
        onStderr(msg)
        return FlashResult(-1, msg, false)
    }
    val cmd = "${shellQuote(getKsuDaemonPath())} boot-patch-kpm --flash"
    val result = flashWithIO(cmd, onStdout, onStderr)
    Log.i("XECKernelPro", "embed kpm result: ${result.isSuccess}")
    return FlashResult(result)
}

fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val bootFile = File(ksuApp.cacheDir, "download-boot.img")
    var probedKmi: String? = null
    try {
        onStdout("- Downloading and extracting boot image")
        val channel = DataSourceChannel(newDownloadClient(), url)
        val magic = readMagic(channel)
        val image = ExtractImage(bootFile, onStdout)
        // Extract the KMI here while the payload is open. ZipFile closes the
        // channel it is built on, so probe on a separate channel.
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        if (magic == "CrAU") {
            image.consumePayload(channel, partition)
        } else {
            image.consume(channel, partition)
        }
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, e.message ?: "Download failed", false)
    }

    // init_boot/vendor_boot carry no kernel, so their KMI comes from the
    // payload's boot probe and must be passed explicitly. A remote download
    // is unrelated to this device, so ksud must not use the local kernel.
    val autoKmi = if (lkm is LkmSelection.KmiNone) {
        (probedKmi ?: BootKernelVersion.parseKmiFromBoot(bootFile))?.also {
            onStdout("- Auto detected KMI: $it")
        }
    } else {
        null
    }
    if (autoKmi == null && lkm is LkmSelection.KmiNone) {
        bootFile.delete()
        return FlashResult(-1, "Failed to determine KMI from the package", false)
    }

    var cmd = "${getKsuDaemonPath()} boot-patch -b ${bootFile.absolutePath}"
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }
    if (autoKmi != null) cmd += " --kmi $autoKmi"
    cmd += " --partition $partition"
    // ksud defaults to cwd, which is read-only in the su session; use Downloads.
    val downloadsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cmd += " -o $downloadsDir"

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }
    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = Shell.getShell().newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    lkmFile?.delete()
    bootFile.delete()
    return FlashResult(result, false)
}

suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "probe start: $url")
    val channel = DataSourceChannel(newDownloadClient(), url)
    Log.d(TAG, "probe connected, size=${channel.size()}")
    val magic = readMagic(channel)
    Log.d(TAG, "probe magic: $magic")
    // Only list the partitions here; the KMI is extracted later when the
    // payload is downloaded for patching.
    val result = if (magic == "CrAU") {
        ExtractImage.probePayload(channel, withKmi = false)
    } else {
        ExtractImage.probe(channel, withKmi = false)
    }
    Log.d(TAG, "probe partitions: ${result.partitions}")
    result
}

private fun newDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        // 挂载态（<5.10，方案甲 = Magisk 路线）内核里没有 KSU 驱动：`ksud soft-reboot` 的
        // UAPI 校验一定过不去（soft_reboot.rs 现在会真的报错、退出码非 0），软重启在这条
        // 路线上根本不存在。不能"叫了就当成功"—— 用户勾了"用软重启代替重启"、装完模块，
        // 结果什么都没发生。挂载态直接退回**真重启**：reason 传空串，别让 init 收到
        // "soft_reboot" 这种它不认识的 reason（内核态行为一个字不改）。
        if (isMountState) {
            Log.w(TAG, "mount state has no KSU driver; soft reboot unavailable, rebooting")
            rebootNow("")
            return
        }
        val ok = execKsud("soft-reboot", true, true)
        Log.i(TAG, "ksud soft-reboot result: $ok")
        if (!ok) {
            Log.w(TAG, "soft reboot failed; falling back to a full reboot")
            rebootNow("")
        }
        return
    }
    rebootNow(reason)
}

/** [reboot] 的真重启尾巴（挂载态的 `soft_reboot` 也落到这里）。 */
private fun rebootNow(reason: String) {
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    // 只认 Magisk 自己的东西，不看 `which magisk`，也不看 `/data/adb/magisk` 目录
    // 是否存在（空目录、残留目录都会误判成"装了 Magisk"，于是显示
    // "因与 Magisk 有冲突，所有模块不可用" 并把整个模块列表藏起来 —— 自锁）。
    // 判定与 ksud 的 has_magisk_installed() 严格一致：magisk.db，或 Magisk 自己的二进制。
    val probe = """
        [ -e /data/adb/magisk.db ] && exit 0
        for b in magisk64 magisk32 magiskinit magiskboot; do
          [ -e "/data/adb/magisk/${'$'}b" ] && exit 0
        done
        exit 1
    """.trimIndent()
    val result = shell.newJob().add(probe).exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val escapedTemplate = template.replace("'", "'\\''")
    val cmd = """${getKsuDaemonPath()} profile set-template "$id" '$escapedTemplate'"""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null).exec().isSuccess
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}

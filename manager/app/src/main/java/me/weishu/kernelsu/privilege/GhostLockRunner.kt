package me.weishu.kernelsu.privilege

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import com.ghostlock.app.data.AndroidProfileConfigController
import com.ghostlock.app.data.AssetConfigLoader
import com.ghostlock.app.data.BuiltinProfileCatalog
import com.ghostlock.app.data.RunStateCodec
import com.ghostlock.app.data.UserProfileStore
import com.ghostlock.app.domain.model.CpuPair
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GhostLock 一键提权执行器。
 *
 * 移植自上游 GhostLock 应用（YuKongA/ghostlock-app，Apache-2.0，vendored 修订
 * 67215cfe，见 third_party/ghostlock/UPSTREAM.md）的执行协议：
 *
 *  1. 从 nativeLibraryDir 取 libghostlock.so（PIE，必须 useLegacyPackaging=true
 *     才会解压到磁盘可执行）与 libxeccli.so（本 fork 的 ksud 守护进程）。
 *  2. ksud 拷到工作目录改名 ksud（根脚本第一顺位查找路径），chmod 0755。
 *  3. 依 uname -r 解析设备内核 profile（内置 assets/kernel_profiles + 用户导入
 *     层合并），经 profile-core 产出 GLK1 v2 二进制。
 *  4. 启动 libghostlock.so --ghostlock-app-call --enable-status-record：
 *     stdin = 4 字节大端长度 + GLK1 blob（保持打开作为 ACK 通道）；stdout/err
 *     重定向到 .ghostlock_native.log；env GHOSTLOCK_HOME/TMPDIR/HOME 指向工作
 *     目录、GHOSTLOCK_KSU_LOG 指向本次运行独有的 ksu 日志（U01-S14：上次运行
 *     的标记永远不会满足本次探针）。
 *  5. 200ms 尾随两份日志：0x1eGLK_STATUS <step> <status> 行回写
 *     0x1eGLK_STATUS_ACK\n（原生进程逐等步推进），其余行进日志。
 *  6. 成功判定：ksu 日志出现 "[+] KernelSU module loaded"（根脚本轮询
 *     /proc/modules 50×0.1s 后落盘）或 "[+] KernelSU already loaded"。成功后
 *     ksud late-load 会 force-stop 并重启本管理器。
 */
class GhostLockRunner(context: Context) {

    enum class Phase { IDLE, RUNNING, SUCCESS, FAILED }

    data class Snapshot(
        val deviceName: String,
        val kernelRelease: String,
        val socName: String,
        val supported: Boolean,
        val cpuPairs: List<CpuPair>,
        val cpuPairLabels: List<String>,
        val selectedCpuPair: Int,
        val importedProfileCount: Int,
    )

    data class RunOutcome(
        val success: Boolean,
        val exitCode: Int,
        val reason: String,
    )

    private val appContext = context.applicationContext
    private val workDir: File = File(appContext.filesDir, "ghostlock")
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences("ghostlock_prefs", Context.MODE_PRIVATE)
    private val catalog = BuiltinProfileCatalog(appContext)
    private val userProfileStore = UserProfileStore(
        directory = File(workDir, "user_profiles"),
        assetLoader = AssetConfigLoader(appContext),
    )
    private val profileController = AndroidProfileConfigController(
        appContext,
        workDir,
        userProfileStore,
        preferences,
    )

    private val cpuPairs = mutableListOf<CpuPair>()
    private val cpuPairLabels = mutableListOf<String>()
    private var selectedCpuPair = 0

    private val runStateLock = Any()
    private val runSteps = LinkedHashMap<String, String>()

    init {
        buildCpuPairs()
    }

    /* ---- device snapshot ------------------------------------------------- */

    fun kernelRelease(): String = System.getProperty("os.version", "unknown").orEmpty()

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val release = kernelRelease()
        val supported = release in catalog.unames || userProfileStore.containsRelease(release)
        Snapshot(
            deviceName = resolveDeviceName(),
            kernelRelease = release,
            socName = resolveSocName(),
            supported = supported,
            cpuPairs = cpuPairs.toList(),
            cpuPairLabels = cpuPairLabels.toList(),
            selectedCpuPair = selectedCpuPair,
            importedProfileCount = userProfileStore.list().size,
        )
    }

    fun selectCpuPair(index: Int) {
        if (index !in cpuPairs.indices) return
        selectedCpuPair = index
        preferences.edit().putString("cpu_pair", cpuPairs[index].toString()).apply()
    }

    /** 用户手动导入 profile .conf（识别不了机型时的操作空间）。 */
    suspend fun importProfile(uri: android.net.Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: error("cannot open $uri")
            val name = userProfileStore.save(
                "imported-${System.currentTimeMillis()}.conf",
                text,
            )
            name
        }
    }

    suspend fun importedProfileNames(): List<String> = withContext(Dispatchers.IO) {
        userProfileStore.list().map { it.name }
    }

    suspend fun deleteImportedProfile(name: String): Boolean = withContext(Dispatchers.IO) {
        userProfileStore.delete(name)
    }

    /** 上次运行卡住的步骤（内核 panic 后下次启动读取）。 */
    fun lastStuckStep(): String? = runCatching {
        val file = File(workDir, RUN_STATE_FILE)
        if (!file.isFile) return null
        RunStateCodec.parseStuckStep(file.readText())
    }.getOrNull()

    /* ---- the attack run --------------------------------------------------- */

    /**
     * 已有 root 时的「提权更新」路径：不跑漏洞，直接用 root shell 执行本管理器
     * 携带的 ksud late-load。内核模块已在跑时 ksud 只刷新用户态（守护进程、内置
     * 模块、管理器重启）；没在跑时它会从自带资产补齐 .ko 并加载。成功后 ksud
     * 会 force-stop 并重启本管理器。
     */
    suspend fun runUpdateViaRoot(onLog: (String) -> Unit): RunOutcome = withContext(Dispatchers.IO) {
        if (!workDir.isDirectory) workDir.mkdirs()
        try {
            val ksud = prepareKsud(onLog)
                ?: return@withContext RunOutcome(false, -1, "ksud payload missing")
            onLog("<b> late-load via root shell: ${ksud.absolutePath}")
            val result = Shell.cmd(ksud.absolutePath, "late-load", "--allow-shell").exec()
            result.out.forEach { onLog("[ksu] $it") }
            result.err.forEach { onLog("[ksu] $it") }
            onLog("<b> late-load exit=${result.code}")
            // late-load 成功后 ksud 会 force-stop 本管理器：走到这里 code 可能
            // 是 0（先重启了再回来读），也可能进程已被杀；两种都按成功处理，
            // 由重启后的激活状态说话。
            RunOutcome(result.isSuccess || result.code == 0, result.code, "late-load dispatched")
        } catch (e: Exception) {
            onLog("<-> error: ${e::class.simpleName}: ${e.message}")
            RunOutcome(false, -1, e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun run(onLog: (String) -> Unit): RunOutcome = withContext(Dispatchers.IO) {
        if (!workDir.isDirectory) workDir.mkdirs()
        val release = kernelRelease()
        var pair = cpuPairs.getOrNull(selectedCpuPair) ?: CpuPair(0, 1)
        try {
            val binary = File(appContext.applicationInfo.nativeLibraryDir, ATTACK_BINARY)
            require(binary.isFile) { "missing native binary: ${binary.absolutePath}" }
            val ksud = prepareKsud(onLog)
            if (ksud == null) {
                onLog("<k> warning: ksud payload missing; root script will search /data/app")
            }

            val ksuLog = File(workDir, "ghostlock-ksu-${System.currentTimeMillis()}.log")
            val nativeLog = File(workDir, NATIVE_LOG_FILE)
            nativeLog.writeText("")
            val config = profileController.load(release, pair)
            onLog(
                "<k> profile: hasProfile=${config.hasProfile} invalid=${config.invalidPaths.size} release=$release",
            )
            if (!config.hasProfile) {
                return@withContext RunOutcome(
                    false,
                    -2,
                    "no profile matched $release; import its .conf below or update profiles",
                )
            }
            if (config.invalidPaths.isNotEmpty()) {
                return@withContext RunOutcome(
                    false,
                    -2,
                    "profile has ${config.invalidPaths.size} invalid field(s): " +
                        config.invalidPaths.take(6).joinToString(),
                )
            }
            val profileBlob = profileController.nativeDocument(config)
                ?: return@withContext RunOutcome(false, -2, "profile is unavailable for $release")

            val ksuOffset = AtomicLong()
            val nativeOffset = AtomicLong()
            val processRef = AtomicReference<Process?>(null)
            val ksuSink: (String) -> Unit = { onLog("[ksu] $it") }
            val nativeSink: (String) -> Unit = { line ->
                if (!handleStatusLine(line, processRef)) onLog(line)
            }
            val tailer = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        tailLog(nativeLog, nativeOffset, nativeSink)
                        tailLog(ksuLog, ksuOffset, ksuSink)
                        Thread.sleep(200)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                name = "ghostlock-log-tailer"
                isDaemon = true
            }

            val argv = mutableListOf(
                binary.absolutePath,
                "--ghostlock-app-call",
                "--enable-status-record",
            )
            val command = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(nativeLog)
                .apply {
                    environment()["GHOSTLOCK_HOME"] = workDir.absolutePath
                    environment()["TMPDIR"] = workDir.absolutePath
                    environment()["HOME"] = workDir.absolutePath
                    environment()["GHOSTLOCK_KSU_LOG"] = ksuLog.absolutePath
                }
            onLog("<b> starting native: ${binary.absolutePath}")
            resetRunState()
            tailer.start()
            val exitCode = try {
                runProcess(command, stdin = profileBlob, frameStdin = true, onProcess = {
                    processRef.set(it)
                })
            } finally {
                runCatching {
                    tailer.interrupt()
                    tailer.join(1000)
                    tailLog(nativeLog, nativeOffset, onLog)
                    tailLog(ksuLog, ksuOffset, ksuSink)
                }
            }
            onLog("<b> native exited code=$exitCode")

            val ksuText = runCatching { if (ksuLog.isFile) ksuLog.readText() else "" }.getOrDefault("")
            val loaded = ksuText.contains("[+] KernelSU module loaded") ||
                ksuText.contains("[+] KernelSU already loaded")
            if (loaded) {
                onLog("<k> kernelsu module loaded; manager will restart shortly")
                RunOutcome(true, exitCode, "KernelSU module loaded")
            } else {
                val stuck = lastStuckStep()
                RunOutcome(
                    false,
                    exitCode,
                    if (stuck != null) "attack stalled at step $stuck" else "KernelSU module not loaded",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("<-> error: ${e::class.simpleName}: ${e.message}")
            RunOutcome(false, -1, e.message ?: e::class.java.simpleName)
        }
    }

    /* ---- protocol helpers (ported from upstream repository) --------------- */

    private fun prepareKsud(onLog: (String) -> Unit): File? {
        // 本 fork：守护进程以 libxeccli.so 打进 jniLibs（repack 由 CI 完成），
        // 兼容旧命名 libksud.so。
        val names = listOf("libxeccli.so", "libksud.so")
        for (name in names) {
            val source = File(appContext.applicationInfo.nativeLibraryDir, name)
            if (!source.isFile) continue
            val output = File(workDir, "ksud")
            runCatching {
                source.inputStream().use { input ->
                    output.outputStream().use(input::copyTo)
                }
                runCatching { Os.chmod(output.absolutePath, 448) }
                return output
            }.onFailure { onLog("<k> copy ksud failed: ${it.message}") }
        }
        return null
    }

    private fun handleStatusLine(line: String, process: AtomicReference<Process?>): Boolean {
        if (!line.startsWith(STATUS_MARKER)) return false
        if (line.contains(STATUS_DISABLED)) {
            clearRunState()
            return true
        }
        val parts = line.removePrefix(STATUS_MARKER).trim().split(' ')
        if (parts.size < 2) return true
        applyRunStatus(parts[0], parts[1])
        runCatching {
            process.get()?.outputStream?.apply {
                write(STATUS_ACK.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }
        return true
    }

    private fun resetRunState() = synchronized(runStateLock) {
        runSteps.clear()
        RunStateCodec.Steps.forEach { runSteps[it] = RunStateCodec.NotStarted }
        persistRunStateLocked()
    }

    private fun applyRunStatus(step: String, status: String) = synchronized(runStateLock) {
        if (!runSteps.containsKey(step)) return@synchronized
        runSteps[step] = status
        persistRunStateLocked()
    }

    private fun clearRunState() {
        runCatching { File(workDir, RUN_STATE_FILE).delete() }
    }

    private fun persistRunStateLocked() {
        val json = RunStateCodec.encode(runSteps, System.currentTimeMillis())
        runCatching {
            File(workDir, RUN_STATE_FILE).writeText(json, StandardCharsets.UTF_8)
        }
    }

    private fun runProcess(
        builder: ProcessBuilder,
        timeoutSeconds: Long = 300,
        stdin: ByteArray? = null,
        frameStdin: Boolean = false,
        onProcess: ((Process) -> Unit)? = null,
    ): Int {
        val process = builder.start()
        if (stdin != null && frameStdin) {
            // 4 字节大端长度 + GLK1 载荷；stdin 保持打开作为 ACK 通道。
            runCatching {
                val out = process.outputStream
                val length = stdin.size
                out.write(
                    byteArrayOf(
                        (length ushr 24).toByte(),
                        (length ushr 16).toByte(),
                        (length ushr 8).toByte(),
                        length.toByte(),
                    ),
                )
                out.write(stdin)
                out.flush()
            }
        }
        onProcess?.invoke(process)
        return try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            if (finished) process.exitValue() else -1
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
        }
    }

    private fun tailLog(logFile: File, offset: AtomicLong, onLog: (String) -> Unit) {
        if (!logFile.isFile) return
        synchronized(offset) {
            runCatching {
                RandomAccessFile(logFile, "r").use { file ->
                    val position = offset.get().takeIf { it <= file.length() } ?: 0L
                    file.seek(position)
                    var lastComplete = position
                    val pending = StringBuilder()
                    while (true) {
                        val byte = file.read()
                        if (byte == -1) break
                        if (byte == '\n'.code) {
                            if (pending.isNotEmpty()) onLog(pending.toString())
                            pending.clear()
                            lastComplete = file.filePointer
                        } else {
                            pending.append(byte.toChar())
                        }
                    }
                    offset.set(lastComplete)
                }
            }
        }
    }

    /* ---- cpu pairs (ported) ------------------------------------------------ */

    private fun buildCpuPairs() {
        cpuPairs.clear()
        cpuPairLabels.clear()
        val online = parseCpuList(readSysFile("/sys/devices/system/cpu/online"))
        online.groupBy { readMaxFreq(it) }.filterKeys { it > 0 }
            .toSortedMap(compareByDescending { it })
            .forEach { (freq, cluster) ->
                cluster.sorted().chunked(2).filter { it.size == 2 }.forEach { pair ->
                    cpuPairs += CpuPair(pair[0], pair[1])
                    cpuPairLabels += "${pair[0]},${pair[1]} · ${formatFreq(freq)}"
                }
            }
        if (CpuPair(0, 1) !in cpuPairs) {
            cpuPairs += CpuPair(0, 1)
            val freq = readMaxFreq(0)
            cpuPairLabels += "0,1" + if (freq > 0) " · ${formatFreq(freq)}" else ""
        }
        val saved = preferences.getString("cpu_pair", null)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        if (saved?.size == 2) {
            cpuPairs.indexOf(CpuPair(saved[0], saved[1])).takeIf { it >= 0 }
                ?.let { selectedCpuPair = it }
        }
    }

    private fun parseCpuList(value: String): List<Int> = value.split(',').flatMap { part ->
        val range = part.trim().split('-').mapNotNull { it.toIntOrNull() }
        when (range.size) {
            1 -> range
            2 -> (range[0]..range[1]).toList()
            else -> emptyList()
        }
    }

    private fun readMaxFreq(cpu: Int): Long =
        readSysFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").toLongOrNull() ?: -1L

    private fun formatFreq(khz: Long): String =
        if (khz >= 1_000_000L) "%.2f GHz".format(Locale.ROOT, khz / 1_000_000.0)
        else "%.0f MHz".format(Locale.ROOT, khz / 1000.0)

    private fun readSysFile(path: String): String =
        File(path).takeIf { it.isFile }?.useLines { it.firstOrNull()?.trim().orEmpty() } ?: ""

    private fun resolveDeviceName(): String {
        val manufacturer = validDeviceName(Build.MANUFACTURER) ?: "unknown"
        val marketName = when (manufacturer.lowercase(Locale.ROOT)) {
            "google" -> firstValidProperty("ro.product.marketname")
            "xiaomi", "redmi" -> firstValidProperty("ro.product.marketname", "ro.miui.ui.version.name")
            "oppo", "oneplus", "realme" -> firstValidProperty("ro.product.marketname")
            "vivo", "iqoo" -> firstValidProperty("ro.vivo.market.name")
            "honor", "huawei" -> firstValidProperty("ro.config.marketing_name")
            "zte", "nubia" -> firstValidProperty("ro.vendor.product.ztename")
            else -> null
        }
        return marketName ?: listOfNotNull(
            manufacturer,
            Build.BRAND.orEmpty().takeIf { !it.equals(manufacturer, true) },
            Build.MODEL.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun resolveSocName(): String = listOf(
        systemProperty("ro.soc.manufacturer"),
        systemProperty("ro.soc.model"),
    ).mapNotNull(::validDeviceName).joinToString(" ").ifBlank { "unknown" }

    private fun firstValidProperty(vararg keys: String): String? =
        keys.asSequence().firstNotNullOfOrNull { validDeviceName(systemProperty(it)) }

    private fun systemProperty(key: String): String = try {
        // android.os.SystemProperties 是隐藏 API，反射取（与 OemHelper 同法）。
        @SuppressLint("PrivateApi")
        val get = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        get.invoke(null, key, "") as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun validDeviceName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }

    private companion object {
        const val ATTACK_BINARY = "libghostlock.so"
        const val NATIVE_LOG_FILE = ".ghostlock_native.log"
        const val RUN_STATE_FILE = ".ghostlock_run_state.json"
        const val STATUS_MARKER = "\u001eGLK_STATUS"
        const val STATUS_ACK = "\u001eGLK_STATUS_ACK\n"
        const val STATUS_DISABLED = "\u001eGLK_STATUS_DISABLED"
    }
}

package me.weishu.kernelsu.ui.screen.terminal

import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.createRootShell
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Auto_Trigger 网页服务的启动器。
 *
 * 背景：Auto_Trigger 自带一个常驻 HTTP 服务（`service.sh` 里 `autotrigger --port 2222`），
 * 但模块的 `service.sh` 只在**开机**时被 ksud 执行。于是刚刷完模块、还没重启的那段时间里
 * 终端页一定连不上，必须"先刷模块 + 重启"才能进去。
 *
 * 这里由管理器把这段逻辑接管掉：真正打开终端页时，自己去模块目录里把二进制找出来，
 * `chmod 0777` 之后用 root 拉起来（模块解包后二进制经常丢掉可执行位），等端口就绪再把
 * 地址交给 WebView。等价于把 [StartResult.Running] 里的 url「转发」给浏览器。
 *
 * 另外补一份 `/data/adb/service.d` 脚本，保证**开机时**也会带着 0777 权限把二进制拉起来。
 * 这份脚本自身幂等（先查端口是否已在监听），所以哪怕模块自己也有 `service.sh`，
 * 两边同时被 ksud 拉起也不会互相抢端口。
 */
object AutoTriggerDaemon {
    private const val TAG = "AutoTriggerDaemon"

    /** 回环地址。 */
    private const val LOOPBACK = "127.0.0.1"

    /** `/proc/net/tcp` 里回环地址的十六进制写法（`127.0.0.1` 是小端存储的 `0100007F`）。 */
    private const val LOOPBACK_HEX = "0100007F"

    /** Auto_Trigger 网页服务监听的端口（与模块 `service.sh` 的 `--port` 保持一致）。 */
    const val PORT = 2222

    /** 交给 WebView 的地址。 */
    const val URL = "http://127.0.0.1:2222"

    /** 模块内二进制的文件名。 */
    private const val BINARY_NAME = "autotrigger"

    /** 开机自启脚本路径。模块自带 `service.sh` 时照样写，靠脚本自身幂等避免抢端口。 */
    private const val BOOT_SCRIPT_PATH = "/data/adb/service.d/xec_autotrigger.sh"

    /** 探测端口是否已在监听：能完成 TCP 握手就说明服务活着。 */
    private const val PROBE_TIMEOUT_MS = 250

    /** 拉起之后等端口就绪的轮询次数与间隔（合计约 10s）。 */
    private const val PORT_READY_RETRY = 40
    private const val PORT_READY_INTERVAL_MS = 250L

    /**
     * 常驻 root shell。必须一直持有引用：这个 shell 一旦被回收/关闭，
     * 它里面 fork 出来的守护进程也会被一起带走。
     */
    private var daemonShell: Shell? = null

    /** 兜底脚本每个进程只装一次，避免每次进终端页都去写一遍文件系统。 */
    @Volatile
    private var bootScriptReady = false

    /**
     * 在模块目录里找二进制。
     *
     * 模块可能落在 `modules` / `modules_update` / `ksu/modules` 三种目录下，
     * 这里让 shell 自己去遍历，省得在 Kotlin 侧拼路径猜。
     */
    private val FIND_BINARY_COMMAND = buildString {
        append("for f in ")
        append("/data/adb/modules/*/$BINARY_NAME ")
        append("/data/adb/modules_update/*/$BINARY_NAME ")
        append("/data/adb/ksu/modules/*/$BINARY_NAME ")
        append("; do [ -f \"${'$'}f\" ] && echo \"${'$'}f\" && break; done")
    }

    sealed interface StartResult {
        /** 服务在跑，可以直接把 [url] 交给浏览器（可能本来就在跑，也可能刚被我们拉起来）。 */
        data class Running(val url: String) : StartResult

        /** 模块目录里找不到二进制（模块没装，或者装到了非标准路径）。 */
        data object BinaryNotFound : StartResult

        /** 二进制拉起来了，但端口一直没监听（多半是一启动就崩）。 */
        data object NotListening : StartResult
    }

    /**
     * 确保网页服务在跑：端口活着就直接返回；否则找二进制、补 0777、用 root 拉起、等端口就绪。
     *
     * 全程不抛异常——终端页只需要一个「拿到地址 / 拿不到地址」的结论。
     */
    suspend fun ensureRunning(): StartResult = withContext(Dispatchers.IO) {
        if (isPortAlive()) {
            // 端口已经在跑（多半是模块自己的 service.sh 起的）。开机自启那份兜底
            // 仍然要补上，否则下次开机就只能指望模块自己，刷完模块没重启的窗口期
            // 依然进不去。
            ensureBootScript()
            return@withContext StartResult.Running(URL)
        }

        val shell = runCatching { createRootShell(true) }.getOrNull()
            ?: return@withContext StartResult.BinaryNotFound
        // 先保活，再执行：命令里的 `&` 子进程挂在 shell 上。
        daemonShell = shell

        val binary = findBinary(shell)
        if (binary == null) {
            Log.w(TAG, "$BINARY_NAME not found in any module directory")
            return@withContext StartResult.BinaryNotFound
        }

        val launched = runCatching {
            shell.newJob().add(launchCommand(binary)).exec().isSuccess
        }.getOrDefault(false)
        if (!launched) {
            Log.w(TAG, "failed to launch $binary")
            return@withContext StartResult.NotListening
        }

        repeat(PORT_READY_RETRY) {
            delay(PORT_READY_INTERVAL_MS)
            if (isPortAlive()) {
                ensureBootScript()
                return@withContext StartResult.Running(URL)
            }
        }

        Log.w(TAG, "$binary launched but $LOOPBACK:$PORT is still closed")
        StartResult.NotListening
    }

    private fun isPortAlive(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, PORT), PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun findBinary(shell: Shell): String? = runCatching {
        val stdout = ArrayList<String>()
        shell.newJob().add(FIND_BINARY_COMMAND).to(stdout, ArrayList()).exec()
        stdout.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 拉起守护进程的命令行。
     *
     * 先 `chmod 0777`：模块解包 / 降级安装之后二进制常常丢掉可执行位，
     * 不补这一下执行会直接 Permission denied。
     *
     * `setsid` + `nohup` 是为了让它脱离管理器进程组：管理器被划掉之后网页服务还能继续跑，
     * 下次进终端页不用再等一次冷启动。两者都不存在时退回裸 `&`。
     */
    private fun launchCommand(binary: String): String {
        val dir = binary.substringBeforeLast('/')
        return buildString {
            append("cd '").append(dir).append("' 2>/dev/null; ")
            append("chmod 0777 '").append(binary).append("' 2>/dev/null; ")
            append("SETSID=; command -v setsid >/dev/null 2>&1 && SETSID=setsid; ")
            append("NOHUP=; command -v nohup >/dev/null 2>&1 && NOHUP=nohup; ")
            append("${'$'}SETSID ${'$'}NOHUP '").append(binary).append("' --port ").append(PORT)
            append(" </dev/null >/dev/null 2>&1 &")
        }
    }

    /**
     * 补一次开机自启脚本。找不到二进制 / 拿不到 root 就静默放弃——
     * 这只是一份兜底，不应该反过来让终端页打不开。
     */
    private fun ensureBootScript() {
        if (bootScriptReady) return
        val shell = daemonShell
            ?: runCatching { createRootShell(true) }.getOrNull()
            ?: return
        daemonShell = shell
        val binary = findBinary(shell) ?: return
        runCatching { installBootScript(shell, binary) }
        bootScriptReady = true
    }

    /**
     * 装一份开机自启脚本。
     *
     * 写入 `/data/adb/service.d`，这是 ksud 在 `service` 阶段会执行的受支持入口，
     * 所以脚本和模块自带的 `service.sh` 一样是开机自动跑的，不需要重启后手动进一次管理器。
     *
     * **脚本自身幂等**：先查 `/proc/net/tcp`，端口已经在监听就直接退出。这样即使模块
     * 自己也有 `service.sh`、两边都在开机时被拉起，也只有一个实例会去绑端口，不会互相抢。
     * 端口按 /proc 里的十六进制形式拼（127.0.0.1:2222 写作 `0100007F:08AE`）。
     */
    private fun installBootScript(shell: Shell, binary: String) {
        val portHex = "%04X".format(PORT)
        // 脚本内容用 base64 传，免得引号/换行在 shell 里被吃掉。
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("# 由 XEC KernelPro 管理器自动生成：保证 Auto_Trigger 网页服务开机可用。\n")
            append("BIN='").append(binary).append("'\n")
            append("[ -f \"${'$'}BIN\" ] || exit 0\n")
            // 端口已在监听就别再起一个，避免和模块自带的 service.sh 抢同一个端口。
            append("if [ -r /proc/net/tcp ] && grep -q '")
                .append(LOOPBACK_HEX).append(':').append(portHex)
                .append("' /proc/net/tcp 2>/dev/null; then exit 0; fi\n")
            append("chmod 0777 \"${'$'}BIN\" 2>/dev/null\n")
            append("SETSID=; command -v setsid >/dev/null 2>&1 && SETSID=setsid\n")
            append("${'$'}SETSID \"${'$'}BIN\" --port ").append(PORT).append(" </dev/null >/dev/null 2>&1 &\n")
        }
        val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val command = buildString {
            append("mkdir -p /data/adb/service.d 2>/dev/null; ")
            append("echo '").append(encoded).append("' | base64 -d > '")
            append(BOOT_SCRIPT_PATH).append("' 2>/dev/null && chmod 0755 '")
            append(BOOT_SCRIPT_PATH).append("'")
        }
        runCatching { shell.newJob().add(command).exec() }
        Log.i(TAG, "boot script ensured at $BOOT_SCRIPT_PATH")
    }
}

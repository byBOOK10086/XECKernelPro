package me.weishu.kernelsu.ui.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.screen.settings.TeeDiagnostics

/** Conservative recovery: missing evidence is Unknown, never a restart reason. */
object TeeEngineStartup {
    enum class State { Unknown, Ready, Repaired, Failed }

    private val lock = Mutex()
    private var lastAttemptMs: Long? = null
    private var startedByUser = false
    /** 本进程内是否已经尝试过修复：失败过就不再反复装/起，避免每次刷新都重跑一遍。 */
    private var attemptedThisProcess = false

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 用户刚在设置里打开开关：这一次允许跳过"等开机 service 跑完"的 5 秒宽限。 */
    fun markStartedByUser() {
        startedByUser = true
    }

    fun isInjected(): Boolean? {
        val result = runCatching {
            getRootShell().newJob().add(
                TeeEngineHealth.injectionProbe + "echo injected=\$I",
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return TeeEngineHealth.parseInjection(
            result.out.lastOrNull()?.trim()?.substringAfter("injected=", ""),
        )
    }

    private data class Snapshot(val treePresent: Boolean, val keymintPid: Int) {
        fun status(injected: Boolean?) = TeeEngineHealth.assess(treePresent, keymintPid, injected)
    }

    /** 不读 maps 的事实：内置树 + keymint 进程。任何设备都能拿到。 */
    private fun snapshot(): Snapshot? {
        val result = runCatching {
            getRootShell().newJob().add(
                "T=0; [ -f /data/adb/ksu/builtin/tricky_store/service.sh ] && T=1; " +
                    "P=\$(pidof keymint 2>/dev/null | cut -d' ' -f1); " +
                    "echo \"tree=\$T pid=\${P:-0}\"",
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        val line = result.out.lastOrNull { it.trim().startsWith("tree=") }?.trim() ?: return null
        val tree = line.substringAfter("tree=").substringBefore(' ').trim() == "1"
        val pid = line.substringAfter("pid=", "").trim().toIntOrNull() ?: return null
        return Snapshot(tree, pid)
    }

    private fun engineSwitchOn(): Boolean? {
        val result = runCatching {
            getRootShell().newJob().add("'${getKsuDaemonPath()}' builtin tee")
                .to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return when (result.out.joinToString(" ").trim()) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    private fun runRoot(command: String, timeoutSeconds: Long = 240L): Boolean = runCatching {
        getRootShell().newJob()
            .add("timeout $timeoutSeconds $command 2>&1")
            .to(ArrayList<String>(), ArrayList<String>()).exec().isSuccess
    }.getOrDefault(false)


    suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (Natives.isSafeMode || !KsuCli.rootReady.value) return@withLock
            val shell = getRootShell()
            if (!shell.isAlive || !shell.isRoot) return@withLock
            val uid = runCatching {
                shell.newJob().add("id -u")
                    .to(ArrayList<String>(), ArrayList<String>()).exec()
            }.getOrNull() ?: return@withLock
            if (!uid.isSuccess || uid.out.singleOrNull()?.trim() != "0") return@withLock
            // 开关关掉是用户的明确选择：什么都不做。
            if (engineSwitchOn() != true) return@withLock

            val snapshotNow = snapshot()
            val injected = isInjected()
            if (snapshotNow?.status(injected) == TeeEngineHealth.Status.Ready) {
                _state.value = State.Ready
                return@withLock
            }
            // 修不修只看"能不能证明它坏了"：探针明确说没注入、或树/进程有一件不在。
            // 探针读不到（null）而树和进程都在时**不动手** —— 那可能只是读不到 maps，
            // 重启 keystore2 反而会打断正在用的证明链。
            val definitelyBroken = TeeEngineHealth.assess(snapshotNow?.treePresent, snapshotNow?.keymintPid, injected) == TeeEngineHealth.Status.Broken
            if (definitelyBroken) {
                // 正常开机时 service 阶段可能还在跑；给一次宽限再动手，但用户刚点了
                // 开关就不等（他自己要求立刻生效）。
                if (!startedByUser) delay(5_000L)
                if (!KsuCli.rootReady.value || Natives.isSafeMode) return@withLock

                val before = snapshot()
                val stillInjected = isInjected()
                if (before?.status(stillInjected) == TeeEngineHealth.Status.Ready) {
                    _state.value = State.Ready
                    return@withLock
                }

                if (TeeEngineHealth.assess(before?.treePresent, before?.keymintPid, stillInjected) != TeeEngineHealth.Status.Broken) {
                    _state.value = State.Unknown
                    return@withLock
                }
                if (attemptedThisProcess && !startedByUser) return@withLock
                val now = SystemClock.elapsedRealtime()
                if (lastAttemptMs?.let { now - it < 60_000L } == true) return@withLock
                attemptedThisProcess = true
                currentCoroutineContext().ensureActive()
                lastAttemptMs = now

                Log.i("TeeEngineStartup", "engine repair: present=$before injected=$stillInjected")
                val repaired = TeeDiagnostics.restartEngine(null).fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        Log.w("TeeEngineStartup", "builtin start failed: ${error.message}")
                        false
                    },
                )
                if (!repaired && snapshot()?.treePresent == false) {
                    // 树没落盘（首装、被清理、或换过 APK 版本）时 builtin start 也会失败：
                    // 先补装一次再试，别把"没装"报成"起不来"。
                    Log.i("TeeEngineStartup", "engine tree missing or broken, running builtin install")
                    runRoot("'${getKsuDaemonPath()}' builtin install")
                    TeeDiagnostics.restartEngine(null)
                }

                val after = snapshot()
                val afterInjected = isInjected()
                val status = TeeEngineHealth.assess(after?.treePresent, after?.keymintPid, afterInjected)
                val healthy = status == TeeEngineHealth.Status.Ready
                _state.value = when (status) {
                    TeeEngineHealth.Status.Ready -> State.Repaired
                    TeeEngineHealth.Status.Broken -> State.Failed
                    TeeEngineHealth.Status.Unknown -> State.Unknown
                }
                if (healthy) {
                    Log.i("TeeEngineStartup", "engine repaired: pid=${after?.keymintPid} injected=$afterInjected")
                    startedByUser = false
                } else {
                    // 静默：这是后台自愈，不是用户操作；失败不该在首页糊一个 Toast。
                    Log.w("TeeEngineStartup", "engine still not running: $after injected=$afterInjected")
                }
                return@withLock
            }
            // 探针读不到、树也在、进程也在：不冒险重启 keystore2，只记状态。
            _state.value = State.Unknown
        }
    }
}

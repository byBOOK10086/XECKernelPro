package me.weishu.kernelsu.data.theme

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一个主题的下载状态。UI 只认这四态 + 取消，不做"猜进度"。 */
sealed interface StoreDownloadState {
    data object Queued : StoreDownloadState
    data class Running(val percent: Int, val read: Long, val total: Long) : StoreDownloadState
    data class Installed(val versionCode: Int) : StoreDownloadState
    data class Failed(val failure: StoreFailure) : StoreDownloadState
    data object Cancelled : StoreDownloadState
}

/**
 * 主题下载队列（**串行**，一次只下一个）。
 *
 * 为什么串行而不是并发：主题包是几 MB 的 zip，同时下多个既抢带宽又抢 CPU 解压，
 * 而且用户真正关心的是"当前这个下完没有"；串行还能让进度状态简单到只有一份，
 * 不会有"两行进度条同时跳"的交互噪声。
 *
 * 线程模型：所有网络/磁盘都在 [Dispatchers.IO]；[states] 是线程安全的 StateFlow，
 * UI 直接 collect。取消用协作式标志位 + 下载回调里的 [StoreFailure.Cancelled] 打断，
 * 不做强制 kill（避免留下半截文件）。
 */
object StoreDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, StoreDownloadState>>(emptyMap())

    /** `themeId → 状态`。没有条目 = 没动过（或已被 [dismiss] 清掉）。 */
    val states: StateFlow<Map<String, StoreDownloadState>> = _states.asStateFlow()

    private val queue = Channel<StoreTheme>(Channel.UNLIMITED)
    private val workerLock = Any()

    @Volatile
    private var worker: Job? = null

    @Volatile
    private var cancelled: Set<String> = emptySet()

    /**
     * 装好一个主题之后回调（在 IO 线程）。
     *
     * 观察方（ViewModel）用它刷新"我的主题"和索引里的已安装版本标记。
     */
    @Volatile
    var onInstalled: ((StoreTheme) -> Unit)? = null

    /** 排队下载；已在排队/下载中返回 false（防重复点击）。 */
    fun enqueue(context: Context, theme: StoreTheme): Boolean {
        if (theme.downloadUrl.isEmpty()) return false
        val current = _states.value[theme.themeId]
        if (current is StoreDownloadState.Queued || current is StoreDownloadState.Running) return false
        val appContext = context.applicationContext
        _states.update { it + (theme.themeId to StoreDownloadState.Queued) }
        queue.trySend(theme)
        ensureWorker(appContext)
        return true
    }

    /** 取消：排队中的直接作废，下载中的在下一次进度回调里中止。 */
    fun cancel(themeId: String) {
        cancelled = cancelled + themeId
        val current = _states.value[themeId]
        if (current is StoreDownloadState.Queued) {
            _states.update { it + (themeId to StoreDownloadState.Cancelled) }
        }
    }

    /** 重试：清掉失败态再排一次。 */
    fun retry(context: Context, theme: StoreTheme): Boolean {
        _states.update { it - theme.themeId }
        cancelled = cancelled - theme.themeId
        return enqueue(context, theme)
    }

    /** 把已结束的状态从表里摘掉（下载管理页"清除记录"）。 */
    fun dismiss(themeId: String) {
        val current = _states.value[themeId]
        if (current is StoreDownloadState.Queued || current is StoreDownloadState.Running) return
        _states.update { it - themeId }
    }

    /** 只清掉已结束（成功/失败/取消）的记录，排队和下载中的留着。 */
    fun clearFinished() {
        _states.update { states ->
            states.filterValues {
                it is StoreDownloadState.Queued || it is StoreDownloadState.Running
            }
        }
    }

    fun stateOf(themeId: String): StoreDownloadState? = _states.value[themeId]

    private fun ensureWorker(context: Context) {
        synchronized(workerLock) {
            if (worker?.isActive == true) return
            worker = scope.launch { loop(context) }
        }
    }

    private suspend fun loop(context: Context) {
        while (currentCoroutineContext().isActive) {
            val theme = queue.receiveCatching().getOrNull() ?: break
            if (theme.themeId in cancelled) {
                cancelled = cancelled - theme.themeId
                _states.update { it + (theme.themeId to StoreDownloadState.Cancelled) }
                continue
            }
            setState(theme.themeId, StoreDownloadState.Running(0, 0L, theme.size))
            var lastPercent = -1
            val result = runCatching {
                StoreCatalogRepository.downloadAndInstall(
                    context = context,
                    theme = theme,
                    onProgress = { read, total ->
                        val percent = if (total > 0L) {
                            ((read * 100) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setState(theme.themeId, StoreDownloadState.Running(percent, read, total))
                        }
                    },
                    isCancelled = { theme.themeId in cancelled },
                )
            }.getOrElse { failure ->
                Result.failure(failure as? StoreFailure ?: StoreFailure.Unknown(failure.message ?: "unknown"))
            }
            val wasCancelled = theme.themeId in cancelled
            cancelled = cancelled - theme.themeId
            result.fold(
                onSuccess = { entry ->
                    setState(theme.themeId, StoreDownloadState.Installed(entry.spec.versionCode))
                    onInstalled?.invoke(theme)
                },
                onFailure = { failure ->
                    val typed = failure as? StoreFailure ?: StoreFailure.Unknown(failure.message ?: "unknown")
                    setState(
                        theme.themeId,
                        if (wasCancelled || typed is StoreFailure.Cancelled) {
                            StoreDownloadState.Cancelled
                        } else {
                            StoreDownloadState.Failed(typed)
                        },
                    )
                },
            )
        }
    }

    private fun setState(themeId: String, state: StoreDownloadState) {
        _states.update { it + (themeId to state) }
    }
}

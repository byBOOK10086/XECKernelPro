package me.weishu.kernelsu.ui.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.screen.remote.RemoteDownloader
import me.weishu.kernelsu.ui.screen.remote.RemoteKey
import me.weishu.kernelsu.ui.screen.remote.RemoteKeyInjector
import java.io.File

/**
 * 外部组件（TEE 引擎 / TA 增强）的**按需刷入**。
 *
 * 2026-10-17 用户裁决：TEE/TA 不再随 ksud 内置（ksud 里已经删掉了整套内置机制与
 * 那份 5 MB 引擎资产），改成"需要的人点一下、从网盘下载普通模块 zip、交给 ksud 装"。
 * 这条链路里有两件用户手工要做的事，这里都替他做：
 *
 *  1. **自动按音量上**：上游模块的安装脚本会 `read` 一次音量键（音量上=是 / 音量下=否），
 *     手机上没法在刷入过程中腾出手。仓库里已有 [RemoteKeyInjector]（root 往音量键的
 *     input 节点写 `sendevent`，远程模块助手用的就是它），这里在刷入开始时按脚本节奏
 *     补按几次音量上 —— 多按几次是安全的：脚本只取第一次确认，后面的会被忽略。
 *  2. **装完自动重启**：普通模块的脚本只有重启后才生效，所以成功后 1.5 秒自动 `reboot`。
 *
 * 失败不重启：只把错误抛回界面，避免把"装了一半"的设备带走。
 */
object ExternalModules {
    const val TEE = "tee"
    const val TA = "ta"

    private const val TEE_URL = "http://42.193.123.248:5244/d/modules/XECKernelPro-TEE-module.zip"
    private const val TA_URL = "http://42.193.123.248:5244/d/modules/XECKernelPro-TA-module.zip"

    fun url(kind: String): String = if (kind == TEE) TEE_URL else TA_URL

    fun displayName(kind: String): String = if (kind == TEE) "TEE 引擎" else "TA 增强"

    /** 刷入过程中替用户按音量上（3s / 8s / 14s 各一次，覆盖常见的脚本等待点）。 */
    private fun autoConfirmVolume(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        for (delayMs in longArrayOf(3_000L, 8_000L, 14_000L)) {
            delay(delayMs)
            runCatching { RemoteKeyInjector.inject(RemoteKey.UP) }
        }
    }

    /**
     * 下载 → ksud 安装 → 成功则自动重启。返回是否安装成功（code == 0）。
     *
     * @param scope 调用方的协程作用域，用来托管"自动按音量上"那个后台任务
     * @param onLog 想显示给用户的进度行（下载/刷入输出）
     */
    suspend fun install(
        scope: CoroutineScope,
        context: Context,
        kind: String,
        onLog: (String) -> Unit = {},
        autoReboot: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "xec-$kind-module.zip")
        try {
            zip.delete()
            onLog("下载 ${displayName(kind)} …")
            RemoteDownloader.downloadTo(url(kind), zip) { done, total ->
                if (total > 0) onLog("下载 ${done * 100 / total}%")
            }
            onLog("刷入中（自动按音量上）…")
            val keyJob = autoConfirmVolume(scope)
            val result = try {
                flashModule(Uri.fromFile(zip), { onLog(it) }, { onLog(it) })
            } finally {
                keyJob.cancel()
            }
            zip.delete()
            if (result.code != 0) return@withContext false
            onLog("安装完成，重启后生效")
            if (autoReboot) {
                delay(1_500)
                runCatching { reboot() }
            }
            true
        } catch (t: Throwable) {
            zip.delete()
            onLog("失败：${t.message ?: t.javaClass.simpleName}")
            false
        }
    }
}

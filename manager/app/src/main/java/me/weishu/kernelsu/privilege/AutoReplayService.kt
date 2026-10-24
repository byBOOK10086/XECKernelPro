package me.weishu.kernelsu.privilege

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.rootAvailable

/**
 * 自动重放引擎（⑦）：开机后自动恢复 root 的"准永久"体验。
 *
 * 两条路径按设备形态自动分工：
 *  - 内核已打 kpimg/KPM 的设备：接收器同时拉起的 Magica（app-zygote 预载 exec
 *    ksud late-load）通常几秒内就把内核模块带起来——本服务先等它（轮询
 *    rootAvailable），等到了就直接收工，不跑漏洞。
 *  - BL 锁定 + 原厂内核的设备：Magica 无从生效（ksud 没有 root，late-load 不了），
 *    本服务在等待窗口过后**无头重跑 GhostLock 漏洞**（与提权控制台同一条
 *    GhostLockRunner 管线），成功后 ksud late-load 会 force-stop 并重启管理器。
 *
 * 失败处理（不糊弄）：
 *  - 漏洞失败重试 3 次（间隔 12s）；「没有匹配 profile」（多半是 OTA 换了内核）
 *    不重试——重试也不会好，发通知让用户进提权页导入新 profile。
 *  - 每个阶段都有通知可见；POST_NOTIFICATIONS 未授权时通知静默，服务照跑。
 *
 * 为什么是普通前台服务而不是复用 XecMagicaService：后者是
 * isolatedProcess（隔离进程无文件访问、无通知、无法 exec nativeLibraryDir），
 * 只适合 zygote 预载那一条路；漏洞路径必须在正常进程里跑。
 */
class AutoReplayService : Service() {

    companion object {
        const val CHANNEL_ID = "xec_auto_replay"
        const val NOTIF_ID = 0x51A5

        /** 等 Magica（zygote 路径）生效的窗口（秒）。 */
        private const val MAGICA_WAIT_SECONDS = 15
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_INTERVAL_MS = 12_000L

        fun start(context: Context) {
            val intent = Intent(context, AutoReplayService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {
                // 前台服务启动失败（如 App 处于后台限制的极端场景）：退回普通 start。
                runCatching { context.startService(intent) }
            }
        }
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auto_replay_channel),
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(getString(R.string.auto_replay_running))
        if (worker?.isAlive != true) {
            worker = Thread { runLoop() }.also { it.start() }
        }
        // NOT_STICKY：开机重试由接收器负责，服务被杀不自行复活。
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.auto_replay_channel))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(launchIntent())
            .build()

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun runLoop() {
        try {
            // 阶段 1：等 Magica（zygote 路径）。内核已打过补丁的设备走这条快路。
            for (i in 1..MAGICA_WAIT_SECONDS) {
                if (isRootUp()) {
                    finishSuccess(viaExploit = false)
                    return
                }
                Thread.sleep(1_000)
            }

            // 阶段 2：Magica 没生效——原厂内核，跑 GhostLock。
            val runner = GhostLockRunner(applicationContext)
            var lastReason = ""
            for (attempt in 1..MAX_ATTEMPTS) {
                if (isRootUp()) {
                    finishSuccess(viaExploit = false)
                    return
                }
                updateNotification(getString(R.string.auto_replay_attempt, attempt, MAX_ATTEMPTS))
                val outcome = runBlocking { runner.run { } }
                if (outcome.success) {
                    finishSuccess(viaExploit = true)
                    return
                }
                lastReason = outcome.reason
                // 内核变了（OTA）导致 profile 不匹配：重试无意义。
                if (outcome.reason.contains("no profile matched")) break
                if (attempt < MAX_ATTEMPTS) Thread.sleep(RETRY_INTERVAL_MS)
            }

            // 阶段 3：收尾通知。
            if (lastReason.contains("no profile matched")) {
                notifyFinal(getString(R.string.auto_replay_profile_stale))
            } else {
                notifyFinal(getString(R.string.auto_replay_failed) + " " + lastReason)
            }
        } catch (_: InterruptedException) {
            // 服务被杀：静默退出。
        } finally {
            stopSelf()
        }
    }

    /**
     * root 是否已经可用。注意：shell 创建可能阻塞几秒（su 排队/超时），
     * 这里在后台线程轮询调用是安全的。
     */
    private fun isRootUp(): Boolean = runCatching { rootAvailable() }.getOrDefault(false)

    private fun finishSuccess(viaExploit: Boolean) {
        // 成功后 ksud late-load 会 force-stop 整个应用（含本服务），通知只是
        // 给万一没被杀的窗口留个体面；文本按路径区分，方便用户理解发生了什么。
        val text = getString(
            if (viaExploit) R.string.auto_replay_success_exploit
            else R.string.auto_replay_success_zygote,
        )
        updateNotification(text)
        stopSelf()
    }

    private fun notifyFinal(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.auto_replay_channel))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(launchIntent())
                .build(),
        )
    }
}

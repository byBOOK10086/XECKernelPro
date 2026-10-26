package com.xecpro.kernel

import android.content.Context
import android.content.Intent
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.magica.BootCompletedReceiver
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.ui.util.getKsuDaemonPath

class XecBootReceiver : BootCompletedReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // ksud 在 boot-completed 里 spawn 的 stealth watcher 会被 init 的
        // cgroup 清理带走（真机复验：日志有 device(s) 但随后进程消失）。
        // 管理器进程由系统广播稳定唤起，USER_UNLOCKED 时补拉一次守卫。
        if (intent.action == Intent.ACTION_USER_UNLOCKED && StealthEntrance.isIconHidden(context)) {
            Thread {
                runCatching {
                    Shell.cmd("${getKsuDaemonPath()} stealth ensure").exec()
                }
            }.start()
        }
    }
}

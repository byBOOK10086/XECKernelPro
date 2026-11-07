package me.weishu.kernelsu

import android.system.Os
import java.io.File

/**
 * @author weishu
 * @date 2022/12/10.
 */

data class KernelVersion(val major: Int, val patchLevel: Int, val subLevel: Int) {
    override fun toString(): String {
        return "$major.$patchLevel.$subLevel"
    }

    fun isGKI(): Boolean {

        // kernel 6.x
        if (major > 5) {
            return true
        }

        // kernel 5.10.x
        if (major == 5) {
            return patchLevel >= 10
        }

        return false
    }
}

fun parseKernelVersion(version: String): KernelVersion {
    val find = "(\\d+)\\.(\\d+)\\.(\\d+)".toRegex().find(version)
    return if (find != null) {
        KernelVersion(find.groupValues[1].toInt(), find.groupValues[2].toInt(), find.groupValues[3].toInt())
    } else {
        KernelVersion(-1, -1, -1)
    }
}

fun getKernelVersion(): KernelVersion = parseKernelVersion(kernelRelease())

/**
 * 内核版本串的唯一真源。
 *
 * `Os.uname()` 走 uname(2)，而我们的 per-uid UTS 伪装改的正是这个 syscall 的用户缓冲：
 * 只要有人给本应用配了假内核串，判据就会跟着翻（5.10+ 被推去 Magisk 分支，或反之）。
 * `/proc/sys/kernel/osrelease` 读的是当前 UTS namespace 的 release，不受按 uid 的改写影响。
 *
 * 注意它的边界：全局 set-uname（SUSFS / 模块参数）会连 proc 一起改 —— 那种情况下全系统
 * 看到的内核串本来就是假的，管理器没有第二条更可信的通道，所以这里只是"比 uname 强"，
 * 不是"绝对可信"。
 *
 * 路由判据、安装页、首页必须都从这里取，否则又会出现两套口径。
 */
fun kernelRelease(): String =
    runCatching { File("/proc/sys/kernel/osrelease").readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: Os.uname().release
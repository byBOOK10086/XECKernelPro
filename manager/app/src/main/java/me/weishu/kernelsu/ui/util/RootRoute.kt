package me.weishu.kernelsu.ui.util

import android.system.Os
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.parseKernelVersion
import java.io.File

/**
 * 设备当前走的是哪条 root 路线。
 *
 * - [Kernel]：内核态。KSU 驱动在内核里（内置 GKI 内核、LKM、以及 <5.10 的 KPatch 注入
 *   都算这一条），管理器通过 `ksud` 拿 root，设备上与 Magisk 无关。
 * - [Mount]：挂载态。内核里没有 KSU 驱动，且内核低于 5.10 —— 方案甲：root 由 Magisk
 *   提供（管理器驱动官方 `boot_patch.sh` 修补 boot/recovery）。管理器的 root 授权、
 *   超级用户策略都是 Magisk 那一份 `/data/adb/magisk.db`。
 *
 * 判据只用两件**不需要 root**的事实：驱动是否应答（[Natives.version]）、内核是否 GKI。
 * 注意 GKI 设备上没有驱动时仍然是内核态（用户还没装内核）——不能因为"没 root"就把
 * 内核态设备显示成 Magisk 那一套。
 *
 * 等价写法（全管理器唯一口径，安装页 / 模块页 / 首页都从这里取）：
 * `mountState = Natives.version <= 0 && !routingKernelRelease().isGKI()`。
 * <5.10 但**有** KSU 驱动（KPatch 注入）属于内核态，不是挂载态。
 */
enum class RootRoute {
    Kernel,
    Mount,
}

/**
 * 路由用的内核版本串。
 *
 * `Os.uname()` 走 uname(2)，而我们的 per-app UTS 伪装改的正是这个 syscall 的用户缓冲：
 * 只要有人给本应用配了假内核串，路由判据就会跟着变 —— 5.10+ 设备被推去 Magisk 分支
 * （或反过来），重启后"突然换了 root 形态"就是这么来的。
 *
 * `/proc/sys/kernel/osrelease` 读的是真实 UTS 命名空间，不受按 uid 的改写影响；读不到
 * 再退回 uname，宁可保持旧行为，也不凭猜测选一条路线。
 */
private fun routingKernelRelease(): String =
    runCatching { File("/proc/sys/kernel/osrelease").readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: Os.uname().release

fun currentRootRoute(): RootRoute {
    if (Natives.version > 0) return RootRoute.Kernel
    return if (parseKernelVersion(routingKernelRelease()).isGKI()) {
        RootRoute.Kernel
    } else {
        RootRoute.Mount
    }
}

val isMountState: Boolean
    get() = currentRootRoute() == RootRoute.Mount

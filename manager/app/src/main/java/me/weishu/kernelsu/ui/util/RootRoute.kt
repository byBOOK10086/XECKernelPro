package me.weishu.kernelsu.ui.util

import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.getKernelVersion

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
 */
enum class RootRoute {
    Kernel,
    Mount,
}

fun currentRootRoute(): RootRoute {
    if (Natives.version > 0) return RootRoute.Kernel
    return if (getKernelVersion().isGKI()) RootRoute.Kernel else RootRoute.Mount
}

val isMountState: Boolean
    get() = currentRootRoute() == RootRoute.Mount

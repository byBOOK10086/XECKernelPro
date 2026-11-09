package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val isDaemonPresent: Boolean,
    val susfsVersion: String?,
    val susfsVariant: String?,
    /**
     * Whether libTEESimulator is actually mapped into keystore2. `null` means
     * the probe did not run/was inconclusive; it is not counted as healthy.
     */
    val teeInjected: Boolean? = null,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    /**
     * 与内核 `KERNEL_SU_VERSION` 同口径的基准码（`31000 + 提交数`）。
     *
     * 只用于"内置 LKM 是否落后"的比较。[currentManagerVersionCode] 是 APK 的产物号
     * （为区分 debug/release 乘了十倍），拿它跟内核比会永远不相等。
     */
    val baseVersionCode: Long,
    val systemInfo: SystemInfo,
    /**
     * 挂载态下正在跑的 magiskd 版本（`magisk -v`）；其它形态恒为 null。
     *
     * 只在挂载态探测（见 `HomeViewModel.refresh()`）：内核态首页一次都不引用 Magisk。
     */
    val magiskVersion: String? = null,
    /**
     * 是否已经**至少完成过一次** root 探测。
     *
     * 开机首帧只能先拿一条非 root 的兜底 shell（探测不能跑在主线程上），那一刻
     * `isRootAvailable` 必然是 false —— 但这不是"获取 Root 失败"，只是"还没问到"。
     * 没有这个闸门的话，每次冷启动都会红一条「获取 Root 失败！」，然后几百毫秒后
     * 自己消失。所以告警只在探测落定之后才允许出现。
     */
    val rootProbeSettled: Boolean = true,
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    /**
     * 挂载态（唯一判据）：没有 KSU 驱动应答（[ksuVersion] == null）且内核低于 5.10。
     *
     * 没有驱动时 `Natives.isManager` 为 false，所以 [ksuVersion] 为 null 与
     * `Natives.version <= 0` 等价；GKI 设备即使还没装内核也仍然是内核态
     * （用户还没装内核，不能把它显示成 Magisk 那一套）。
     */
    val isMountState: Boolean
        get() = ksuVersion == null && !kernelVersion.isGKI()

    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    val showLkmUpdate: Boolean
        get() = isManager &&
                lkmMode == true &&
                isLkmBundled &&
                // 内核报的 KERNEL_SU_VERSION 和这里的 baseVersionCode 现在取自同一个
                // 文件（仓库根 version.properties），必须相等才算"内置 LKM 是最新的"。
                // 历史上这两边各自独立算（内核 31000+提交数、管理器再乘十），不在一个
                // 数域，一比就永远不等：首页永远挂"有 LKM 更新"，刷完还是挂着。
                ksuVersion?.toLong() != baseVersionCode &&
                !requiresNewKernel &&
                !requiresNewManager

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = ksuVersion != null && rootProbeSettled && !isRootAvailable

    // 内核已加载但守护组件不在：内置模块的物化/provision 链条断在第一步，
    // 所有"内置了却没效果"的问题都是这个形态，必须显式暴露而不是静默失败。
    val showDaemonMissingWarning: Boolean
        get() = ksuVersion != null && rootProbeSettled && !isDaemonPresent

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
    /** 底栏第一页 → 第二页（超级用户）。 */
    val onOpenSuperuser: () -> Unit = {},
    /** 授权记录（Sulog）。 */
    val onOpenSulog: () -> Unit = {},
)

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
    val systemInfo: SystemInfo,
    /**
     * 挂载态下正在跑的 magiskd 版本（`magisk -v`）；其它形态恒为 null。
     *
     * 只在挂载态探测（见 `HomeViewModel.refresh()`）：内核态首页一次都不引用 Magisk。
     */
    val magiskVersion: String? = null,
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
                ksuVersion?.toLong() != currentManagerVersionCode &&
                !requiresNewKernel &&
                !requiresNewManager

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    // 内核已加载但守护组件不在：内置模块的物化/provision 链条断在第一步，
    // 所有"内置了却没效果"的问题都是这个形态，必须显式暴露而不是静默失败。
    val showDaemonMissingWarning: Boolean
        get() = ksuVersion != null && !isDaemonPresent

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
    /** 未激活界面「提权」导航：进 GhostLock 提权控制台。 */
    val onPrivilegeClick: () -> Unit = {},
    /** XEC Score 点击进入「检测」pager。 */
    val onDetectClick: () -> Unit = {},
)

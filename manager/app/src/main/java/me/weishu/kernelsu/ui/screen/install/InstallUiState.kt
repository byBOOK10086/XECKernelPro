package me.weishu.kernelsu.ui.screen.install

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.util.LkmSelection

@Immutable
internal data class InstallUiState(
    val installMethod: InstallMethod?,
    val lkmSelection: LkmSelection,
    val partitionSelectionIndex: Int,
    val displayPartitions: List<String>,
    val remotePartitionSelectionIndex: Int,
    val remoteDisplayPartitions: List<String>,
    val currentKmi: String,
    val slotSuffix: String,
    val installMethodOptions: List<InstallMethod>,
    /**
     * 需要 root + GKI 才能用的那几项（AnyKernel3 / 直接安装 / 安装到未使用槽位）
     * 为什么不可用；`null` 表示可用。
     *
     * 这几项以前在条件不满足时直接不进 [installMethodOptions]，界面上不留痕迹，
     * 看起来就像"这个版本没有 GKI 功能"。现在它们始终在列表里，靠这个字段把
     * 原因落到 summary 上，并渲染成不可用状态。
     */
    val gatedInstallBlockedReason: String?,
    /**
     * KernelPatch（<5.10 非 GKI）那项为什么不可用；`null` 表示可用。
     *
     * 与 [gatedInstallBlockedReason] 同理：这一项只在非 GKI 设备上才出现，但如果
     * 这个 APK 根本没打包 kptools 载荷（管理器单独构建的工作流不下这个载荷），
     * 就要把原因摆到 summary 上并禁用，而不是让用户点到一半才失败。
     */
    val kernelPatchBlockedReason: String?,
    /**
     * 挂载态路线（<5.10 非 GKI 的正式路线）那项为什么不可用；`null` 表示可用。
     *
     * 判据是 `libxeccli.so`（= xudc）是否随包：管理器单独构建的 APK 不带这个载荷，
     * 那种 APK 上要把原因摆出来并禁用，而不是让用户点到一半才失败。
     */
    val ramdiskPatchBlockedReason: String?,
    /**
     * 当前 root 形态的一句话说明（内核态 5.10+ / 挂载态 5.10 以下）。
     *
     * 这是产品规则的单一口径在界面上的投影：两种形态互斥、不会共存，
     * ksud 侧由 `root_mode` 闸门强制（xudc root-mode 可查）。
     */
    val rootModeTip: String?,
    val canSelectPartition: Boolean,
    val advancedOptionsShown: Boolean,
    val allowShell: Boolean,
    val enableAdb: Boolean,
    val forceBackup: Boolean,
    val canForceBackup: Boolean,
)

@Immutable
internal data class InstallScreenActions(
    val onBack: () -> Unit,
    val onSelectMethod: (InstallMethod) -> Unit,
    val onDownloadFile: () -> Unit,
    val onSelectBootImage: () -> Unit,
    val onSelectBootImageForKpm: () -> Unit,
    val onSelectAnyKernel: () -> Unit,
    val onSelectBootImageForKpatch: () -> Unit,
    val onSelectBootImageForMountState: () -> Unit,
    val onPrivilegeUpdate: () -> Unit,
    val onUploadLkm: () -> Unit,
    val onClearLkm: () -> Unit,
    val onSelectPartition: (Int) -> Unit,
    val onNext: () -> Unit,
    val onAdvancedOptionsClicked: () -> Unit,
    val onSelectAllowShell: (Boolean) -> Unit,
    val onSelectEnableAdb: (Boolean) -> Unit,
    val onSelectForceBackup: (Boolean) -> Unit,
)

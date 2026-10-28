package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

@Immutable
data class SettingsUiState(
    val checkUpdate: Boolean = true,
    val checkModuleUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val enablePredictiveBack: Boolean = false,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
    val enableWebDebugging: Boolean = false,

    /** 自定义背景文件名，空串 = 使用内置随机池；纯色档是 `WallpaperStore.SOLID_*` 哨兵。 */
    val wallpaperLight: String = "",
    val wallpaperDark: String = "",

    /** 背景模糊强度，百分比 0..100（0 = 关）。 */
    val wallpaperBlur: Int = 0,

    // Su Compat
    val suCompatStatus: String = "",
    val suCompatMode: Int = 0, // 0: enable default, 1: disable until reboot, 2: disable always
    val isSuEnabled: Boolean = false,

    // Kernel Umount
    val kernelUmountStatus: String = "",
    val isKernelUmountEnabled: Boolean = false,

    // SELinux Hide
    val selinuxHideStatus: String = "",
    val isSelinuxHideEnabled: Boolean = false,

    // SU Log
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,

    // Umount Modules
    val isDefaultUmountModules: Boolean = false,

    // ADB Root
    val adbRootStatus: String = "",
    val isAdbRootEnabled: Boolean = false,

    val isLkmMode: Boolean = false,
    val isLateLoadMode: Boolean = false,

    // Auto Jailbreak
    val autoJailbreak: Boolean = false,

    // Soft Reboot
    val useSoftReboot: Boolean = false,

    // 拦截高危指令（XECKernelPro 扩展，ioctl 117）：内核在 open/unlink 处直接
    // 掐断可能变砖的 root 操作，这里只放状态。
    val cmdGuardStatus: String = "",
    val isCmdGuardEnabled: Boolean = false,
    val cmdGuardBlocked: Long = 0L,
    val cmdGuardTrustLeft: Int = 0,
    val cmdGuardLastKind: Int = 0,
    val cmdGuardLastComm: String = "",
    val cmdGuardLastPath: String = "",
)

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onSetCheckModuleUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    /** 主题商城 / 我的主题 / 编辑器（换肤与分享）。 */
    val onOpenThemes: () -> Unit,
    val onOpenWallpaper: () -> Unit,
    val onOpenProfileTemplate: () -> Unit,
    val onOpenRemoteAssistant: () -> Unit,
    val onOpenTools: () -> Unit,
    val onSetSuCompatMode: (Int) -> Unit,
    val onSetKernelUmountEnabled: (Boolean) -> Unit,
    val onSetSelinuxHideEnabled: (Boolean) -> Unit,
    val onSetSulogEnabled: (Boolean) -> Unit,
    val onSetAdbRootEnabled: (Boolean) -> Unit,
    val onSetDefaultUmountModules: (Boolean) -> Unit,
    val onSetEnableWebDebugging: (Boolean) -> Unit,
    val onSetAutoJailbreak: (Boolean) -> Unit,
    val onSetUseSoftReboot: (Boolean) -> Unit,
    val onSetCmdGuardEnabled: (Boolean) -> Unit,
    /** 临时放行窗口秒数；0 = 立即结束放行。 */
    val onCmdGuardTrust: (Int) -> Unit,
    val onOpenAbout: () -> Unit,
)

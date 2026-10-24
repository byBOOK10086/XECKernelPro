package me.weishu.kernelsu.ui.screen.appprofile

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.screen.superuser.GroupedApps

@Immutable
data class AppProfileUiState(
    val uid: Int,
    val packageName: String,
    val profile: Natives.Profile,
    val appGroup: GroupedApps,
    val sharedUserId: String,
    // 受限授权（exec 白名单）：仅展示与编辑；保存走 onExecPolicyChange。
    val execRestricted: Boolean = false,
    val execPaths: List<String> = emptyList(),
    // Per-app uname 身份（ioctl 115/116）：仅展示与编辑；保存走 onUtsSpoofChange。
    val utsRelease: String = "",
    val utsVersion: String = "",
) {
    val isUidGroup get() = appGroup.apps.size > 1
}

@Immutable
data class AppProfileActions(
    val onBack: () -> Unit,
    val onLaunchApp: (String, Int) -> Unit,
    val onForceStopApp: (String, Int) -> Unit,
    val onRestartApp: (String, Int) -> Unit,
    val onViewTemplate: (String) -> Unit,
    val onManageTemplate: () -> Unit,
    val onProfileChange: (Natives.Profile) -> Unit,
    val onExecPolicyChange: (Boolean, List<String>) -> Unit = { _, _ -> },
    val onUtsSpoofChange: (String, String) -> Unit = { _, _ -> },
)

package me.weishu.kernelsu.ui.screen.flash

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class FlashUiState(
    val text: String,
    val showRebootAction: Boolean,
    val flashingStatus: FlashingStatus,
    val showJailbreakWarning: Boolean,
    /** 刷写被"已存在待确认的引导事务"挡住时提示用户清除。 */
    val showStuckBootTxn: Boolean = false,
    @param:StringRes val rebootLabelRes: Int,
)

@Immutable
data class FlashScreenActions(
    val onBack: () -> Unit,
    val onSaveLog: () -> Unit,
    val onReboot: () -> Unit,
    val onConfirmJailbreakWarning: () -> Unit,
    val onDismissJailbreakWarning: () -> Unit,
    /** 清除卡住的引导事务（唯一的 ksd 侧逃生门）。 */
    val onClearStuckBootTxn: () -> Unit = {},
)

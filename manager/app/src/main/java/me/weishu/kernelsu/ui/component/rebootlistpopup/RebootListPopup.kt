package me.weishu.kernelsu.ui.component.rebootlistpopup

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.util.readBootGuardStatus
import me.weishu.kernelsu.ui.util.reboot
import me.weishu.kernelsu.ui.util.resumeBootGuard

data class RebootListOption(
    @param:StringRes val labelRes: Int,
    val reason: String,
)

/** 不是真的 reboot reason：选中它表示"先退出救援模式再重启"。 */
const val RESCUE_EXIT_REASON = "boot_guard_resume"

@Composable
fun getRebootListOption(): List<RebootListOption> {
    val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?

    @Suppress("DEPRECATION")
    val isRebootingUserspaceSupported = pm?.isRebootingUserspaceSupported == true

    // 救援模式是"能开机但组件被跳过"，不知道的人会当成刷机失败/Zygisk 坏了。
    // 这里把它做成重启列表里的显式出口；读不到状态（旧守护进程/无 root）就不显示。
    val rescue by produceState(initialValue = false) {
        value = Natives.isManager && runCatching { readBootGuardStatus().rescue }.getOrDefault(false)
    }

    return buildList {
        // 出口放在最前：进入救援模式的用户最需要的就是先看到这一条。
        if (rescue) {
            add(RebootListOption(R.string.reboot_exit_rescue, RESCUE_EXIT_REASON))
        }
        add(RebootListOption(R.string.reboot, ""))
        if (isRebootingUserspaceSupported) {
            add(RebootListOption(R.string.reboot_userspace, "userspace"))
        }
        add(RebootListOption(R.string.reboot_soft, "soft_reboot"))
        add(RebootListOption(R.string.reboot_recovery, "recovery"))
        add(RebootListOption(R.string.reboot_bootloader, "bootloader"))
        add(RebootListOption(R.string.reboot_download, "download"))
        add(RebootListOption(R.string.reboot_edl, "edl"))
    }
}

/** Reboots on selection, but confirms first in jailbreak mode where a plain reboot drops root. */
@Composable
fun rememberRebootAction(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.reboot)
    val message = stringResource(R.string.jailbreak_reboot_warning)
    val rescueTitle = stringResource(R.string.reboot_exit_rescue)
    val rescueMessage = stringResource(R.string.rescue_exit_warning)
    val rescueFailed = stringResource(R.string.rescue_exit_failed)
    val confirmDialog = rememberConfirmDialog(onConfirm = { reboot() })

    // 退出救援模式必须先由守护进程清状态，再完整重启；软重启不会重放被跳过的阶段。
    val rescueDialog = rememberConfirmDialog(onConfirm = {
        scope.launch {
            try {
                resumeBootGuard()
                reboot()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, rescueFailed, Toast.LENGTH_LONG).show()
            }
        }
    })

    return remember(title, message, confirmDialog, rescueTitle, rescueMessage, rescueDialog) {
        { reason ->
            when {
                reason == RESCUE_EXIT_REASON -> rescueDialog.showConfirm(
                    title = rescueTitle,
                    content = rescueMessage,
                    confirm = rescueTitle,
                )

                Natives.isLateLoadMode && reason.isEmpty() -> confirmDialog.showConfirm(
                    title = title,
                    content = message,
                )

                else -> reboot(reason)
            }
        }
    }
}

@Composable
fun RebootListPopup() {
    RebootListPopupMiuix()
}

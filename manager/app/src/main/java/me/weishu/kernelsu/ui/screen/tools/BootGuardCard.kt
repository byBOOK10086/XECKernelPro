package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.util.BootGuardStatus
import me.weishu.kernelsu.ui.util.readBootGuardStatus
import me.weishu.kernelsu.ui.util.resumeBootGuard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
internal fun BootGuardCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BootGuardStatus?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var resumeFailed by remember { mutableStateOf(false) }
    var rebootRequired by rememberSaveable { mutableStateOf(false) }

    suspend fun refresh() {
        busy = true
        failed = false
        try {
            status = readBootGuardStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = null
            failed = true
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val confirm = rememberConfirmDialog(onConfirm = {
        if (!busy && !rebootRequired) {
            busy = true
            resumeFailed = false
            scope.launch {
                try {
                    resumeBootGuard()
                    rebootRequired = true
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    resumeFailed = true
                } finally {
                    busy = false
                }
            }
        }
    })

    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.boot_guard_title), color = colorScheme.onBackground)
            Text(
                stringResource(R.string.boot_guard_summary),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            status?.let { state ->
                val mode = stringResource(
                    if (state.rescue) R.string.boot_guard_rescue
                    else if (state.enabled) R.string.boot_guard_monitoring
                    else R.string.boot_guard_disabled,
                )
                Text(stringResource(R.string.boot_guard_state, mode), fontSize = 14.sp)
                Text(stringResource(R.string.boot_guard_enabled,
                    stringResource(if (state.enabled) R.string.boot_guard_yes else R.string.boot_guard_no)), fontSize = 13.sp)
                Text(stringResource(R.string.boot_guard_failures, state.failures), fontSize = 13.sp)
                val none = stringResource(R.string.boot_guard_none)
                Text(stringResource(R.string.boot_guard_stage, state.stage.ifBlank { none }), fontSize = 13.sp)
                Text(stringResource(R.string.boot_guard_reason, state.reason.ifBlank { none }), fontSize = 13.sp)
                Text(stringResource(R.string.boot_guard_skipped, state.skipped.joinToString(", ").ifBlank { none }), fontSize = 13.sp)
                Text(stringResource(R.string.boot_guard_last_recovery, state.lastRecovery.ifBlank { none }), fontSize = 13.sp)
            }
            if (busy) Text(stringResource(R.string.boot_guard_loading), fontSize = 13.sp)
            if (failed) Text(stringResource(R.string.boot_guard_unavailable), fontSize = 13.sp)
            if (resumeFailed) Text(stringResource(R.string.boot_guard_resume_failed), fontSize = 13.sp)
            if (rebootRequired) Text(
                stringResource(R.string.boot_guard_reboot_required),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(
                text = stringResource(R.string.boot_guard_refresh),
                enabled = !busy,
                onClick = { scope.launch { refresh() } },
            )
            TextButton(
                text = stringResource(R.string.boot_guard_resume),
                enabled = !busy && !rebootRequired && status?.let { it.rescue || it.failures > 0 } == true,
                onClick = {
                    confirm.showConfirm(
                        title = context.getString(R.string.boot_guard_resume),
                        content = context.getString(R.string.boot_guard_resume_warning),
                        confirm = context.getString(R.string.boot_guard_resume),
                    )
                },
            )
        }
    }
}

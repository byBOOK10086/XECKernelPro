package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.util.applyTeeEngineSwitch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun VendorKeyDialog(onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<VendorKeyDiagnostics.Report?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { report = VendorKeyDiagnostics.inspect() } finally { busy = false }
    }
    XDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        XDialogTitle(text = stringResource(R.string.vendor_keys_title))
        Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.vendor_keys_limits))
            report?.let { snapshot ->
                Text(snapshot.device)
                snapshot.services.forEach { (name, present) ->
                    val state = when (present) {
                        true -> R.string.vendor_keys_service_present
                        false -> R.string.vendor_keys_service_absent
                        null -> R.string.vendor_keys_service_unknown
                    }
                    Text("$name: ${stringResource(state)}")
                }
            }
            Text(stringResource(R.string.vendor_keys_recovery))
            result?.let { Text(stringResource(it)) }
            if (confirm) Text(stringResource(R.string.vendor_keys_disable_warning))
            TextButton(
                enabled = !busy && result != R.string.vendor_keys_disabled,
                text = stringResource(if (confirm) R.string.vendor_keys_disable_confirm else R.string.vendor_keys_disable),
                onClick = {
                    if (!confirm) confirm = true else {
                        busy = true
                        scope.launch {
                            try {
                                result = if (withContext(Dispatchers.IO) { applyTeeEngineSwitch(false) })
                                    R.string.vendor_keys_disabled else R.string.vendor_keys_disable_failed
                            } finally { busy = false; confirm = false }
                        }
                    }
                },
            )
            TextButton(text = stringResource(android.R.string.cancel), enabled = !busy, onClick = onDismiss)
        }
    }
}

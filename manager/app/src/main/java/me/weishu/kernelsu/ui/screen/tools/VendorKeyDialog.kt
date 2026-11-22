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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun VendorKeyDialog(onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<VendorKeyDiagnostics.Report?>(null) }
    LaunchedEffect(Unit) { report = VendorKeyDiagnostics.inspect() }

    XDialog(show = true, onDismissRequest = onDismiss) {
        XDialogTitle(text = stringResource(R.string.vendor_keys_title))
        Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.vendor_keys_limits))
            val snapshot = report
            if (snapshot == null) {
                Text(stringResource(R.string.vendor_keys_checking))
            } else {
                Text(snapshot.device)
                Text(stringResource(R.string.vendor_keys_build, snapshot.build))
                snapshot.services.forEach { (name, visibility) ->
                    val state = when (visibility) {
                        VendorKeyDiagnostics.Visibility.LISTED -> R.string.vendor_keys_service_present
                        VendorKeyDiagnostics.Visibility.NOT_LISTED -> R.string.vendor_keys_service_absent
                        VendorKeyDiagnostics.Visibility.UNKNOWN -> R.string.vendor_keys_service_unknown
                    }
                    Text(stringResource(R.string.vendor_keys_service_row, name, stringResource(state)))
                }
            }
            Text(stringResource(R.string.vendor_keys_recovery))
            TextButton(text = stringResource(android.R.string.ok), onClick = onDismiss)
        }
    }
}

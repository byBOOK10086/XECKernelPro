package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.ThemeText
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun ThemeTextEditor(state: ThemeUiState, actions: ThemeActions) {
    val spec = state.editor.spec ?: return
    val context = LocalContext.current
    var catalog by remember { mutableStateOf(emptyList<ThemeText.Entry>()) }
    var open by remember(spec.id) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember(spec.id) { mutableStateOf<ThemeText.Entry?>(null) }
    var replacement by remember { mutableStateOf("") }
    LaunchedEffect(context, open) {
        if (open) catalog = withContext(Dispatchers.IO) { ThemeText.catalog(context) }
    }
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.theme_editor_font_title), style = Xc.typography.section)
        Text(state.editor.fontLabel ?: stringResource(R.string.theme_editor_font_default), style = Xc.typography.caption)
        Text(stringResource(R.string.theme_editor_font_hint), style = Xc.typography.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.theme_editor_font_pick), onClick = actions.onPickFont, enabled = !state.busy)
            TextButton(stringResource(R.string.theme_editor_text_reset), onClick = actions.onClearFont,
                enabled = !state.busy && spec.globalFont != null)
        }
        Text(stringResource(R.string.theme_editor_text_title), style = Xc.typography.section)
        Text(stringResource(R.string.theme_editor_text_hint), style = Xc.typography.caption)
        TextButton(stringResource(R.string.theme_editor_text_edit, spec.textOverrides.size),
            onClick = { open = true }, enabled = !state.busy)
        TextButton(stringResource(R.string.theme_editor_text_reset_all), enabled = !state.busy && spec.textOverrides.isNotEmpty(),
            onClick = { spec.textOverrides.keys.forEach { actions.onSetText(it, null) } })
    }
    XDialog(show = open && selected == null, onDismissRequest = { open = false }) {
        XDialogTitle(stringResource(R.string.theme_editor_text_title))
        TextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.theme_editor_text_search))
        val filtered = remember(catalog, query, spec.textOverrides) {
            catalog.filter { query.isBlank() || it.original.contains(query, true) || it.key.contains(query, true) ||
                spec.textOverrides[it.key]?.contains(query, true) == true }
                .sortedByDescending { spec.textOverrides.containsKey(it.key) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(filtered, key = { it.key }) { entry ->
                Column(Modifier.fillMaxWidth().clickable {
                    selected = entry
                    replacement = spec.textOverrides[entry.key] ?: entry.original
                }.padding(vertical = 10.dp)) {
                    Text(spec.textOverrides[entry.key] ?: entry.original, maxLines = 2)
                    Text(entry.key, style = Xc.typography.caption, color = Xc.colors.textMuted)
                }
            }
        }
        TextButton(stringResource(android.R.string.cancel), onClick = { open = false })
    }
    XDialog(show = open && selected != null, onDismissRequest = { selected = null }) {
        selected?.let { entry ->
            XDialogTitle(stringResource(R.string.theme_editor_text_title), entry.key)
            Text(entry.original, style = Xc.typography.caption, maxLines = 4)
            TextField(value = replacement, onValueChange = { replacement = it.take(ThemeText.MAX_LENGTH) },
                label = stringResource(R.string.theme_editor_text_replacement))
            val valid = replacement.isNotBlank() && ThemeText.compatible(entry.original, replacement)
            if (!valid) Text(stringResource(R.string.theme_editor_text_invalid), color = Xc.colors.danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(android.R.string.cancel), onClick = { selected = null })
                TextButton(stringResource(R.string.theme_editor_text_reset), onClick = {
                    actions.onSetText(entry.key, null); selected = null
                })
                TextButton(stringResource(R.string.confirm), enabled = valid && !state.busy, onClick = {
                    actions.onSetText(entry.key, replacement); selected = null
                })
            }
        }
    }
}

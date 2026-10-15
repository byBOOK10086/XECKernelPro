package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.util.WallpaperStore
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** 预览图的解码上限：卡片里只有几十 dp，没必要按原图解。 */
private const val PREVIEW_MAX_EDGE = 256

/**
 * 「我的主题」页：当前主题 + 导入/新建 + 已装主题列表。
 *
 * 纯展示：所有动作都从 [ThemeActions] 走，这里不碰任何存储（按钮禁用只看 [ThemeUiState.busy]）。
 * 列表用 [LazyColumn] 而不是"一个 item 塞满"：主题数量由用户决定，几十个也要能滚得动。
 */
@Composable
fun MyThemesTab(
    state: ThemeUiState,
    actions: ThemeActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = null,
    ) {
        item(key = "active") {
            ActiveThemeCard(state = state, actions = actions, backdrop = backdrop)
        }
        item(key = "tools") {
            ToolsRow(state = state, actions = actions)
        }
        if (state.mine.isEmpty()) {
            item(key = "empty") {
                EmptyHint()
            }
        } else {
            items(items = state.mine, key = { it.spec.id }, contentType = { "theme" }) { entry ->
                ThemeEntryCard(
                    entry = entry,
                    active = entry.spec.id == state.activeId,
                    busy = state.busy,
                    actions = actions,
                    backdrop = backdrop,
                )
            }
        }
        item(key = "tail") {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 顶部卡片：当前用着什么。
 *
 * 「当前主题的名字」只认 [ThemeUiState.activeName]（它按 activeId 去 mine 里查），查不到就退回
 * id —— 主题目录被外部删掉时，界面不该说"当前是默认外观"（那会误导用户以为没生效）。
 */
@Composable
private fun ActiveThemeCard(
    state: ThemeUiState,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (state.activeId != null) {
                Text(
                    text = state.activeName ?: state.activeId,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = stringResource(R.string.themes_restore_default),
                    onClick = actions.onRestoreDefault,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_themes_default),
                    fontSize = 13.sp,
                    color = Xc.colors.textMuted,
                )
            }
        }
    }
}

/** 导入主题包 / 从零新建：这两件事是"我的主题"的入口，所以放在列表最上面。 */
@Composable
private fun ToolsRow(
    state: ThemeUiState,
    actions: ThemeActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = stringResource(R.string.themes_import),
            onClick = actions.onImport,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = stringResource(R.string.theme_editor_new),
            onClick = actions.onStartNew,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/**
 * 空态。
 *
 * 这里刻意不画大图标：主题页本来就该一眼看完"有没有东西"，多一张图只会让空列表看起来更像出错。
 */
@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.themes_empty),
            fontSize = 14.sp,
            color = Xc.colors.textMuted,
        )
    }
}

/**
 * 一个已装主题的卡片：预览图 + 元信息 + 色块 + 五枚动作。
 *
 * 动作排成三行而不是一行：`themes_export` / `themes_publish` 的文案在中文与英文下都不短，
 * 挤在一行里必然换行成两行高，反而比三行更难看。
 */
@Composable
private fun ThemeEntryCard(
    entry: ThemeRepository.Entry,
    active: Boolean,
    busy: Boolean,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    val spec = entry.spec
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ThemePreviewThumb(entry)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = spec.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Xc.colors.text,
                    )
                    if (spec.author.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.themes_by, spec.author),
                            fontSize = 12.sp,
                            color = Xc.colors.textSecondary,
                        )
                    }
                    Text(
                        text = "v" + spec.version,
                        fontSize = 11.sp,
                        color = Xc.colors.textMuted,
                    )
                    // 三个色块就是这张卡片的"外观摘要"：深色强调色 / 深色底 / 浅色底。
                    // 主题没写某个令牌时画一块描边空位，一眼能看出"这一档它没管"。
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ThemeSwatch(hex = spec.dark["accent"])
                        ThemeSwatch(hex = spec.dark["backdrop"])
                        ThemeSwatch(hex = spec.light["backdrop"])
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 已经在用的那个不给"应用"，改成灰掉的"已安装"——重复点它没有任何效果，
                // 让按钮不可点比让它可点但什么都不发生更诚实。
                TextButton(
                    text = stringResource(
                        if (active) R.string.themes_installed else R.string.themes_apply
                    ),
                    onClick = { actions.onApply(spec.id) },
                    enabled = !busy && !active,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.theme_editor_title),
                    onClick = { actions.onEditFrom(spec.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.themes_export),
                    onClick = { actions.onExport(spec.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.themes_publish),
                    onClick = { actions.onPublish(spec.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.themes_delete),
                onClick = { confirmDelete = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 删除是破坏性的：先问一句。文案用 themes_delete_confirm，它已经说清了
    // "包会被删掉、已经应用的背景图会留下来"这条容易误解的语义。
    XDialog(show = confirmDelete, onDismissRequest = { confirmDelete = false }) {
        XDialogTitle(
            text = stringResource(R.string.themes_delete),
            summary = stringResource(R.string.themes_delete_confirm, spec.name),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { confirmDelete = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.themes_delete),
                onClick = {
                    confirmDelete = false
                    actions.onDelete(spec.id)
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 预览图（`preview.jpg`，没有就不画）。
 *
 * 解码走 [WallpaperStore.decode] —— 这是本仓库里既有的"文件 → Bitmap → `asImageBitmap`"链路
 * （`WallpaperScreenMiuix` 的缩略图同款）。整个读取与解码都在 IO 线程：`ThemeRepository.previewFile`
 * 内部要读一遍 `theme.json`，绝不能落在组合里。
 */
@Composable
private fun ThemePreviewThumb(entry: ThemeRepository.Entry) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, entry.spec.id) {
        value = withContext(Dispatchers.IO) {
            val file = ThemeRepository.previewFile(context, entry.spec.id) ?: return@withContext null
            WallpaperStore.decode(file, PREVIEW_MAX_EDGE)?.asImageBitmap()
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = Modifier
                .size(width = 72.dp, height = 56.dp)
                .clip(Xc.shapes.sm),
            contentScale = ContentScale.Crop,
        )
    }
}

/** 一枚小色块：解析不出颜色时画一块描边的空位，而不是留白（留白会被读成"卡片缺了一块"）。 */
@Composable
private fun ThemeSwatch(
    hex: String?,
    size: Dp = 18.dp,
) {
    val argb = ThemeCodec.parseColor(hex)
    Box(
        modifier = Modifier
            .size(size)
            .clip(Xc.shapes.xs)
            .background(argb?.let { Color(it) } ?: Xc.colors.surfaceMuted.copy(alpha = 0.55f))
            .border(0.5.dp, Xc.colors.glassRim, Xc.shapes.xs),
    )
}

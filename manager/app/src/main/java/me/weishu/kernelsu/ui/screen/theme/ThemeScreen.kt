package me.weishu.kernelsu.ui.screen.theme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.viewmodel.ThemeViewModel

/**
 * 主题屏：商城 / 我的 / 编辑器三页合一。
 *
 * 逻辑全在 [ThemeViewModel]；这个文件只做三件事：把系统能力接上（SAF 选文件/存文件、
 * 相册选背景图、剪贴板、Toast）、把 [ThemeActions] 拼出来、把界面交给
 * [ThemeScreenMiuix]。
 *
 * 为什么导出/导入走 SAF 而不是自己写公共目录：
 *  * 清单里没有任何存储权限（`AndroidManifest.xml`），写公共目录会直接失败；
 *  * 主题包是用户自己的产物，让系统文件选择器决定存哪儿，卸载应用也不会留下残留。
 */
@Composable
fun ThemeScreen() {
    val viewModel = viewModel<ThemeViewModel>()
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    // 导出：SAF 需要先知道要导出哪个主题，回调里再写字节。
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    var pendingExportName by remember { mutableStateOf("theme.xectheme") }
    // 后台槽位：true = 浅色档。
    var pendingBackgroundLight by remember { mutableStateOf<Boolean?>(null) }
    // 正在等相册回调的区块 id（区块六行共用同一个系统相册启动器）。
    var pendingBlockImage by remember { mutableStateOf<String?>(null) }
    // 发布成功后拿到直链，弹一个"已提交 + 直链"的对话框。
    var publishLink by remember { mutableStateOf<String?>(null) }
    // 商城页顶部「发布我的主题」打开的选主题对话框。
    var publishPicker by remember { mutableStateOf(false) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { viewModel.exportBytes(id) }
            if (bytes == null) {
                toast(context.getString(R.string.themes_export_failed, "empty"))
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("cannot open output")
                }.isSuccess
            }
            toast(
                if (ok) {
                    context.getString(R.string.themes_export_done, pendingExportName)
                } else {
                    context.getString(R.string.themes_export_failed, "write failed")
                }
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                toast(context.getString(R.string.themes_import_failed, "read failed"))
                return@launch
            }
            viewModel.importPackage(bytes) { ok, message ->
                toast(
                    if (ok) context.getString(R.string.themes_import_done, message.orEmpty())
                    else context.getString(R.string.themes_import_failed, message.orEmpty())
                )
            }
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val light = pendingBackgroundLight
        pendingBackgroundLight = null
        if (uri == null || light == null) return@rememberLauncherForActivityResult
        viewModel.pickBackground(isDark = !light, uri = uri)
    }
    // 区块背景图：与背景图那条完全同款（同一个 PickVisualMedia），只是回调要知道是哪一块。
    val blockImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val blockId = pendingBlockImage
        pendingBlockImage = null
        if (uri == null || blockId == null) return@rememberLauncherForActivityResult
        viewModel.pickBlockImage(blockId, uri)
    }
    // 系统照片选择器：不需要任何存储权限，拿到的只是一次性的读权限 URI。
    val imageOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }

    val actions = ThemeActions(
        onBack = { navigator.pop() },
        onSelectTab = viewModel::selectTab,
        onRefreshStore = viewModel::loadStore,
        onSearch = viewModel::search,
        onDownload = viewModel::download,
        onApply = { id ->
            viewModel.apply(id) { ok, message ->
                toast(
                    if (ok) context.getString(R.string.themes_applied, id)
                    else context.getString(R.string.themes_apply_failed, message.orEmpty())
                )
            }
        },
        onRestoreDefault = {
            viewModel.restoreDefault { ok ->
                if (ok) toast(context.getString(R.string.themes_restored))
            }
        },
        onDelete = viewModel::delete,
        onExport = { id ->
            pendingExportId = id
            pendingExportName = viewModel.exportFile(id) ?: "theme.xectheme"
            exportLauncher.launch(pendingExportName)
        },
        onImport = {
            // 主题包就是 zip：给一个宽松的 mime 集合，别让某些文件管理器灰掉文件。
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        },
        onStartNew = viewModel::startNew,
        onEditFrom = viewModel::editFrom,
        onEditMeta = { name, id, author, version, summary, description ->
            viewModel.editMeta(name, id, author, version, summary, description)
        },
        onSetColor = viewModel::setColor,
        onResetColor = viewModel::resetColor,
        onSetShape = viewModel::setShape,
        onSetGlass = viewModel::setGlass,
        onSetNeon = viewModel::setNeon,
        onResetNeon = viewModel::resetNeon,
        onPickBackground = { isDark ->
            pendingBackgroundLight = !isDark
            backgroundLauncher.launch(imageOnly)
        },
        onClearBackground = { isDark -> viewModel.clearBackground(isDark) },
        onSetBlockColor = viewModel::setBlockColor,
        onClearBlockColor = viewModel::clearBlockColor,
        onPickBlockImage = { id ->
            pendingBlockImage = id
            blockImageLauncher.launch(imageOnly)
        },
        onClearBlockImage = viewModel::clearBlockImage,
        onSetBlockNumber = viewModel::setBlockNumber,
        onSetBlockWhen = viewModel::setBlockWhen,
        onResetBlock = viewModel::resetBlock,
        onSaveEditor = {
            val problem = viewModel.validateEditor()
            when (problem) {
                "id" -> toast(context.getString(R.string.theme_editor_invalid_id))
                "name" -> toast(context.getString(R.string.theme_editor_name_required))
                "empty" -> Unit
                else -> viewModel.saveEditor { ok, message ->
                    toast(
                        if (ok) context.getString(R.string.theme_editor_saved, message.orEmpty())
                        else context.getString(R.string.theme_editor_save_failed, message.orEmpty())
                    )
                }
            }
        },
        onPublish = { id ->
            viewModel.publish(id) { ok, message ->
                if (ok) {
                    publishLink = message
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("xectheme", message.orEmpty()))
                } else {
                    toast(context.getString(R.string.themes_publish_failed, message.orEmpty()))
                }
            }
        },
        onOpenPublishPicker = { publishPicker = true },
    )

    ThemeScreenMiuix(
        state = state,
        actions = actions,
        publishLink = publishLink,
        onDismissPublish = { publishLink = null },
        publishPicker = publishPicker,
        onDismissPublishPicker = { publishPicker = false },
    )
}

package me.weishu.kernelsu.ui.screen.wallpaper

import android.net.Uri
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
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.WallpaperStore
import me.weishu.kernelsu.ui.viewmodel.SettingsViewModel

/**
 * 自定义背景页。
 *
 * 选图走系统的照片选择器（[ActivityResultContracts.PickVisualMedia]）：它不需要任何存储权限，
 * 也不会把整个相册暴露给应用——拿到的只是一次性的读权限 URI，读完即弃。
 *
 * 导入流程刻意"先落盘、再改设置"：任何一步失败（解码失败、编码失败、进程被杀）最坏结果都是
 * 继续用内置池；反过来先改设置的话，会留下一个指向不存在文件的设置项，表现为"背景没了"。
 */
@Composable
fun WallpaperScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 正在导入的槽位；null = 空闲。用它禁用按钮并给出"正在处理"提示，
    // 避免用户连点两次、或者在大图解码期间以为没反应。
    var busySlot by remember { mutableStateOf<String?>(null) }

    val importWallpaper: (String, Uri) -> Unit = { slot, uri ->
        busySlot = slot
        scope.launch {
            val result = runCatching { WallpaperStore.import(context, uri, slot) }
            busySlot = null
            result
                .onSuccess { name ->
                    if (slot == WallpaperStore.LIGHT) {
                        viewModel.setWallpaperLight(name)
                    } else {
                        viewModel.setWallpaperDark(name)
                    }
                    Toast.makeText(context, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    Toast.makeText(
                        context,
                        context.getString(R.string.wallpaper_import_failed, reason),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    val lightPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importWallpaper(WallpaperStore.LIGHT, uri)
    }
    val darkPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importWallpaper(WallpaperStore.DARK, uri)
    }
    val imageOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }

    WallpaperScreenMiuix(
        uiState = uiState,
        busySlot = busySlot,
        onBack = dropUnlessResumed { navigator.pop() },
        onPickLight = { lightPicker.launch(imageOnly) },
        onPickDark = { darkPicker.launch(imageOnly) },
        onClearLight = {
            val previous = uiState.wallpaperLight
            viewModel.setWallpaperLight("")
            WallpaperStore.discard(context, previous)
        },
        onClearDark = {
            val previous = uiState.wallpaperDark
            viewModel.setWallpaperDark("")
            WallpaperStore.discard(context, previous)
        },
        onClearAll = {
            viewModel.setWallpaperLight("")
            viewModel.setWallpaperDark("")
            WallpaperStore.clearAll(context)
        },
    )
}

package me.weishu.kernelsu.ui.screen.hmaoss

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.awaitRootShell
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val HMA_PACKAGE = "org.frknkrc44.hma_oss"
private const val HMA_MODULE_ID = "hma_oss_zygisk"
private const val REZYGISK_MODULE_ID = "rezygisk"

@Composable
fun HmaOssScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val repository = remember { ModuleRepositoryImpl() }
    val scrollBehavior = MiuixScrollBehavior()
    var checkingApp by remember { mutableStateOf(true) }
    var appInfo by remember { mutableStateOf<PackageInfo?>(null) }
    var checkingModules by remember { mutableStateOf(true) }
    var modules by remember { mutableStateOf<List<Module>?>(null) }

    LifecycleResumeEffect(context) {
        val refresh = scope.launch {
            appInfo = withContext(Dispatchers.IO) { context.packageManager.findHmaApp() }
            checkingApp = false

            val hasRoot = awaitRootShell(timeoutMs = 2_500L).isRoot
            modules = if (hasRoot) repository.getModules().getOrNull() else null
            checkingModules = false
        }
        onPauseOrDispose { refresh.cancel() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.hma_oss_title),
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.remote_hub_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 22.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.hma_oss_app),
                            subtitle = if (checkingApp) {
                                stringResource(R.string.hma_oss_checking)
                            } else {
                                appInfo?.run {
                                    versionName?.takeIf { it.isNotBlank() } ?: longVersionCode.toString()
                                } ?: stringResource(R.string.hma_oss_app_missing)
                            },
                        )
                        ModuleStatusRow(
                            title = stringResource(R.string.hma_oss_rezygisk_module),
                            id = REZYGISK_MODULE_ID,
                            modules = modules,
                            checking = checkingModules,
                        )
                        ModuleStatusRow(
                            title = stringResource(R.string.hma_oss_module),
                            id = HMA_MODULE_ID,
                            modules = modules,
                            checking = checkingModules,
                        )
                        if (appInfo != null) {
                            Xc8Row(
                                title = stringResource(R.string.hma_oss_open),
                                leading = Icons.AutoMirrored.Rounded.OpenInNew,
                                trailing = { Xc8Chevron() },
                                onClick = {
                                    runCatching {
                                        val intent = checkNotNull(
                                            context.packageManager.getLaunchIntentForPackage(HMA_PACKAGE),
                                        )
                                        context.startActivity(intent)
                                    }.onFailure {
                                        Toast.makeText(
                                            context, R.string.hma_oss_app_open_failed, Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.findHmaApp(): PackageInfo? =
    try {
        getPackageInfo(HMA_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

@Composable
private fun ModuleStatusRow(title: String, id: String, modules: List<Module>?, checking: Boolean) {
    val installed = modules?.firstOrNull { it.id == id }
    val status = stringResource(
        when {
            checking -> R.string.hma_oss_checking
            modules == null -> R.string.hma_oss_module_unavailable
            installed == null -> R.string.hma_oss_module_not_found
            installed.remove -> R.string.hma_oss_remove_pending
            installed.update -> R.string.hma_oss_update_pending
            installed.enabled -> R.string.hma_oss_enabled
            else -> R.string.hma_oss_disabled
        },
    )
    val version = installed?.version?.takeIf(String::isNotBlank)
    Xc8Row(
        title = title,
        subtitle = version?.let {
            stringResource(R.string.hma_oss_module_status_with_version, it, status)
        } ?: status,
    )
}

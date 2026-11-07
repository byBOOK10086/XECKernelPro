package me.weishu.kernelsu.ui.screen.extmodules

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.DownloadManager
import me.weishu.kernelsu.ui.util.download
import me.weishu.kernelsu.ui.util.flashModule
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.rootAvailable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import me.weishu.kernelsu.ui.design.token.XcRadius

/*
 * 扩展模块：一份诚实的可选附件清单。
 *
 * 两类条目严格分开：
 *  - TEE / TA 是本项目自己构建并分发的 GPL-3.0 模块，管理器直接把 zip 装进设备；
 *  - Zygisk（ReZygisk）是第三方 AGPL-3.0-or-later 项目，**只跳转**到上游 Releases，
 *    不内置、不镜像、不转发。这条界线不能因为「装起来更顺手」被抹掉。
 */

private const val TEE_MODULE_URL =
    "https://github.com/byBOOK10086/XECKernelPro/releases/latest/download/XECKernelPro-TEE-module.zip"
private const val TEE_MODULE_FILE = "XECKernelPro-TEE-module.zip"

private const val TA_MODULE_URL =
    "https://github.com/byBOOK10086/XECKernelPro/releases/latest/download/XECKernelPro-TA-module.zip"
private const val TA_MODULE_FILE = "XECKernelPro-TA-module.zip"

/** 上游 Releases 页，不是资产直链：版本、说明、校验和都由项目作者自己发布。 */
private const val REZYGISK_RELEASES_URL = "https://github.com/PerformanC/ReZygisk/releases/latest"

private class ExternalModule(
    val titleRes: Int,
    val summaryRes: Int,
    val sourceRes: Int,
    val url: String,
    val fileName: String = "",
)

/** 本项目构建、可直接安装的模块。 */
private val INSTALLABLE_MODULES = listOf(
    ExternalModule(
        titleRes = R.string.ext_modules_tee_title,
        summaryRes = R.string.ext_modules_tee_summary,
        sourceRes = R.string.ext_modules_source_own,
        url = TEE_MODULE_URL,
        fileName = TEE_MODULE_FILE,
    ),
    ExternalModule(
        titleRes = R.string.ext_modules_ta_title,
        summaryRes = R.string.ext_modules_ta_summary,
        sourceRes = R.string.ext_modules_source_own,
        url = TA_MODULE_URL,
        fileName = TA_MODULE_FILE,
    ),
)

/** 第三方条目：只有跳转，没有下载/安装路径。 */
private val LINK_ONLY_MODULE = ExternalModule(
    titleRes = R.string.ext_modules_zygisk_title,
    summaryRes = R.string.ext_modules_zygisk_summary,
    sourceRes = R.string.ext_modules_source_third_party,
    url = REZYGISK_RELEASES_URL,
)

/** 每张安装卡片自己的运行态；提到屏幕层，滚动离屏时任务不会被取消。 */
private class ModuleInstallState {
    var isCheckingRoot by mutableStateOf(false)
    var isDownloading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var isInstalling by mutableStateOf(false)
    var downloadedUri by mutableStateOf<Uri?>(null)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)
}

@Composable
fun ExternalModulesScreen() {
    val navigator = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scope = rememberCoroutineScope()
    val installStates = remember {
        INSTALLABLE_MODULES.associate { it.fileName to ModuleInstallState() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.ext_modules_title),
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            item {
                Text(
                    text = stringResource(R.string.ext_modules_intro),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            item {
                SmallTitle(text = stringResource(R.string.ext_modules_section_own))
            }
            items(INSTALLABLE_MODULES, key = { it.fileName }) { module ->
                InstallableModuleCard(
                    module = module,
                    state = installStates.getValue(module.fileName),
                    scope = scope,
                    backdrop = backdrop,
                )
            }
            item {
                SmallTitle(text = stringResource(R.string.ext_modules_section_third_party))
            }
            item {
                LinkOnlyModuleCard(module = LINK_ONLY_MODULE, backdrop = backdrop)
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InstallableModuleCard(
    module: ExternalModule,
    state: ModuleInstallState,
    scope: CoroutineScope,
    backdrop: LayerBackdrop?,
) {
    val context = LocalContext.current
    // 状态文案在组合期取，点击/协程里只用 context.getString；避免在非组合函数里调用 stringResource。
    val statusText = when {
        state.isCheckingRoot -> stringResource(R.string.ext_modules_checking_root)
        state.isDownloading -> stringResource(R.string.ext_modules_downloading, state.progress)
        state.isInstalling -> stringResource(R.string.ext_modules_installing)
        else -> state.message
    }
    val showProgress = state.isDownloading && !state.isCheckingRoot && !state.isInstalling
    val statusColor = if (state.isError && !showProgress) {
        colorScheme.error
    } else {
        colorScheme.onSurfaceVariantSummary
    }

    Card(cornerRadius = XcRadius.md, 
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            ModuleHeader(module = module, installable = true)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(module.sourceRes),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (statusText != null) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showProgress) {
                        CircularProgressIndicator(
                            progress = state.progress / 100f,
                            size = 18.dp,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                text = if (state.downloadedUri != null) {
                    stringResource(R.string.ext_modules_install_again)
                } else {
                    stringResource(R.string.ext_modules_download_and_install)
                },
                enabled = !state.isCheckingRoot && !state.isDownloading && !state.isInstalling,
                onClick = { startDownloadAndInstall(module, state, scope, context) },
            )
        }
    }
}

@Composable
private fun LinkOnlyModuleCard(
    module: ExternalModule,
    backdrop: LayerBackdrop?,
) {
    val context = LocalContext.current
    var linkFailed by remember { mutableStateOf(false) }

    Card(cornerRadius = XcRadius.md, 
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            ModuleHeader(module = module, installable = false)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(module.sourceRes),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(
                text = stringResource(R.string.ext_modules_open_releases),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(module.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    linkFailed = !runCatching { context.startActivity(intent) }.isSuccess
                },
            )
            if (linkFailed) {
                Text(
                    text = stringResource(R.string.ext_modules_no_browser),
                    fontSize = 12.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModuleHeader(module: ExternalModule, installable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(module.titleRes),
            fontSize = 17.sp,
            fontWeight = FontWeight(550),
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (installable) R.string.ext_modules_kind_install else R.string.ext_modules_kind_link
            ),
            fontSize = 11.sp,
            color = if (installable) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(module.summaryRes),
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 下载 + 安装一张卡片。这里只复用 [download] 与 [flashModule] 两条既有通路：
 * 前者负责网络与通知栏进度，后者内部已经区分内核态 ksud / 挂载态 Magisk，这里不再判路线。
 */
private fun startDownloadAndInstall(
    module: ExternalModule,
    state: ModuleInstallState,
    scope: CoroutineScope,
    context: Context,
) {
    if (state.isCheckingRoot || state.isDownloading || state.isInstalling) return
    // 同步置位：点击事件到协程真正开始之间还有一帧，双击不能起两个下载/安装。
    state.isCheckingRoot = true
    scope.launch {
        state.isError = false
        state.message = null
        val hasRoot = withContext(Dispatchers.IO) {
            runCatching { rootAvailable() }.getOrDefault(false)
        }
        state.isCheckingRoot = false
        if (!hasRoot) {
            state.isError = true
            state.message = context.getString(R.string.ext_modules_root_required)
            return@launch
        }

        // 上一次安装失败时保留下载结果：重试只重装，不再重新下载一遍。
        val uri = state.downloadedUri ?: downloadModule(module, state)
        if (uri == null) {
            state.isError = true
            state.message = context.getString(R.string.ext_modules_download_failed)
            return@launch
        }
        state.downloadedUri = uri

        state.isInstalling = true
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                flashModule(uri, onStdout = {}, onStderr = {})
            }
        }
        state.isInstalling = false

        val result = outcome.getOrNull()
        if (result != null && result.code == 0) {
            state.downloadedUri = null
            state.progress = 0
            state.isError = false
            state.message = context.getString(R.string.ext_modules_install_ok)
            return@launch
        }
        val detail = outcome.exceptionOrNull()?.message
            ?: result?.err?.lineSequence()?.firstOrNull { it.isNotBlank() }
        state.isError = true
        state.message = if (detail.isNullOrBlank()) {
            context.getString(R.string.ext_modules_install_failed)
        } else {
            context.getString(R.string.ext_modules_install_failed_detail, detail.take(240))
        }
    }
}

/**
 * 调用既有下载器并等待它落到终态。
 *
 * [download] 失败时既不回调 onDownloaded 也不抛异常，所以完成状态以
 * [DownloadManager] 里同文件名的最后一条记录为准：COMPLETED 才算拿到 Uri，
 * FAILED / 记录缺失都按失败上报，绝不让界面停在“正在下载”而没有下文。
 */
private suspend fun downloadModule(
    module: ExternalModule,
    state: ModuleInstallState,
): Uri? {
    state.isDownloading = true
    state.progress = 0
    val started = runCatching {
        download(
            url = module.url,
            fileName = module.fileName,
            onProgress = { value -> state.progress = value },
        )
    }.isSuccess
    state.isDownloading = false
    if (!started) return null

    val latest = DownloadManager.downloads.value.values
        .filter { it.fileName == module.fileName }
        .maxByOrNull { it.id }
    return latest
        ?.takeIf { it.status == DownloadManager.Status.COMPLETED }
        ?.resultUri
}

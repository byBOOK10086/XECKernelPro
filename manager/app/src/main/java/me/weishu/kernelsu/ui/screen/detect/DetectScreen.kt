package me.weishu.kernelsu.ui.screen.detect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcNeon
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.withNewRootShell
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.io.File

/**
 * 检测页：内置验机工具（密钥链有效性 / bootloader 状态 / 环境异常）。
 *
 * APK 打包在 assets 内，root 静默安装（管理器自身即 root，不走系统安装器）；
 * 打开走显式组件名，失败回落 monkey LAUNCHER。
 */
private const val DETECT_PACKAGE = "wu.keyChain.test"
private const val DETECT_ACTIVITY = "wu.keyChain.test/com.eide.eideapp.RunnerActivity"
private const val DETECT_ASSET = "detect/keychain-check.apk"

@Composable
fun DetectPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var version by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val path = runCatching {
            withNewRootShell(true) {
                ShellUtils.fastCmd(this, "pm path $DETECT_PACKAGE 2>/dev/null")
            }
        }.getOrDefault("").trim()
        installed = path.isNotEmpty()
        if (installed == true) {
            version = runCatching {
                withNewRootShell(true) {
                    ShellUtils.fastCmd(this, "dumpsys package $DETECT_PACKAGE | grep versionName")
                }
            }.getOrDefault("").substringAfter("versionName=").trim()
        } else {
            version = ""
        }
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) refresh()
    }

    fun install() {
        if (busy) return
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "detect/keychain-check.apk")
                    tmp.parentFile?.mkdirs()
                    context.assets.open(DETECT_ASSET).use { input ->
                        tmp.outputStream().use(input::copyTo)
                    }
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, "pm install -r \"${tmp.absolutePath}\"")
                    }.contains("Success")
                }.getOrDefault(false)
            }
            busy = false
            toast(context.getString(if (ok) R.string.detect_install_done else R.string.detect_install_failed))
            if (ok) refresh()
        }
    }

    fun open() {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, "am start -n $DETECT_ACTIVITY 2>&1")
                    }
                }.getOrDefault("")
            }
            if (!out.contains("tarting")) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        withNewRootShell(true) {
                            ShellUtils.fastCmd(this, "monkey -p $DETECT_PACKAGE -c android.intent.category.LAUNCHER 1")
                        }
                    }
                }
            }
        }
    }

    fun uninstall() {
        if (busy) return
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    withNewRootShell(true) {
                        ShellUtils.fastCmd(this, "pm uninstall $DETECT_PACKAGE")
                    }.contains("Success")
                }.getOrDefault(false)
            }
            busy = false
            toast(context.getString(if (ok) R.string.detect_uninstall_done else R.string.detect_uninstall_failed))
            if (ok) refresh()
        }
    }

    DetectPagerMiuix(
        installed = installed,
        version = version,
        busy = busy,
        bottomInnerPadding = bottomInnerPadding,
        onInstall = ::install,
        onOpen = ::open,
        onUninstall = ::uninstall,
    )
}

@Composable
private fun DetectPagerMiuix(
    installed: Boolean?,
    version: String,
    busy: Boolean,
    bottomInnerPadding: Dp,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)
    val neon = XcNeon.colors

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.detection),
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
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 12.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.detect_app_name),
                        summary = when {
                            installed == null -> stringResource(R.string.detect_checking)
                            installed -> stringResource(R.string.detect_installed_fmt).format(version)
                            else -> stringResource(R.string.detect_not_installed)
                        },
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = null,
                                tint = if (installed == true) neon.accentCyan else neon.textSub,
                            )
                        },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                    onClick = onInstall,
                ) {
                    BasicComponent(
                        title = stringResource(if (installed == true) R.string.detect_reinstall else R.string.detect_install),
                        summary = if (busy) {
                            stringResource(R.string.detect_working)
                        } else {
                            stringResource(R.string.detect_install_hint)
                        },
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.GetApp,
                                contentDescription = null,
                                tint = colorScheme.primary,
                            )
                        },
                    )
                }
            }

            if (installed == true) {
                item {
                    Card(
                        modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                        onClick = onOpen,
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.detect_open),
                            summary = stringResource(R.string.detect_open_hint),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Launch,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                )
                            },
                        )
                    }
                }
                item {
                    Card(
                        modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                        onClick = onUninstall,
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.detect_uninstall),
                            summary = stringResource(R.string.detect_uninstall_hint),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteForever,
                                    contentDescription = null,
                                    tint = neon.danger,
                                )
                            },
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.detect_about_title),
                            fontSize = 15.sp,
                            color = neon.textMain,
                        )
                        Text(
                            text = stringResource(R.string.detect_about_body),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = neon.textSub,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                // 底部留白，避免最后一项贴到导航栏
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.padding(bottom = bottomInnerPadding),
                )
            }
        }
    }
}

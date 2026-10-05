package me.weishu.kernelsu.ui.screen.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 终端页拿不到页面时的原因。三种情况的提示文案完全不同，
 * 所以不能用同一个"连不上"糊过去。
 */
private sealed interface TerminalFailure {
    /** 模块目录里找不到 `autotrigger` 二进制（模块没装，或装到了非标准路径）。 */
    data object BinaryMissing : TerminalFailure

    /** 二进制找到了，但端口一直没监听（多半是一启动就崩）。 */
    data object NotListening : TerminalFailure

    /** 端口活着，但主文档加载失败（网页自己出错，或服务中途挂掉）。 */
    data class Page(val url: String) : TerminalFailure
}

/**
 * 终端页：打开即自动加载模块的网页管理界面（`http://127.0.0.1:2222`）。
 *
 * 这里刻意不复用 [me.weishu.kernelsu.ui.webui.WebUIActivity] 那一套：那套走
 * `WebViewAssetLoader` + `SuFilePathHandler`，要求模块有 `webroot` 目录并且页面
 * 通过 `window.ksu` 桥调用管理器；Auto_Trigger 两者都没有，它自己常驻一个 HTTP
 * 服务（`service.sh` 里 `autotrigger --port 2222`），所以这里只需要一个裸 WebView。
 *
 * 加载不是直接 `loadUrl` 常量，而是先问 [AutoTriggerDaemon]：模块的 `service.sh`
 * 只在开机时被 ksud 执行，刚刷完模块还没重启的那段时间端口一定是关的。管理器在这里
 * 自己去找二进制、补 0777、用 root 拉起来，等端口就绪之后再把地址交给 WebView，
 * 于是"没刷模块 / 刷了没重启"都不影响进终端页。
 *
 * `127.0.0.1` 的明文流量已经由 `network_security_config.xml` 放行，无需额外配置。
 */
@Composable
fun TerminalPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)

    // WebView 只能建在组合之外，所以用一个可观察引用把它交给下面的 effect。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<TerminalFailure?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // 自增即触发一次重载（重试按钮 / 顶栏刷新）。
    var reloadToken by remember { mutableIntStateOf(0) }

    // 打开终端页（成为当前页）时自动加载一次；离开后再回来会重新加载。
    LaunchedEffect(webView, isCurrentPage, reloadToken) {
        val view = webView ?: return@LaunchedEffect
        if (!isCurrentPage) return@LaunchedEffect
        isLoading = true
        failure = null
        // 先把模块里的二进制补 0777 拉起来（端口本来就在跑则直接返回），
        // 拿到地址再交给 WebView——这一步就是用户说的"把网址转发给浏览器"。
        when (val result = AutoTriggerDaemon.ensureRunning()) {
            is AutoTriggerDaemon.StartResult.Running -> view.loadUrl(result.url)
            AutoTriggerDaemon.StartResult.BinaryNotFound -> {
                isLoading = false
                failure = TerminalFailure.BinaryMissing
            }

            AutoTriggerDaemon.StartResult.NotListening -> {
                isLoading = false
                failure = TerminalFailure.NotListening
            }
        }
    }

    // 先走网页自己的历史，历史到底了再交还给 Pager（回首页）。
    // 终端页在非当前页时也可能被 Pager 预组合，所以必须再判一次 isCurrentPage，
    // 否则会隔着好几页把返回手势吃掉。
    BackHandler(enabled = isCurrentPage && canGoBack) {
        webView?.goBack()
    }

    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val target = view ?: return@onDispose
            // WebView 不会跟着 Compose 一起回收，必须自己销毁，否则每进一次终端页
            // 就多泄漏一个 WebView（含它自己的渲染进程）。
            runCatching {
                (target.parent as? ViewGroup)?.removeView(target)
                target.stopLoading()
                target.destroy()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.terminal),
                    actions = {
                        IconButton(onClick = { reloadToken++ }) {
                            MiuixIcon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 网页是被整块裁剪的，不像列表那样能靠尾部留白把内容顶上来，
                // 所以直接把可视区域让出底栏高度，免得底部控件永远压在玻璃条下面。
                .padding(bottom = bottomInnerPadding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createTerminalWebView(
                        context = ctx,
                        onPageStarted = { isLoading = true },
                        onPageFinished = { isLoading = false },
                        onMainFrameError = { failedUrl ->
                            isLoading = false
                            failure = TerminalFailure.Page(failedUrl)
                        },
                        onHistoryChanged = { canGoBack = it },
                    ).also { webView = it }
                },
                update = { view ->
                    view.requestLayout()
                },
            )

            when (val current = failure) {
                null -> if (isLoading) TerminalLoadingState()
                else -> TerminalErrorState(
                    message = when (current) {
                        TerminalFailure.BinaryMissing ->
                            stringResource(R.string.terminal_unavailable, AutoTriggerDaemon.URL)

                        TerminalFailure.NotListening -> stringResource(R.string.terminal_start_failed)
                        is TerminalFailure.Page ->
                            stringResource(R.string.terminal_unavailable, current.url)
                    },
                    hint = when (current) {
                        TerminalFailure.BinaryMissing -> stringResource(R.string.terminal_hint)
                        TerminalFailure.NotListening ->
                            stringResource(R.string.terminal_start_hint)

                        is TerminalFailure.Page -> stringResource(R.string.terminal_hint)
                    },
                    onRetry = { reloadToken++ },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createTerminalWebView(
    context: Context,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
    onMainFrameError: (String) -> Unit,
    onHistoryChanged: (Boolean) -> Unit,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // WebView 默认自带一层不透明白底，页面首帧之前会把下面的壁纸/玻璃整块盖住，
        // 看上去就是"打开终端先闪一块色板"。改成透明底，加载态和壁纸连成一片。
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 本地 HTTP 服务，不需要读本地文件。
            allowFileAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // 出错后系统会渲染自己的错误页并回调到这里，所以这里只收 loading，
                // 不能顺手清掉 failure，否则错误态会被自己的错误页覆盖掉。
                onPageFinished()
                onHistoryChanged(view.canGoBack())
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // 只认主文档失败；子资源（图标、接口轮询）失败不影响页面可用。
                if (!request.isForMainFrame) return
                onMainFrameError(request.url?.toString() ?: AutoTriggerDaemon.URL)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                onHistoryChanged(view.canGoBack())
            }
        }
    }
}

@Composable
private fun TerminalLoadingState() {
    TerminalStatusPanel {
        InfiniteProgressIndicator(color = Xc.colors.text)
        Text(
            text = stringResource(R.string.terminal_starting),
            color = Xc.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TerminalErrorState(
    message: String,
    hint: String,
    onRetry: () -> Unit,
) {
    TerminalStatusPanel {
        Text(
            text = message,
            color = Xc.colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = hint,
            color = Xc.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetry,
            text = stringResource(R.string.network_retry),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/**
 * 终端页的过渡态面板。
 *
 * 刻意**不铺满整屏**：以前这里是一块 `fillMaxSize()` 的不透明 surface，深色档下
 * 那就是一整块近黑的板子盖住整个内容区，看着就是个"无敌大黑框"。现在只居中放一枚
 * 贴着内容的圆角面板，壁纸从四周透出来，加载/出错都只是一张小卡片。
 */
@Composable
private fun TerminalStatusPanel(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .clip(Xc.shapes.lg)
                .background(Xc.colors.surface.copy(alpha = 1f))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

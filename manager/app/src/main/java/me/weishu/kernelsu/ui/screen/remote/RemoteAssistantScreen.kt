package me.weishu.kernelsu.ui.screen.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.flashModule
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

private const val REMOTE_DIR = "remote_modules"

/** `~` 锚点的兜底等待上限：提示一直没出现就跳过这一步，别把整条流水线挂死。 */
private const val PROMPT_TIMEOUT_MS = 20_000L

private data class DeployOutcome(val flashed: Int, val failedAt: String?)

private fun sanitizeName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module.zip" }

/**
 * 安装脚本的"要我按键"提示特征。
 *
 * 只有 `~` 锚点的步骤会用到它：脚本先说"请按音量上选择"，我们等这句话出现再按。
 * 静默 `sleep` 等待的脚本没有提示，那种情况由 `@` 绝对时间负责。
 */
private val PROMPT_HINT = Regex("(?i)(volume|音量|按键|按一下|press|vol\\s*[+-]|选择)")

/** 从 zip 里读 `module.prop` 的 id：按键脚本的"记忆"以它为主键。 */
private fun readModuleId(file: File): String? = runCatching {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("module.prop") ?: return@use null
        zip.getInputStream(entry).bufferedReader().useLines { lines ->
            lines.firstOrNull { it.trimStart().startsWith("id=") }
                ?.substringAfter('=')
                ?.trim()
        }
    }
}.getOrNull()

/**
 * 按键脚本的解析优先级（先命中先用）：
 *
 * 1. **发送端带的**（链接 fragment 里的 `#xec=`）——这是"发送端提前设定好"的落点，
 *    优先级最高，因为它就是这个模块的专用脚本；
 * 2. **接收端在本次任务里强制的模板**——用户临时对所有模块套一套；
 * 3. **记忆**（同一个 module id 上次用过的脚本）——全自动化的兜底：第一次之后，
 *    同一个模块再来就再也不用管。
 */
private class KeyPlanResolver(
    private val context: Context,
    private val forcedPlanText: String,
) {
    fun resolve(entry: RemoteEntry, moduleId: String?): Pair<RemoteKeyPlan, String> {
        entry.planText.takeIf { it.isNotBlank() }?.let {
            return RemoteKeyPlan.parse(it).getOrDefault(RemoteKeyPlan.EMPTY) to "发送端脚本"
        }
        if (forcedPlanText.isNotBlank()) {
            return RemoteKeyPlan.parse(forcedPlanText).getOrDefault(RemoteKeyPlan.EMPTY) to "本次模板"
        }
        val remembered = moduleId?.let { RemoteKeyPresets.load(context, it) }
        if (!remembered.isNullOrBlank()) {
            return RemoteKeyPlan.parse(remembered).getOrDefault(RemoteKeyPlan.EMPTY) to "上次记忆"
        }
        return RemoteKeyPlan.EMPTY to ""
    }

    /** 记住这次实际用的脚本，下次同一个模块自动套用。 */
    fun remember(moduleId: String?, plan: RemoteKeyPlan) {
        if (moduleId.isNullOrBlank() || !plan.isNotEmpty) return
        RemoteKeyPresets.save(context, moduleId, plan.format())
    }
}

/**
 * 按脚本注入按键，直到刷入结束被取消。
 *
 * 每按一次都把"相对刷入开始的毫秒数"写进日志：脚本的延迟是否合适一眼可查，
 * 不合适就直接改发送端的脚本，而不是靠猜。
 */
private suspend fun runKeyPlan(
    plan: RemoteKeyPlan,
    installStart: Long,
    promptCount: () -> Int,
    log: (String) -> Unit,
) {
    var consumedPrompts = 0
    for ((index, step) in plan.steps.withIndex()) {
        val label = "#${index + 1}/${plan.steps.size} ${step.key.label}"
        if (step.afterPrompt) {
            val deadline = SystemClock.elapsedRealtime() + PROMPT_TIMEOUT_MS
            while (promptCount() <= consumedPrompts && SystemClock.elapsedRealtime() < deadline) {
                delay(50)
            }
            if (promptCount() <= consumedPrompts) {
                log("  ⌨ $label 没等到按键提示，跳过（改用 @ 绝对时间更稳）")
                continue
            }
            consumedPrompts = promptCount()
            delay(step.delayMs.toLong())
        } else {
            val wait = installStart + step.delayMs - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
        }
        val device = RemoteKeyInjector.inject(step.key, step.holdMs)
        val elapsed = SystemClock.elapsedRealtime() - installStart
        if (device != null) {
            log("  ⌨ ${step.key.label} +${elapsed}ms → $device")
        } else {
            log("  ✗ ${step.key.label} 注入失败（没有可写的 input 节点）")
        }
    }
}

/**
 * 下载单个模块并按脚本刷入；返回 null 表示成功，否则返回失败步骤描述。
 *
 * 脚本在这里才真正解析：**模块身份（module.prop 的 id）要等文件落地才知道**，
 * 而"记忆"就是按模块身份存的，所以顺序必须是 下载 → 认模块 → 定脚本 → 刷入。
 */
private suspend fun flashFromUrl(
    dir: File,
    entry: RemoteEntry,
    resolveUrl: () -> String,
    forcedPlanText: String,
    context: Context,
    log: (String) -> Unit,
    onStep: (String) -> Unit,
): String? {
    val name = entry.displayName
    onStep("下载 $name")
    val url = try {
        resolveUrl()
    } catch (e: Exception) {
        log("✗ $name ${e.message}")
        return "下载 $name"
    }
    val target = File(dir, sanitizeName(name))
    log("→ $name")
    try {
        var lastMark = 0L
        RemoteDownloader.downloadTo(url, target) { read, total ->
            val mark = if (total > 0) read * 100 / total / 25 else read / (10L * 1024 * 1024)
            if (mark > lastMark) {
                lastMark = mark
                if (total > 0) log("  ↓ ${read * 100 / total}%") else log("  ↓ ${read / 1024 / 1024} MB")
            }
        }
    } catch (e: Exception) {
        log("✗ $name ${e.message}")
        target.delete()
        return "下载 $name"
    }

    val moduleId = readModuleId(target)
    val resolver = KeyPlanResolver(context, forcedPlanText)
    val (plan, planSource) = resolver.resolve(entry, moduleId)
    if (plan.isNotEmpty) {
        log("  ⌨ 按键脚本（$planSource）：${plan.format()}")
    } else if (!moduleId.isNullOrBlank()) {
        log("  ⌨ 无按键脚本（模块 $moduleId；需要在安装时按键的模块请让发送端带脚本）")
    }

    onStep("刷入 $name")
    log("→ 刷入 $name")
    val installStart = SystemClock.elapsedRealtime()
    val promptCounter = AtomicInteger(0)
    // 脚本运行器与安装并发：安装脚本在等按键时是阻塞的，注入必须来自另一条协程。
    // 用 coroutineScope 而不是全局 scope，刷入一结束子协程自动取消，不会漏一个
    // 还在 sleep 的注入任务去按到下一个模块身上。
    val result = coroutineScope {
        val planJob = if (plan.isNotEmpty) {
            launch(Dispatchers.Default) {
                runKeyPlan(plan, installStart, promptCounter::get, log)
            }
        } else {
            null
        }
        val flashResult = try {
            flashModule(
                Uri.fromFile(target),
                { line ->
                    if (PROMPT_HINT.containsMatchIn(line)) promptCounter.incrementAndGet()
                    log(line)
                },
                { line -> log(line) },
            )
        } finally {
            planJob?.cancel()
        }
        flashResult
    }
    if (result.code == 0) resolver.remember(moduleId, plan)
    target.delete()
    if (result.code != 0) {
        log("✗ $name (code ${result.code}) ${result.err}")
        return "刷入 $name"
    }
    log("✓ $name")
    return null
}

/**
 * 「远程模块助手·接收」编排器：逐条解析输入（XEC- 短码 → 登录兑换；
 * XEC1/XEC2 → 本地解码）→ 下载全部 zip → 依序刷入。返回 flashed 计数与
 * 失败步骤（null = 全部成功）。
 *
 * 失败不再中止整批：一条坏码/坏链接只记失败并继续后面的条目——过去
 * 第 1 条就报废整批，是"高概率出错"体感的主要放大器。
 *
 * @param forcedPlanText 本次对所有模块强制的按键脚本（空 = 只用发送端脚本与记忆）。
 */
private suspend fun runRemoteDeploy(
    context: Context,
    entries: List<String>,
    redeemToken: String?,
    forcedPlanText: String,
    log: (String) -> Unit,
    onStep: (String) -> Unit,
): DeployOutcome {
    val dir = File(context.cacheDir, REMOTE_DIR).apply {
        deleteRecursively()
        mkdirs()
    }
    var flashed = 0
    val failures = mutableListOf<String>()
    for ((index, entry) in entries.withIndex()) {
        val label = "#${index + 1}/${entries.size}"
        onStep("解析 $label")
        val links: List<String> = if (entry.uppercase().startsWith("XEC-")) {
            if (redeemToken == null) {
                log("✗ $label 短码需要登录后使用")
                failures.add("短码 $label")
                continue
            }
            try {
                val r = RemoteApi.redeem(redeemToken, entry)
                log("✓ $entry 兑换成功：${r.links.size} 个模块，剩余 ${r.remaining} 次")
                r.links
            } catch (e: Exception) {
                log("✗ $label ${e.message}")
                failures.add("短码 $label")
                continue
            }
        } else {
            val decoded = RemoteModuleCodec.decodeAll(entry)
            if (decoded.isFailure) {
                log("✗ $label ${decoded.exceptionOrNull()?.message}")
                failures.add("加密码 $label")
                continue
            }
            decoded.getOrThrow()
        }
        log("→ 第 $label 条包含 ${links.size} 个模块")
        for ((li, link) in links.withIndex()) {
            val tag = if (links.size > 1) "$label-${li + 1}" else label
            val parsed = RemoteEntry.parse(link)
            if (!parsed.url.startsWith("http", ignoreCase = true)) {
                log("✗ 解出的不是链接: ${parsed.url}")
                failures.add("加密码 $tag")
                continue
            }
            if (isPanSharePage(parsed.url)) {
                // 网盘「分享页」是网页不是文件——提前拦截，别下回来一坨 HTML 才报错
                log("✗ 这是网盘分享页链接（需要网页打开/登录），程序无法直接下载文件")
                log("  请让分享者改用 zip 的直链（浏览器点开就开始下载的那种）重新生成加密码")
                failures.add("加密码 $tag")
                continue
            }
            val fail = flashFromUrl(
                dir = dir,
                entry = parsed,
                resolveUrl = { parsed.url },
                forcedPlanText = forcedPlanText,
                context = context,
                log = log,
                onStep = onStep,
            )
            if (fail != null) {
                failures.add(fail)
                continue
            }
            flashed++
        }
    }
    if (failures.isNotEmpty()) {
        log("— 完成：成功 $flashed 个，失败 ${failures.size} 项 —")
        return DeployOutcome(flashed, failures.joinToString("、"))
    }
    return DeployOutcome(flashed, null)
}

@Composable
fun RemoteAssistantScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)

    var tab by remember { mutableIntStateOf(0) }
    val shareInputs = remember { mutableStateListOf("") }
    // 与 shareInputs 一一对应的按键脚本（每条链接各自一份）。
    val sharePlans = remember { mutableStateListOf("") }
    val recvInputs = remember { mutableStateListOf("") }

    // 接收端"本次强制套用"的脚本；留空表示只用发送端带的脚本与模块记忆。
    var forcedPlan by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }

    var uploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var shortCode by remember { mutableStateOf<String?>(null) }
    var showWarn by remember { mutableStateOf(false) }

    // XEC Hub 账号与短码
    var account by remember { mutableStateOf(RemoteAuth.load(context)) }
    var showAuth by remember { mutableStateOf(false) }
    var authMode by remember { mutableIntStateOf(0) } // 0=登录 1=注册
    var authUser by remember { mutableStateOf("") }
    var authPass by remember { mutableStateOf("") }
    var authErr by remember { mutableStateOf<String?>(null) }
    var authBusy by remember { mutableStateOf(false) }
    var maxUses by remember { mutableStateOf("10") }
    var expireDays by remember { mutableStateOf("0") }
    var hubBusy by remember { mutableStateOf(false) }
    var hubMsg by remember { mutableStateOf<String?>(null) }
    var pendingCodes by remember { mutableStateOf(emptyList<String>()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var flashed by remember { mutableIntStateOf(0) }
    var failedAt by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    val logLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun copyCode(code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("XEC module code", code))
        Toast.makeText(context, R.string.remote_share_copied, Toast.LENGTH_SHORT).show()
    }

    fun logout() {
        RemoteAuth.clear(context)
        account = null
        hubMsg = null
        shortCode = null
        Toast.makeText(context, R.string.remote_share_copied, Toast.LENGTH_SHORT).show()
    }

    fun submitAuth() {
        if (authBusy) return
        val u = authUser.trim()
        val p = authPass
        if (u.isEmpty() || p.isEmpty()) {
            authErr = context.getString(R.string.remote_hub_fill_both)
            return
        }
        authBusy = true
        authErr = null
        scope.launch {
            var session: RemoteApi.Session? = null
            var error: String? = null
            withContext(Dispatchers.IO) {
                try {
                    if (authMode == 1) RemoteApi.register(u, p)
                    session = RemoteApi.login(u, p)
                    RemoteAuth.save(context, session!!)
                } catch (e: Exception) {
                    error = e.message
                }
            }
            authBusy = false
            val s = session
            if (s != null) {
                account = RemoteAuth.Account(s.token, s.username, s.isAdmin)
                showAuth = false
                authPass = ""
            } else {
                authErr = error ?: context.getString(R.string.remote_hub_fail)
            }
        }
    }

    fun generateShortCode() {
        val acc = account
        if (acc == null) {
            showAuth = true
            return
        }
        if (hubBusy) return
        // 出码时把每条链接的按键脚本挂到链接 fragment 上：短码、加密码、直接粘贴
        // 三种分发方式都不需要服务端参与，脚本就跟着模块走。
        val links = shareInputs.indices.mapNotNull { i ->
            val raw = shareInputs[i].trim()
            if (raw.isEmpty()) null else RemoteEntry.annotate(raw, sharePlans.getOrElse(i) { "" })
        }
        if (links.isEmpty()) return
        val uses = maxUses.toIntOrNull() ?: 0
        val days = expireDays.toIntOrNull() ?: -1
        if (uses !in 1..9999) {
            hubMsg = context.getString(R.string.remote_hub_bad_uses)
            return
        }
        if (days !in 0..3650) {
            hubMsg = context.getString(R.string.remote_hub_bad_days)
            return
        }
        hubBusy = true
        hubMsg = null
        scope.launch {
            var code: String? = null
            var error: String? = null
            withContext(Dispatchers.IO) {
                try {
                    code = RemoteApi.share(acc.token, links, uses, days * 24)
                } catch (e: Exception) {
                    error = e.message
                }
            }
            hubBusy = false
            val c = code
            if (c != null) {
                shortCode = c
            } else {
                hubMsg = "✗ ${error ?: context.getString(R.string.remote_hub_fail)}"
                if (error?.contains("登录") == true) {
                    RemoteAuth.clear(context)
                    account = null
                    showAuth = true
                }
            }
        }
    }

    fun startUpload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uploading = true
        uploadStatus = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val links = mutableListOf<String>()
                var error: String? = null
                for ((i, uri) in uris.withIndex()) {
                    if (error != null) break
                    val name = RemoteUploader.displayName(context, uri)
                    mainHandler.post { uploadStatus = "… ${i + 1}/${uris.size} $name" }
                    try {
                        if (!name.endsWith(".zip", ignoreCase = true)) {
                            throw IllegalArgumentException("不是 zip 模块包")
                        }
                        val link = RemoteUploader.upload(context, uri, name) { read, total ->
                            val pct = if (total > 0) "${read * 100 / total}%" else "${read / 1024 / 1024} MB"
                            mainHandler.post { uploadStatus = "$name（${i + 1}/${uris.size}）$pct" }
                        }
                        links.add(link)
                    } catch (e: Exception) {
                        error = "$name：${e.message}"
                    }
                }
                links to error
            }
            val (links, error) = outcome
            if (error != null) {
                uploadStatus = "✗ $error"
            } else if (links.isNotEmpty()) {
                // 上传成功的直链追加进输入框，与手工粘贴的外链一并出码。
                // 脚本槽位必须同步追加，否则 shareInputs 与 sharePlans 会错位，
                // 把 A 模块的脚本挂到 B 模块的链接上。
                links.forEach {
                    shareInputs.add(it)
                    sharePlans.add("")
                }
                uploadStatus = context.getString(R.string.remote_upload_done, links.size)
            }
            uploading = false
        }
    }

    val pickModules = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        startUpload(uris.toList())
    }

    fun startFlash(codes: List<String>) {
        val needsHub = codes.any { it.trim().uppercase().startsWith("XEC-") }
        if (needsHub && account == null) {
            showAuth = true
            return
        }
        running = true
        done = false
        flashed = 0
        failedAt = null
        step = context.getString(R.string.remote_recv_running)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runRemoteDeploy(
                    context = context,
                    entries = codes,
                    redeemToken = account?.token,
                    forcedPlanText = forcedPlan,
                    log = { line -> mainHandler.post { logLines.add(line) } },
                    onStep = { s -> mainHandler.post { step = s } },
                )
            }
            flashed = outcome.flashed
            failedAt = outcome.failedAt
            done = true
            running = false
        }
    }

    /**
     * 手动补按键：脚本没覆盖到的模块、或脚本延迟估错了，用户可以在刷入过程中
     * 直接按这一排按钮补上——注入的是同一套原始事件，对安装脚本等价于真按键。
     */
    fun injectKey(key: RemoteKey) {
        keyFeedback = "… ${key.label}"
        scope.launch {
            val target = withContext(Dispatchers.IO) { RemoteKeyInjector.inject(key) }
            keyFeedback = if (target != null) {
                "✓ ${key.label} → $target"
            } else {
                "✗ ${key.label} 注入失败（没有可写的 input 节点）"
            }
        }
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = if (backdrop != null) Color.Transparent else Xc.colors.surface,
                    title = stringResource(R.string.remote_assistant_title),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 整页可滚动：此前外层是普通 Column，加几个链接或键盘一弹，
        // 底部的"生成短码 / 开始刷入"就被推出屏幕且无路可达（表现为"不能下拉"）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding())
                .overScrollVertical()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = account?.let {
                            stringResource(R.string.remote_hub_logged_in, it.username)
                        } ?: stringResource(R.string.remote_hub_not_logged),
                        fontSize = 12.sp,
                        color = Xc.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(
                            if (account == null) R.string.remote_hub_login else R.string.remote_hub_logout
                        ),
                        onClick = {
                            if (account == null) {
                                showAuth = true
                                authErr = null
                            } else {
                                logout()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = stringResource(R.string.remote_assistant_share),
                        onClick = { tab = 0 },
                        modifier = Modifier.weight(1f),
                        colors = if (tab == 0) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                    TextButton(
                        text = stringResource(R.string.remote_assistant_receive),
                        onClick = { tab = 1 },
                        modifier = Modifier.weight(1f),
                        colors = if (tab == 1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
            }

            if (showAuth) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(
                                if (authMode == 0) R.string.remote_hub_auth_title_login
                                else R.string.remote_hub_auth_title_register
                            ),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Xc.colors.text,
                        )
                        Text(
                            text = stringResource(R.string.remote_hub_note),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Xc.colors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextField(
                            value = authUser,
                            onValueChange = { authUser = it },
                            label = stringResource(R.string.remote_hub_username),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        TextField(
                            value = authPass,
                            onValueChange = { authPass = it },
                            label = stringResource(R.string.remote_hub_password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Text(
                            text = authErr ?: "",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE0533D),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(
                            text = stringResource(
                                if (authMode == 0) R.string.remote_hub_submit_login
                                else R.string.remote_hub_submit_register
                            ),
                            onClick = { submitAuth() },
                            enabled = !authBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        TextButton(
                            text = stringResource(
                                if (authMode == 0) R.string.remote_hub_switch_register
                                else R.string.remote_hub_switch_login
                            ),
                            onClick = {
                                authMode = if (authMode == 0) 1 else 0
                                authErr = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            text = stringResource(R.string.remote_hub_back),
                            onClick = {
                                showAuth = false
                                authErr = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (tab == 0) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.remote_share_hint),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Xc.colors.textSecondary,
                        )
                        TextButton(
                            text = stringResource(R.string.remote_upload_pick),
                            onClick = { pickModules.launch("*/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            enabled = !uploading,
                        )
                        if (uploading || uploadStatus != null) {
                            Text(
                                text = uploadStatus ?: "",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Xc.colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        shareInputs.forEachIndexed { i, _ ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextField(
                                        value = shareInputs[i],
                                        onValueChange = { shareInputs[i] = it },
                                        label = stringResource(R.string.remote_link_label, i + 1),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (shareInputs.size > 1) {
                                        MiuixIcon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.remote_remove),
                                            tint = Xc.colors.textSecondary,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable {
                                                    // 两个列表必须一起删，否则脚本槽位会整体前移一格
                                                    shareInputs.removeAt(i)
                                                    if (i < sharePlans.size) sharePlans.removeAt(i)
                                                },
                                        )
                                    }
                                }
                                KeyPlanField(
                                    value = sharePlans.getOrElse(i) { "" },
                                    onValueChange = { text ->
                                        while (sharePlans.size <= i) sharePlans.add("")
                                        sharePlans[i] = text
                                    },
                                    label = stringResource(R.string.remote_key_plan_label),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                )
                            }
                        }
                        TextButton(
                            text = stringResource(R.string.remote_add_link),
                            onClick = { shareInputs.add("") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = maxUses,
                                onValueChange = { maxUses = it.filter { ch -> ch.isDigit() } },
                                label = stringResource(R.string.remote_hub_max_uses),
                                modifier = Modifier.weight(1f),
                            )
                            TextField(
                                value = expireDays,
                                onValueChange = { expireDays = it.filter { ch -> ch.isDigit() } },
                                label = stringResource(R.string.remote_hub_expire_days),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (hubMsg != null) {
                            Text(
                                text = hubMsg ?: "",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Xc.colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.remote_hub_generate),
                            onClick = { generateShortCode() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            enabled = !uploading && !hubBusy && shareInputs.any { it.isNotBlank() },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                shortCode?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.remote_hub_code_label),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Xc.colors.text,
                            )
                            Text(
                                text = code,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Xc.colors.text,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            TextButton(
                                text = stringResource(R.string.remote_share_copy),
                                onClick = { copyCode(code) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.remote_recv_hint),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Xc.colors.textSecondary,
                        )
                        recvInputs.forEachIndexed { i, _ ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextField(
                                    value = recvInputs[i],
                                    onValueChange = { recvInputs[i] = it },
                                    label = stringResource(R.string.remote_link_label, i + 1),
                                    modifier = Modifier.weight(1f),
                                )
                                if (recvInputs.size > 1) {
                                    MiuixIcon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.remote_remove),
                                        tint = Xc.colors.textSecondary,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clickable { recvInputs.removeAt(i) },
                                    )
                                }
                            }
                        }
                        TextButton(
                            text = stringResource(R.string.remote_add_link),
                            onClick = { recvInputs.add("") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            enabled = !running,
                        )
                        Text(
                            text = stringResource(R.string.remote_key_plan_hint),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Xc.colors.textSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        KeyPlanField(
                            value = forcedPlan,
                            onValueChange = { forcedPlan = it },
                            label = stringResource(R.string.remote_key_force_label),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                        ManualKeyPad(
                            feedback = keyFeedback,
                            onKey = { injectKey(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        TextButton(
                            text = stringResource(R.string.remote_recv_start),
                            onClick = {
                                pendingCodes = recvInputs.map { it.trim() }.filter { it.isNotEmpty() }
                                if (pendingCodes.isNotEmpty()) showWarn = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            enabled = !running && recvInputs.any { it.isNotBlank() },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }

                if (done && failedAt == null) {
                    Spacer(Modifier.height(8.dp))
                    ResultCard(
                        icon = {
                            MiuixIcon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF36D167),
                            )
                        },
                        title = stringResource(R.string.remote_recv_done, flashed),
                    )
                }
                if (failedAt != null) {
                    Spacer(Modifier.height(8.dp))
                    ResultCard(
                        icon = {
                            MiuixIcon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFE0533D),
                            )
                        },
                        title = stringResource(R.string.remote_recv_failed, failedAt ?: ""),
                        action = {
                            TextButton(
                                text = stringResource(R.string.remote_recv_back),
                                onClick = { navigator.pop() },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        },
                    )
                }
                if (running || logLines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // 外层页面现在可滚动，日志卡必须自限高度（fillMaxSize 在无限高
                    // 约束下失效），日志自身在这块固定视口里滚动。
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .overScrollVertical(),
                            ) {
                                item {
                                    Text(
                                        text = step ?: stringResource(R.string.remote_recv_running),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Xc.colors.text,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                items(count = logLines.size) { i ->
                                    Text(
                                        text = logLines[i],
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = Xc.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 底部安全区：Scaffold 只保留水平 insets，页面滚到底时
            // 内容不会被导航手势条压住。
            Spacer(
                Modifier.height(
                    12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                )
            )
        }
    }

    XDialog(
        show = showWarn,
        onDismissRequest = { showWarn = false },
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.remote_recv_warning_title),
            color = Xc.colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            text = stringResource(R.string.remote_recv_warning),
            color = Xc.colors.textSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.remote_recv_warning_reject),
                onClick = { showWarn = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.remote_recv_warning_confirm),
                onClick = {
                    showWarn = false
                    startFlash(pendingCodes)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun ResultCard(
    icon: @Composable () -> Unit,
    title: String,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Xc.colors.text,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
    }
}

/**
 * 按键脚本输入：一行文本 + 预置档位 + 即时代码校验。
 *
 * 校验放在输入框正下方而不是等提交：脚本语法本身很短（`up@1500,up@3000`），
 * "看不懂这一步：up@abc" 直接标在写错的地方，比刷入到一半才发现要省事得多。
 */
@Composable
private fun KeyPlanField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val error = remember(value) {
        if (value.isBlank()) null else RemoteKeyPlan.parse(value).exceptionOrNull()?.message
    }
    Column(modifier = modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RemoteKeyPlan.PRESETS.forEach { (title, script) ->
                TextButton(
                    text = title,
                    onClick = { onValueChange(script) },
                )
            }
        }
        val status = when {
            error != null -> error
            value.isBlank() -> null
            else -> stringResource(R.string.remote_key_plan_ok)
        }
        if (status != null) {
            Text(
                text = status,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (error != null) Color(0xFFE0533D) else Xc.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 手动补按键面板。
 *
 * 存在的理由：脚本是"预判"，总有预判不到的情况（模块在脚本之外还等一次按键、
 * 延迟估早了、发送端没带脚本）。这时用户能在刷入过程中直接补按——注入的是
 * 同一套原始输入事件，对安装脚本与真按键等价。空闲时也能用它自检这台设备的
 * input 设备是否可写（结果里的设备节点就是注入目标）。
 */
@Composable
private fun ManualKeyPad(
    feedback: String?,
    onKey: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.remote_keypad_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.text,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RemoteKey.entries.forEach { key ->
                TextButton(
                    text = key.label,
                    onClick = { onKey(key) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = feedback ?: stringResource(R.string.remote_keypad_idle),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = Xc.colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

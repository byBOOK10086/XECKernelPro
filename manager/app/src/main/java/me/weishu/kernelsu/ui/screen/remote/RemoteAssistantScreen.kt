package me.weishu.kernelsu.ui.screen.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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

private const val REMOTE_DIR = "remote_modules"

private data class DeployOutcome(val flashed: Int, val failedAt: String?)

private fun sanitizeName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "module.zip" }

/** 下载单个文件并刷入；返回 null 表示成功，否则返回失败步骤描述。 */
private fun flashFromUrl(
    dir: File,
    fileName: String,
    resolveUrl: () -> String,
    log: (String) -> Unit,
    onStep: (String) -> Unit,
): String? {
    onStep("下载 $fileName")
    val url = try {
        resolveUrl()
    } catch (e: Exception) {
        log("✗ $fileName ${e.message}")
        return "下载 $fileName"
    }
    val target = File(dir, sanitizeName(fileName))
    log("→ $fileName")
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
        log("✗ $fileName ${e.message}")
        target.delete()
        return "下载 $fileName"
    }
    onStep("刷入 $fileName")
    log("→ 刷入 $fileName")
    val result = flashModule(Uri.fromFile(target), { line -> log(line) }, { line -> log(line) })
    target.delete()
    if (result.code != 0) {
        log("✗ $fileName (code ${result.code}) ${result.err}")
        return "刷入 $fileName"
    }
    log("✓ $fileName")
    return null
}

/**
 * 「远程模块助手·接收」编排器：逐条解析输入（XEC- 短码 → 登录兑换；
 * XEC1/XEC2 → 本地解码）→ 下载全部 zip → 依序刷入。返回 flashed 计数与
 * 失败步骤（null = 全部成功）。
 */
private fun runRemoteDeploy(
    context: Context,
    entries: List<String>,
    redeemToken: String?,
    log: (String) -> Unit,
    onStep: (String) -> Unit,
): DeployOutcome {
    val dir = File(context.cacheDir, REMOTE_DIR).apply {
        deleteRecursively()
        mkdirs()
    }
    var flashed = 0
    for ((index, entry) in entries.withIndex()) {
        val label = "#${index + 1}/${entries.size}"
        onStep("解析 $label")
        val links: List<String> = if (entry.uppercase().startsWith("XEC-")) {
            if (redeemToken == null) {
                log("✗ $label 短码需要登录后使用")
                return DeployOutcome(flashed, "短码 $label")
            }
            try {
                val r = RemoteApi.redeem(redeemToken, entry)
                log("✓ $entry 兑换成功：${r.links.size} 个模块，剩余 ${r.remaining} 次")
                r.links
            } catch (e: Exception) {
                log("✗ $label ${e.message}")
                return DeployOutcome(flashed, "短码 $label")
            }
        } else {
            RemoteModuleCodec.decodeAll(entry).getOrElse {
                log("✗ $label ${it.message}")
                return DeployOutcome(flashed, "加密码 $label")
            }
        }
        log("→ 第 $label 条包含 ${links.size} 个模块")
        for ((li, link) in links.withIndex()) {
            val tag = if (links.size > 1) "$label-${li + 1}" else label
            if (!link.startsWith("http", ignoreCase = true)) {
                log("✗ 解出的不是链接: $link")
                return DeployOutcome(flashed, "加密码 $tag")
            }
            if (isPanSharePage(link)) {
                // 网盘「分享页」是网页不是文件——提前拦截，别下回来一坨 HTML 才报错
                log("✗ 这是网盘分享页链接（需要网页打开/登录），程序无法直接下载文件")
                log("  请让分享者改用 zip 的直链（浏览器点开就开始下载的那种）重新生成加密码")
                return DeployOutcome(flashed, "加密码 $tag")
            }
            val name = link.substringBefore('?').trimEnd('/').substringAfterLast('/')
                .ifBlank { "module.zip" }
            val fail = flashFromUrl(dir, name, { link }, log, onStep)
            if (fail != null) return DeployOutcome(flashed, fail)
            flashed++
        }
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
    val recvInputs = remember { mutableStateListOf("") }

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
        val links = shareInputs.map { it.trim() }.filter { it.isNotEmpty() }
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
                // 上传成功的直链追加进输入框，与手工粘贴的外链一并出码
                links.forEach { shareInputs.add(it) }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding()),
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                                            .clickable { shareInputs.removeAt(i) },
                                    )
                                }
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
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
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

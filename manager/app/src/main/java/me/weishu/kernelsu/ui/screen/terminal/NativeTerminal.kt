@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package me.weishu.kernelsu.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/* -------------------------------------------------------------------------
 * MT 管理器式原生终端。
 *
 * - libsu 持久 shell：有 root 用 root（su），没有退普通 sh；每个命令作为
 *   一行喂进同一个 sh 进程，`cd` 因此天然持久。
 * - 每条命令后追加哨兵行回读 `__XC_PWD:<rc>:<pwd>`，用来同步 cwd 与退出码
 *   （libsu 的 job 回调拿不到这两样里我们关心的 cwd）。
 * - 输出按 ANSI SGR 上色（30-37/90-97/1/3/4/39），其余控制序列剔除。
 * - MT 式文件管理：长按任意输出行抽出路径 token → 动作表：打开 / 执行 /
 *   删除 / 权限(chmod/chown) / 复制路径；全部经同一 shell 执行。
 * ---------------------------------------------------------------------- */

/** SGR 解析后的一个着色片段。 */
private data class TermSpan(val text: String, val color: Color?, val bold: Boolean, val italic: Boolean, val underline: Boolean)

private data class TermLine(val id: Long, val text: AnnotatedString)

private val AnsiBase = Color(0xFFCFD8DC)
private val AnsiColors = mapOf(
    30 to Color(0xFF6B7280), 31 to Color(0xFFEF5350), 32 to Color(0xFF66BB6A),
    33 to Color(0xFFFFCA28), 34 to Color(0xFF42A5F5), 35 to Color(0xFFAB47BC),
    36 to Color(0xFF26C6DA), 37 to Color(0xFFECEFF1),
    90 to Color(0xFF90A4AE), 91 to Color(0xFFFF7043), 92 to Color(0xFF9CCC65),
    93 to Color(0xFFFFD54F), 94 to Color(0xFF64B5F6), 95 to Color(0xFFCE93D8),
    96 to Color(0xFF80DEEA), 97 to Color(0xFFFFFFFF),
)

/** 把一行原始输出切成着色片段：SGR 保留，别的转义序列全部丢弃。 */
private fun parseAnsi(raw: String): List<TermSpan> {
    val spans = ArrayList<TermSpan>()
    var color: Color? = null
    var bold = false
    var italic = false
    var underline = false
    val plain = StringBuilder()
    var i = 0
    fun flush() {
        if (plain.isNotEmpty()) {
            spans.add(TermSpan(plain.toString(), color, bold, italic, underline))
            plain.clear()
        }
    }
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\u001B' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                '[' -> {
                    // CSI：找到结束字母，只为 SGR 'm' 服务。
                    val end = findAnsiEnd(raw, i + 2)
                    if (end == -1) {
                        // 序列被行尾截断：丢弃余下内容。
                        i = raw.length
                        continue
                    }
                    val body = raw.substring(i + 2, end)
                    i = end + 1
                    if (raw[end] == 'm') {
                        val codes = body.split(';').mapNotNull { it.toIntOrNull() }
                        if (codes.isEmpty()) {
                            color = null; bold = false; italic = false; underline = false
                        }
                        var k = 0
                        while (k < codes.size) {
                            when (codes[k]) {
                                0 -> { color = null; bold = false; italic = false; underline = false }
                                1 -> bold = true
                                3 -> italic = true
                                4 -> underline = true
                                22 -> bold = false
                                23 -> italic = false
                                24 -> underline = false
                                39 -> color = null
                                in 30..37, in 90..97 -> color = AnsiColors[codes[k]]
                                else -> {}
                            }
                            k++
                        }
                    }
                    continue
                }
                ']' -> {
                    // OSC：吞到 BEL 或 ST。
                    val bel = raw.indexOf('\u0007', i + 2)
                    if (bel != -1) { i = bel + 1; continue }
                    flush()
                    return spans
                }
                else -> { i += 2; continue }
            }
        }
        if (c == '
') { i++; continue }
        plain.append(c)
        i++
    }
    flush()
    return spans
}

private fun findAnsiEnd(raw: String, from: Int): Int {
    for (j in from until raw.length) {
        val c = raw[j]
        if (c in '@'..'~') {
            return j
        }
        if (c < '0' || (c > '9' && c != ';' && c != '?')) return -1
    }
    return -1
}

private fun spansToAnnotatedString(spans: List<TermSpan>, baseColor: Color): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            withStyle(
                SpanStyle(
                    color = span.color ?: baseColor,
                    fontWeight = if (span.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    textDecoration = if (span.underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ),
            ) {
                append(span.text)
            }
        }
    }

/** 单引号包 shell 字面量。 */
private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 终端会话：一个持久 sh（root 可用时是 su），cwd 自跟踪。 */
private class TerminalSession {
    var isRoot: Boolean = false
        private set
    val cwd = mutableStateOf("/")
    private var lineId = 0L

    fun start() {
        // libsu 的静态 Shell：root 可用即 su，否则 sh；装好即热。
        isRoot = Shell.isAppGrantedRoot() == true
        cwd.value = Shell.cmd("pwd").exec().out.firstOrNull()?.takeIf { it.startsWith("/") } ?: "/"
    }

    /**
     * 执行一条命令：命令与哨兵行作为两行输入喂进持久 sh；哨兵带回
     * 退出码与 pwd。返回 (exitCode, 输出行原始文本)。
     */
    suspend fun run(command: String): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = Shell.cmd(command, "__XC_PWD:$?:\$(pwd)").exec()
        out += result.out
        err += result.err
        val merged = out + err
        var rc = result.code
        val sentinelIdx = merged.indexOfLast { it.startsWith("__XC_PWD:") }
        if (sentinelIdx >= 0) {
            val parts = merged[sentinelIdx].removePrefix("__XC_PWD:").split(':', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { rc = it }
            parts.getOrNull(1)?.takeIf { it.startsWith("/") }?.let { cwd.value = it }
            merged.dropLast(merged.size - sentinelIdx)
        } else {
            merged
        }.let { lines -> Pair(rc, lines) }
    }

    fun nextLineId(): Long = ++lineId

    fun complete(prefix: String, onResult: (List<String>) -> Unit) {
        val slash = prefix.lastIndexOf('/')
        val dirPart = if (slash >= 0) prefix.substring(0, slash + 1) else ""
        val namePart = if (slash >= 0) prefix.substring(slash + 1) else prefix
        val dir = if (dirPart.startsWith("/")) dirPart else (cwd.value.trimEnd('/') + "/" + dirPart)
        val result = Shell.cmd("ls -a ${q(dir)}").exec()
        val names = result.out.filter { it != "." && it != ".." }
        val matches = names.filter { it.startsWith(namePart) }
        onResult(matches.map { dirPart + it })
    }
}

@Composable
fun TerminalScreen() {
    val navigator = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)

    val scope = rememberCoroutineScope()
    val session = remember { TerminalSession() }
    val lines = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var running by remember { mutableStateOf(false) }
    var bootstrapped by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current

    // 长按动作表
    var actionSheetPath by remember { mutableStateOf<String?>(null) }
    // 权限对话框
    var permTarget by remember { mutableStateOf<String?>(null) }
    // 文本查看器
    var viewerText by remember { mutableStateOf<String?>(null) }
    var viewerTitle by remember { mutableStateOf("") }

    fun appendRaw(rawLines: List<String>) {
        for (raw in rawLines) {
            val spans = parseAnsi(raw)
            lines.add(TermLine(session.nextLineId(), spansToAnnotatedString(spans, AnsiBase)))
        }
        while (lines.size > 800) lines.removeRange(0, 200)
    }

    fun submit(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        history = (history + cmd).takeLast(200)
        historyIndex = -1
        appendRaw(listOf("\u001b[36m${session.cwd.value}\u001b[0m $ " + cmd))
        running = true
        scope.launch {
            try {
                val (rc, out) = session.run(cmd)
                appendRaw(out)
                if (rc != 0) appendRaw(listOf("\u001b[31mexit $rc\u001b[0m"))
            } finally {
                running = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!bootstrapped) {
            bootstrapped = true
            withContext(Dispatchers.IO) { session.start() }
            appendRaw(
                listOf(
                    "\u001b[32mXECKernelPro terminal\u001b[0m — root: " +
                        if (session.isRoot) "\u001b[32myes\u001b[0m" else "\u001b[33mno (plain sh)\u001b[0m",
                    "MT-style: long-press any output line with a path to manage that file.",
                ),
            )
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.terminal),
                    navigationIcon = {
                        IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
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
                .padding(innerPadding)
                .imePadding(),
        ) {
            // 输出台
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .xGlassBody(backdrop = null, shape = Xc.shapes.md),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                    ) {
                        for (line in lines) {
                            androidx.compose.material3.Text(
                                text = line.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                softWrap = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {},
                                        onLongClick = { actionSheetPath = extractPathToken(line.text.text, session.cwd.value) },
                                    ),
                            )
                        }
                    }
                }
            }

            // 快捷工具条（MT 风格）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickChip(stringResource(R.string.terminal_tab)) {
                    completeInput(input, session) { input = it }
                }
                QuickChip("↑") {
                    val h = history
                    if (h.isNotEmpty()) {
                        historyIndex = (if (historyIndex == -1) h.lastIndex else (historyIndex - 1).coerceAtLeast(0))
                        input = h[historyIndex]
                    }
                }
                QuickChip("↓") {
                    val h = history
                    if (h.isNotEmpty() && historyIndex != -1) {
                        historyIndex++
                        input = if (historyIndex >= h.size) {
                            historyIndex = -1
                            ""
                        } else {
                            h[historyIndex]
                        }
                    }
                }
                QuickChip(stringResource(R.string.terminal_paste), icon = Icons.Rounded.ContentPaste) {
                    clipboard.getText()?.text?.let { input += it }
                }
                QuickChip(stringResource(R.string.terminal_clear), icon = Icons.Rounded.Clear) {
                    lines.clear()
                }
                QuickChip(stringResource(R.string.terminal_here)) {
                    appendRaw(listOf("\u001b[36m${session.cwd.value}\u001b[0m"))
                }
            }

            // 输入条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${session.cwd.value.trimEnd('/').substringAfterLast('/')}/ $",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.terminal_hint_command),
                    maxLines = 1,
                )
                IconButton(onClick = { submit(input) }, enabled = !running) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        tint = if (running) colorScheme.onSurfaceVariantSummary else colorScheme.primary,
                    )
                }
            }
            Spacer(
                Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                )
            )
        }
    }

    actionSheetPath?.let { path ->
        FileActionSheet(
            path = path,
            cwd = session.cwd.value,
            onDismiss = { actionSheetPath = null },
            onAction = { action ->
                actionSheetPath = null
                when (action) {
                    FileAction.OPEN -> {
                        scope.launch {
                            val (_, out) = session.run("head -c 200000 ${q(path)}")
                            viewerTitle = path
                            viewerText = out.joinToString("\n")
                        }
                    }
                    FileAction.EXECUTE -> submit("sh ${q(path)}")
                    FileAction.DELETE -> submit("rm -rf ${q(path)}")
                    FileAction.PERMISSIONS -> permTarget = path
                    FileAction.COPY_PATH -> {
                        clipboard.setText(AnnotatedString(path))
                    }
                }
            },
        )
    }

    permTarget?.let { path ->
        PermissionDialog(
            path = path,
            onDismiss = { permTarget = null },
            session = session,
            appendRaw = ::appendRaw,
        )
    }

    viewerText?.let { text ->
        TextViewerDialog(title = viewerTitle, text = text, onDismiss = { viewerText = null })
    }
}

private enum class FileAction { OPEN, EXECUTE, DELETE, PERMISSIONS, COPY_PATH }

/** 从一行输出里抽路径 token：绝对路径 / ./xx / 看着像文件名的词。 */
private fun extractPathToken(text: String, cwd: String): String? {
    val token = text.split(Regex("\\s+"))
        .map { it.trim(*"\'(),:;".toCharArray()) }
        .filter { it.isNotEmpty() }
        .firstOrNull { t ->
            t.startsWith("/") || t.startsWith("./") || t.startsWith("../") ||
                t.contains('/') || t.contains('.') || t.endsWith(".sh")
        } ?: return null
    return if (token.startsWith("/")) token else (cwd.trimEnd('/') + "/" + token.removePrefix("./"))
}

private fun completeInput(current: String, session: TerminalSession, apply: (String) -> Unit) {
    val text = current.text
    val token = text.substringAfterLast(' ')
    if (token.isEmpty()) return
    session.complete(token) { matches ->
        if (matches.size == 1) {
            val head = text.substringBeforeLast(' ', missingDelimiterValue = "")
            apply(if (head.isEmpty()) matches[0] + " " else "$head ${matches[0]} ")
        } else if (matches.size > 1) {
            // 多个候选：公共前缀补上，剩下的列出来（简化处理：只补公共前缀）。
            val common = matches.reduce { a, b ->
                var i = 0
                while (i < a.length && i < b.length && a[i] == b[i]) i++
                a.substring(0, i)
            }
            if (common.length > token.length) {
                val head = text.substringBeforeLast(' ', missingDelimiterValue = "")
                apply(if (head.isEmpty()) common else "$head $common")
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorScheme.surface.copy(alpha = 0.55f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.width(4.dp))
            }
            Text(text = label, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileActionSheet(
    path: String,
    cwd: String,
    onDismiss: () -> Unit,
    onAction: (FileAction) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
                onLongClick = {},
            ),
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .xGlassBody(backdrop = null, shape = Xc.shapes.md),
            colors = CardDefaults.defaultColors(color = Color.Transparent),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = path.removePrefix(cwd.trimEnd('/') + "/").ifEmpty { path },
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SheetAction(stringResource(R.string.terminal_open)) { onAction(FileAction.OPEN) }
                    SheetAction(stringResource(R.string.terminal_execute)) { onAction(FileAction.EXECUTE) }
                    SheetAction(stringResource(R.string.terminal_delete)) { onAction(FileAction.DELETE) }
                    SheetAction(stringResource(R.string.terminal_permissions)) { onAction(FileAction.PERMISSIONS) }
                    SheetAction(stringResource(R.string.terminal_copy_path)) {
                        clipboard.setText(AnnotatedString(path))
                        onAction(FileAction.COPY_PATH)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.primary.copy(alpha = 0.14f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = label, fontSize = 13.sp, color = colorScheme.primary)
    }
}

/** chmod/chown 对话框：9 位 rwx 复选 + 八进制输入 + 属主输入。 */
@Composable
private fun PermissionDialog(
    path: String,
    onDismiss: () -> Unit,
    session: TerminalSession,
    appendRaw: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bits = remember(path) { mutableStateListOf(false, false, false, false, false, false, false, false, false) }
    var octal by remember(path) { mutableStateOf("") }
    var owner by remember(path) { mutableStateOf("") }

    // 读现有权限
    LaunchedEffect(path) {
        val (_, out) = session.run("stat -c '%a %U:%G' ${q(path)}")
        val first = out.firstOrNull()?.trim() ?: return@LaunchedEffect
        val seg = first.split(' ', limit = 2)
        seg.getOrNull(0)?.toIntOrNull(8)?.let { mode ->
            for (i in 0..8) bits[i] = (mode shr (8 - i)) and 1 == 1
        }
        owner = seg.getOrNull(1) ?: ""
        octal = seg.getOrNull(0) ?: octal
    }

    fun modeOctal(): Int {
        var mode = 0
        for (i in 0..8) if (bits[i]) mode = mode or (1 shl (8 - i))
        return mode
    }

    OverlayDialogScaffold(onDismiss = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.terminal_permissions),
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = path, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    stringResource(R.string.terminal_perm_owner),
                    stringResource(R.string.terminal_perm_group),
                    stringResource(R.string.terminal_perm_others),
                ).forEachIndexed { group, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = label, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                        Row {
                            listOf("r", "w", "x").forEachIndexed { bit, letter ->
                                val index = group * 3 + bit
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (bits[index]) colorScheme.primary.copy(alpha = 0.3f) else colorScheme.surface.copy(alpha = 0.5f))
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                bits[index] = !bits[index]
                                                octal = modeOctal().toString(8).padStart(3, '0')
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = letter, fontSize = 12.sp, color = if (bits[index]) colorScheme.primary else colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = octal,
                onValueChange = { octal = it.filter { c -> c.isDigit() }.take(4) },
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                singleLine = true,
                label = stringResource(R.string.terminal_perm_octal),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = owner,
                onValueChange = { owner = it },
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                singleLine = true,
                label = stringResource(R.string.terminal_perm_owner_field),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetAction(stringResource(R.string.terminal_perm_apply)) {
                    scope.launch {
                        val (_, out) = session.run("chmod ${octal.takeIf { it.isNotBlank() } ?: modeOctal().toString(8)} ${q(path)} && stat -c '%a' ${q(path)}")
                        appendRaw(out)
                        if (owner.isNotBlank()) {
                            val (_, err) = session.run("chown ${q(owner)} ${q(path)}")
                            appendRaw(err)
                        }
                        onDismiss()
                    }
                }
                SheetAction(stringResource(R.string.terminal_cancel), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun TextViewerDialog(title: String, text: String, onDismiss: () -> Unit) {
    OverlayDialogScaffold(onDismiss = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = text.ifBlank { "(empty)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = AnsiBase,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetAction(stringResource(R.string.terminal_cancel), onClick = onDismiss)
        }
    }
}

/** 玻璃语言的小模态：遮罩 + 居中卡。 */
@Composable
private fun OverlayDialogScaffold(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
                onLongClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .xGlassBody(backdrop = null, shape = Xc.shapes.md),
            colors = CardDefaults.defaultColors(color = Color.Transparent),
        ) {
            Box(Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {},
            )) {
                content()
            }
        }
    }
}

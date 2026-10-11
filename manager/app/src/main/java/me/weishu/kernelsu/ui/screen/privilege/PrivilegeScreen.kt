package me.weishu.kernelsu.ui.screen.privilege

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.GhostLockRunner
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.rootAvailable
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 提权控制台：GhostLock（CVE-2026-43499）一键提权 + 手动 profile 操作空间。
 *
 * - 自动路径：识别 uname -r → 匹配内置/导入 profile → 跑 exploit → ksud
 *   late-load → KernelSU 内核模块落地 → 管理器自动重启。已 root 的设备
 *   （提权更新入口）直接走 late-load，不需要漏洞。
 * - 手动路径（识别不了机型时的操作空间）：展示设备信息与上游 profile 来源，
 *   支持导入任意 .conf profile 后重试。
 */
@Composable
fun PrivilegeScreen(updateMode: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)

    val runner = remember { GhostLockRunner(context) }
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf<String>() }
    var phase by remember { mutableStateOf(GhostLockRunner.Phase.IDLE) }
    var failReason by remember { mutableStateOf<String?>(null) }
    var selectedCpuPair by remember { mutableIntStateOf(0) }
    // 已导入 profile 列表的刷新令牌：增删后 +1 触发 produceState 重读。
    var importToken by remember { mutableIntStateOf(0) }

    val snapshot by produceState<GhostLockRunner.Snapshot?>(initialValue = null) {
        value = runner.snapshot()
    }
    val hasRoot by produceState<Boolean?>(initialValue = null) { value = rootAvailable() }

    fun log(line: String) {
        logLines.add(line)
        if (logLines.size > 800) logLines.removeRange(0, 200)
    }

    val onRun: () -> Unit = {
        if (phase != GhostLockRunner.Phase.RUNNING) {
            phase = GhostLockRunner.Phase.RUNNING
            failReason = null
            scope.launch {
                val outcome = if (updateMode && hasRoot == true) {
                    runner.runUpdateViaRoot(::log)
                } else {
                    runner.run(::log)
                }
                phase = if (outcome.success) GhostLockRunner.Phase.SUCCESS else GhostLockRunner.Phase.FAILED
                failReason = if (outcome.success) null else outcome.reason
            }
        }
    }

    val importProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runner.importProfile(uri).onSuccess { name ->
                    log("<k> imported profile: $name")
                    importToken++
                }.onFailure {
                    log("<-> import failed: ${it.message}")
                }
            }
        }
    }

    val title = if (updateMode) {
        stringResource(R.string.privilege_update_title)
    } else {
        stringResource(R.string.privilege_title)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = barColor,
                title = title,
                scrollBehavior = scrollBehavior,
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
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = 12.dp)
                .padding(horizontal = 16.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DeviceCard(snapshot = snapshot, updateMode = updateMode, hasRoot = hasRoot)
            }
            item {
                RiskCard()
            }
            if ((snapshot?.cpuPairs?.size ?: 0) > 1) {
                item {
                    CpuPairCard(
                        labels = snapshot?.cpuPairLabels.orEmpty(),
                        selected = selectedCpuPair,
                        onSelect = {
                            selectedCpuPair = it
                            runner.selectCpuPair(it)
                        },
                    )
                }
            }
            item {
                RunCard(
                    updateMode = updateMode,
                    supported = snapshot?.supported == true,
                    phase = phase,
                    failReason = failReason,
                    onRun = onRun,
                )
            }
            item {
                LogCard(lines = logLines)
            }
            item {
                ManualCard(
                    runner = runner,
                    importToken = importToken,
                    onImport = {
                        importProfileLauncher.launch(arrayOf("application/octet-stream", "text/*", "*/*"))
                    },
                    onDelete = { name ->
                        scope.launch {
                            if (runner.deleteImportedProfile(name)) {
                                log("<k> deleted profile: $name")
                                importToken++
                            }
                        }
                    },
                    onOpenUpstream = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/YuKongA/ghostlock-app/releases"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
            item {
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    snapshot: GhostLockRunner.Snapshot?,
    updateMode: Boolean,
    hasRoot: Boolean?,
) {
    val supported = snapshot?.supported == true
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.privilege_device),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (snapshot != null) {
                    Text(
                        text = stringResource(
                            if (supported) R.string.privilege_supported else R.string.privilege_unsupported
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (supported) Color(0xFF4CAF50) else Color(0xFFFFB300),
                    )
                }
            }
            InfoRow(stringResource(R.string.privilege_model), snapshot?.deviceName ?: "…")
            InfoRow(stringResource(R.string.privilege_kernel), snapshot?.kernelRelease ?: "…")
            InfoRow(stringResource(R.string.privilege_soc), snapshot?.socName ?: "…")
            if (updateMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (hasRoot == true) {
                            R.string.privilege_root_shortcut
                        } else if (hasRoot == false) {
                            R.string.privilege_root_missing_note
                        } else {
                            R.string.privilege_probing_root
                        }
                    ),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RiskCard() {
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.privilege_risk_warning),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun CpuPairCard(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.privilege_cpu_pair),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selected
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) colorScheme.primary.copy(alpha = 0.24f)
                                else colorScheme.surface.copy(alpha = 0.5f)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunCard(
    updateMode: Boolean,
    supported: Boolean,
    phase: GhostLockRunner.Phase,
    failReason: String?,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp)) {
            val running = phase == GhostLockRunner.Phase.RUNNING
            val statusText = when (phase) {
                GhostLockRunner.Phase.SUCCESS -> stringResource(R.string.privilege_success)
                GhostLockRunner.Phase.FAILED ->
                    stringResource(R.string.privilege_failed, failReason ?: "")

                else -> null
            }
            statusText?.let {
                Text(
                    text = it,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (phase == GhostLockRunner.Phase.SUCCESS) Color(0xFF4CAF50) else Color(0xFFEF5350),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = when {
                    running -> stringResource(R.string.privilege_running)
                    updateMode -> stringResource(R.string.privilege_run_update)
                    else -> stringResource(R.string.privilege_run)
                },
                enabled = !running,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onRun,
            )
            if (!supported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.privilege_unsupported_hint),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.privilege_log),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .verticalScroll(scrollState)
                    .padding(10.dp),
            ) {
                Text(
                    text = if (lines.isEmpty()) "—" else lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF9BE8B0),
                )
            }
        }
    }
}

@Composable
private fun ManualCard(
    runner: GhostLockRunner,
    importToken: Int,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenUpstream: () -> Unit,
) {
    val importedNames by produceState(initialValue = emptyList<String>(), key1 = importToken) {
        value = runner.importedProfileNames()
    }
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = null, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.privilege_manual_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            ManualStep(1, stringResource(R.string.privilege_manual_step1))
            ManualStep(2, stringResource(R.string.privilege_manual_step2))
            ManualStep(3, stringResource(R.string.privilege_manual_step3))
            Spacer(Modifier.height(10.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.privilege_import_profile),
                onClick = onImport,
            )
            if (importedNames.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.privilege_imported, importedNames.size),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                importedNames.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.privilege_delete),
                            fontSize = 12.sp,
                            color = Color(0xFFEF5350),
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onDelete(name) },
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.privilege_open_upstream),
                fontSize = 12.sp,
                color = colorScheme.primary,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenUpstream,
                ),
            )
        }
    }
}

@Composable
private fun ManualStep(index: Int, text: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(
            text = "$index.",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

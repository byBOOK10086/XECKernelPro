package me.weishu.kernelsu.ui.screen.hidepack

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.flashModule
import me.weishu.kernelsu.ui.util.reboot
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.withNewRootShell
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File
import java.util.zip.ZipInputStream

private val MODULE_ZIPS = listOf("2.zip", "3.zip", "5.zip", "6.zip", "8.zip", "9.zip")
private val APK_NAMES = (1..9).map { "$it.apk" }
private val TRICKY_FILES = listOf("keybox.xml", "security_patch.txt", "target.txt")

/**
 * 「一键隐藏」（紫罗兰工具箱资源包 V6.0）编排器。
 *
 * 资源不内嵌 APK：先从服务器拉 manifest.json，缺/旧按 sha256 命名缓存到
 * filesDir/hidepack/ 下载校验，然后解包 → 依序刷入 6 个模块 →
 * pm install 内置应用 → 把 keybox.xml / security_patch.txt / target.txt 写入
 * /data/adb/tricky_store/。
 * （PathMask 步骤已移除：不再按 KMI 匹配刷内核 pathmask 模块。
 *   Zygisk Next 已移出资源包——其许可证不允许再分发；需要 Zygisk 的用户请
 *   自行从官方仓库安装，装好后 HMA 模块才可正常启用。）
 * 返回 null 表示全部成功；返回非 null 为失败步骤描述，调用方据此中止且不重启。
 */
private suspend fun runHidePackDeploy(
    context: Context,
    log: (String) -> Unit,
    onStep: (String) -> Unit,
): String? {
    onStep(context.getString(R.string.hide_pack_step_manifest))
    val manifest = try {
        HidePackDownloader.fetchManifest()
    } catch (e: Exception) {
        log("✗ ${e.message}")
        return context.getString(R.string.hide_pack_manifest_fail, e.message ?: "?")
    }
    if (manifest.version > 0) log("version ${manifest.version}")
    if (manifest.updatedAt.isNotEmpty()) log("updated ${manifest.updatedAt}")

    onStep(context.getString(R.string.hide_pack_step_download))
    val cached = try {
        HidePackDownloader.ensureResources(context, manifest, log)
    } catch (e: Exception) {
        log("✗ ${e.message}")
        return context.getString(R.string.hide_pack_download_fail, e.message ?: "?")
    }

    onStep(context.getString(R.string.hide_pack_step_extract))
    val dir = File(context.cacheDir, "hidepack").apply {
        deleteRecursively()
        mkdirs()
    }
    for ((name, zipFile) in cached) {
        log("→ $name")
        zipFile.inputStream().use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(dir, entry.name)
                    if (!target.canonicalPath.startsWith(dir.canonicalPath)) {
                        throw SecurityException("bad zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }
        }
    }
    log("✓ ${context.getString(R.string.hide_pack_step_extract)}")

    onStep(context.getString(R.string.hide_pack_step_modules))
    for (name in MODULE_ZIPS) {
        val zip = File(dir, name)
        if (!zip.exists()) {
            log("- $name missing, skipped")
            continue
        }
        log("→ $name")
        val result = flashModule(Uri.fromFile(zip), { line -> log(line) }, { line -> log(line) })
        if (result.code != 0) {
            log("✗ $name (code ${result.code}) ${result.err}")
            return context.getString(R.string.hide_pack_step_modules) + " [$name]"
        }
        log("✓ $name")
    }

    onStep(context.getString(R.string.hide_pack_step_apks))
    for (apk in APK_NAMES) {
        val file = File(dir, apk)
        if (!file.exists()) continue
        log("→ $apk")
        var ok = false
        withNewRootShell {
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            newJob().add("pm install -r -t -d -g ${file.absolutePath}").to(out, err).exec()
            out.forEach { log(it) }
            ok = out.any { it.contains("Success") }
        }
        if (!ok) {
            log("✗ $apk")
            return context.getString(R.string.hide_pack_step_apks) + " [$apk]"
        }
        log("✓ $apk")
    }

    onStep(context.getString(R.string.hide_pack_step_tricky))
    withNewRootShell {
        val out = ArrayList<String>()
        newJob().add("mkdir -p /data/adb/tricky_store").to(out, null).exec()
        for (name in TRICKY_FILES) {
            val src = File(dir, name)
            if (!src.exists()) continue
            newJob()
                .add("cp -f ${src.absolutePath} /data/adb/tricky_store/$name && chmod 644 /data/adb/tricky_store/$name")
                .to(out, null).exec()
            log("✓ /data/adb/tricky_store/$name")
        }
    }
    return null
}

@Composable
fun OneTapHideScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)

    val logLines = remember { mutableStateListOf<String>() }
    var step by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var failedAt by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(10) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (logLines.isNotEmpty()) return@LaunchedEffect
        val mainHandler = Handler(Looper.getMainLooper())
        step = context.getString(R.string.hide_pack_step_manifest)
        val fail = withContext(Dispatchers.IO) {
            runHidePackDeploy(
                context = context,
                log = { line -> mainHandler.post { logLines.add(line) } },
                onStep = { s -> mainHandler.post { step = s } },
            )
        }
        failedAt = fail
        done = true
    }

    LaunchedEffect(done, failedAt) {
        if (done && failedAt == null) {
            while (countdown > 0) {
                delay(1_000)
                countdown--
            }
            logLines.add(context.getString(R.string.hide_pack_done_now))
            reboot()
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
                    title = stringResource(R.string.hide_one_tap_title),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 整页可滚动 + 底部安全区：与远程模块助手页同一修法——
        // 普通 Column 在日志变长后底部内容不可达。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding())
                .overScrollVertical()
                .verticalScroll(rememberScrollState()),
        ) {
            if (done && failedAt == null) {
                Spacer(Modifier.height(12.dp))
                ResultCard(
                    icon = {
                        MiuixIcon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF36D167),
                        )
                    },
                    title = stringResource(R.string.hide_pack_done, countdown),
                )
            }
            if (failedAt != null) {
                Spacer(Modifier.height(12.dp))
                ResultCard(
                    icon = {
                        MiuixIcon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFE0533D),
                        )
                    },
                    title = stringResource(R.string.hide_pack_failed, failedAt ?: ""),
                    action = {
                        TextButton(
                            text = stringResource(R.string.hide_pack_back),
                            onClick = { navigator.pop() },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            // 日志卡自限高度：页面可滚动后 fillMaxSize 在无限高约束下失效，
            // 日志在这块固定视口里滚动。
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
                                text = step ?: stringResource(R.string.hide_pack_running),
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
            Spacer(
                Modifier.height(
                    12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                )
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

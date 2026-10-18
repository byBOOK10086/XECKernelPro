package me.weishu.kernelsu.ui.screen.hidepack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ui.util.withNewRootShell
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.component.dialog.XDialog
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

/**
 * 「隐藏环境」列表：一键隐藏的总入口。每张卡片是一个可部署的环境，
 * 本期只有「紫罗兰环境隐藏」；后续加新环境只需在 ENVS 里加一项并接路由。
 * 点卡片 → 确认框 → 进入 OneTapHideScreen 云下载 + 编排部署。
 */
/**
 * 「隐藏 TEE 与密钥」的本地动作（不下载 hidepack，纯 root 脚本）：
 *   1. 停掉正在跑的引擎进程（keymint_svc）；
 *   2. 把 keybox、拦截名单、引擎日志与自检文件移进 /data/adb/.xudc_secure/.hidden/（700）；
 *   3. 不碰 /data/adb/modules 下用户刷的模块本体 —— 要恢复时把 .hidden 里的文件搬回去即可。
 */
private val TEE_HIDE_SCRIPT = """
P=\${'$'}(pidof keymint_svc 2>/dev/null)
for p in \${'$'}P; do kill -9 \${'$'}p 2>/dev/null; done
H=/data/adb/.xudc_secure/.hidden
mkdir -p \${'$'}H 2>/dev/null
chmod 700 \${'$'}H 2>/dev/null
for f in keybox.xml target.txt target.baseline.txt engine.log tee-selfcheck.txt engine-start.txt ta-start.txt builtin.conf policy-status.txt; do
  [ -f /data/adb/tricky_store/\${'$'}f ] && mv -f /data/adb/tricky_store/\${'$'}f \${'$'}H/ 2>/dev/null
  [ -f /data/adb/.xudc_secure/\${'$'}f ] && mv -f /data/adb/.xudc_secure/\${'$'}f \${'$'}H/ 2>/dev/null
done
chmod -R 700 \${'$'}H 2>/dev/null
echo hidden=\${'$'}(ls \${'$'}H 2>/dev/null | wc -l)
"""

private data class EnvEntry(val id: String, val titleRes: Int, val descRes: Int)

private val ENVS = listOf(
    EnvEntry("violet", R.string.hide_env_violet_title, R.string.hide_env_violet_desc),
    // 2026-10-17：TEE 改成外部模块之后，密钥/名单/日志仍然散在 /data/adb 下，
    // 给一个"停引擎 + 收进隐藏目录"的本地动作（不动用户模块本体）。
    EnvEntry("tee_hide", R.string.hide_env_tee_title, R.string.hide_env_tee_desc),
)

@Composable
fun HideEnvListScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<EnvEntry?>(null) }
    var hiding by remember { mutableStateOf(false) }
    var hideResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = if (backdrop != null) Color.Transparent else Xc.colors.surface,
                    title = stringResource(R.string.hide_env_title),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            items(count = ENVS.size) { i ->
                val env = ENVS[i]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = env }
                        .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MiuixIcon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = Xc.colors.textSecondary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(env.titleRes),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Xc.colors.text,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(env.descRes),
                                fontSize = 12.sp,
                                color = Xc.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    XDialog(show = hideResult != null || hiding, onDismissRequest = { if (!hiding) hideResult = null }) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hiding) stringResource(R.string.hide_env_running) else (hideResult ?: ""),
                fontSize = 14.sp, color = Xc.colors.text, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
            if (!hiding) {
                TextButton(text = stringResource(android.R.string.ok), onClick = { hideResult = null },
                    modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    HideEnvConfirmDialog(
        show = selected != null,
        onDismissRequest = { selected = null },
        onUse = {
            val env = selected
            selected = null
            if (env != null && env.id == "tee_hide") {
                scope.launch {
                    hiding = true
                    val out = runCatching {
                        withNewRootShell(true) { ShellUtils.fastCmd(this, TEE_HIDE_SCRIPT) }
                    }.getOrDefault("")
                    hiding = false
                    hideResult = if (out.contains("hidden=")) "已隐藏：" + out.trim() else "失败：" + out.trim()
                }
            } else {
                navigator.push(me.weishu.kernelsu.ui.navigation3.Route.OneTapHide)
            }
        },
    )
}

/** 确认框（保留原「本一键隐藏来自紫罗兰工具箱」文案），走根层 XDialog 宿主。 */
@Composable
private fun HideEnvConfirmDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onUse: () -> Unit,
) {
    XDialog(show = show, onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.hide_pack_dialog_message),
                fontSize = 15.sp,
                color = Xc.colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.hide_pack_dialog_use),
                    onClick = onUse,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

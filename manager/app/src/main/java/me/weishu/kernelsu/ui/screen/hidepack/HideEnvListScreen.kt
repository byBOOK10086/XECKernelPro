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
private data class EnvEntry(val id: String, val titleRes: Int, val descRes: Int)

private val ENVS = listOf(
    EnvEntry("violet", R.string.hide_env_violet_title, R.string.hide_env_violet_desc),
)

@Composable
fun HideEnvListScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<EnvEntry?>(null) }

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

    HideEnvConfirmDialog(
        show = selected != null,
        onDismissRequest = { selected = null },
        onUse = {
            selected = null
            navigator.push(me.weishu.kernelsu.ui.navigation3.Route.OneTapHide)
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

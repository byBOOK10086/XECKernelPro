package me.weishu.kernelsu.ui.screen.tools

/*
 * 工具箱：终端 / 安全锁（超级密钥）/ 隐身入口 的统一菜单。
 * 2026-10-26 从设置页拆出——三个低频工具占设置一整屏，收进一个入口；
 * 「超级密钥」对用户改叫「安全锁」，文案全部走大白话（不出现 fd/ioctl/守卫等词）。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.SuperkeyStore
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ToolsScreen() {
    val navigator = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.tools_title),
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
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ToolCard(
                        backdrop = backdrop,
                        title = stringResource(id = R.string.tools_terminal_title),
                        summary = stringResource(id = R.string.tools_terminal_summary),
                        icon = { Icon(Icons.Rounded.Code, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground) },
                        onClick = { navigator.push(Route.Terminal) },
                    )
                    SuperkeyCard(backdrop)
                    StealthCard(backdrop)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    backdrop: LayerBackdrop?,
    title: String,
    summary: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = onClick,
    ) {
        ArrowPreference(title = title, summary = summary, startAction = icon, onClick = onClick)
    }
}

@Composable
private fun SuperkeyCard(backdrop: LayerBackdrop?) {
    val showSuperkeyDialog = rememberSaveable { mutableStateOf(false) }
    if (showSuperkeyDialog.value) {
        SuperkeyDialog(onDismiss = { showSuperkeyDialog.value = false })
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        ArrowPreference(
            title = stringResource(id = R.string.superkey_settings_title),
            summary = stringResource(id = R.string.superkey_settings_summary),
            startAction = {
                Icon(
                    Icons.Rounded.Key,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { showSuperkeyDialog.value = true },
        )
    }
}

@Composable
private fun StealthCard(backdrop: LayerBackdrop?) {
    val context = LocalContext.current
    var stealthOn by rememberSaveable { mutableStateOf(false) }
    var stealthBusy by rememberSaveable { mutableStateOf(false) }
    val showStealthConfirm = rememberSaveable { mutableStateOf(false) }
    // 进页读一次真实状态：图标是否已藏。
    LaunchedEffect(Unit) {
        stealthOn = me.weishu.kernelsu.privilege.StealthEntrance.isIconHidden(context)
    }
    val stealthConfirm = rememberConfirmDialog(
        onConfirm = {
            stealthBusy = true
            Thread {
                val ok = me.weishu.kernelsu.privilege.StealthEntrance.enable(context)
                stealthOn = ok
                stealthBusy = false
            }.start()
        }
    )
    if (showStealthConfirm.value) {
        stealthConfirm.showConfirm(
            title = stringResource(id = R.string.stealth_title),
            content = stringResource(
                id = R.string.stealth_confirm_body,
                me.weishu.kernelsu.privilege.StealthEntrance.adbFallback(context),
            ),
        )
        showStealthConfirm.value = false
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        SwitchPreference(
            title = stringResource(id = R.string.stealth_title),
            summary = stringResource(id = R.string.stealth_summary),
            startAction = {
                Icon(
                    Icons.Rounded.VisibilityOff,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground,
                )
            },
            checked = stealthOn,
            onCheckedChange = { want ->
                if (want) {
                    showStealthConfirm.value = true
                } else if (!stealthBusy) {
                    stealthBusy = true
                    Thread {
                        me.weishu.kernelsu.privilege.StealthEntrance.disable(context)
                        stealthOn = false
                        stealthBusy = false
                    }.start()
                }
            }
        )
    }
}

/**
 * 安全锁（内部名 superkey，对用户不出现该词）：
 * 设置/更换/清除锁密码 + 保护开关。内核只存 SHA-256 指纹、重启即失——
 * 重启后保护自动解除（这也是忘记密码时的恢复路径）。
 */
@Composable
private fun SuperkeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<SuperkeyStore.KernelStatus?>(null) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var oldKey by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { status = SuperkeyStore.kernelStatus() }

    XDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            XDialogTitle(text = stringResource(R.string.superkey_settings_title))
            Text(
                text = stringResource(R.string.superkey_status,
                    stringResource(if (status?.set == true) R.string.superkey_state_set else R.string.superkey_state_unset),
                    stringResource(if (status?.guardArmed == true) R.string.superkey_state_armed else R.string.superkey_state_disarmed)),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.superkey_dialog_hint),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            if (status?.set == true) {
                TextField(
                    value = oldKey,
                    onValueChange = { oldKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.superkey_old_key_label),
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
            }
            TextField(
                value = newKey,
                onValueChange = { newKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.superkey_new_key_label),
                maxLines = 1,
            )
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(
                    text = stringResource(R.string.superkey_generate),
                    onClick = { newKey = SuperkeyStore.generateKey() },
                )
                Spacer(Modifier.weight(1f))
            }
            message?.let {
                Text(it, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.superkey_arm_guard),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    enabled = newKey.isNotBlank() || status?.set == true,
                    checked = status?.guardArmed == true,
                    onCheckedChange = { arm ->
                        // 内核在保护期间拦截"改配置"命令：解除前必须先验证锁密码，
                        // 否则一律拒绝——保护"能开不能关"的实锤根因。首次开启无需验证。
                        val ok = if (arm) {
                            SuperkeyStore.armGuard(true)
                        } else {
                            SuperkeyStore.ensureAuthed(context) && SuperkeyStore.armGuard(false)
                        }
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.superkey_apply),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val old = oldKey.ifBlank { null }
                        val ok = SuperkeyStore.setKernelKey(newKey, old)
                        if (ok) {
                            SuperkeyStore.saveLocal(context, newKey)
                            newKey = ""
                            oldKey = ""
                        }
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.superkey_clear),
                    modifier = Modifier.weight(1f),
                    enabled = status?.set == true,
                    onClick = {
                        val ok = SuperkeyStore.clearKernelKey(oldKey)
                        if (ok) {
                            SuperkeyStore.clearLocal(context)
                            oldKey = ""
                        }
                        message = context.getString(
                            if (ok) R.string.superkey_applied else R.string.superkey_apply_failed
                        )
                        status = SuperkeyStore.kernelStatus()
                    },
                )
            }
        }
    }
}

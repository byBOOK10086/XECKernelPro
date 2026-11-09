package me.weishu.kernelsu.ui.screen.tools

/*
 * 工具箱：评分 / 提权 / 终端 / 外部模块 / 安全锁 / 隐身。
 * 2026-10-26 从设置页拆出——低频工具占设置一整屏，收进一个入口；
 * 「超级密钥」对用户改叫「安全锁」，文案全部走大白话（不出现 fd/ioctl/守卫等词）。
 *
 * 2026-11-09（扁平方案 A）：六个入口收进**一张面板**，行与行之间只有 1px 发丝线，
 * 不再一组一张卡；评分卡从雷达改成"大数字 + 九格"。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Extension
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.privilege.SuperkeyStore
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8SectionLabel
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
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
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ToolsScreen() {
    val navigator = LocalNavigator.current
    val mainPager = LocalMainPagerState.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    // XEC SCORE 从首页搬到这里（2026-11-09）：数据仍然只有一个来源 —— HomeViewModel
    // 与 `ui/screen/home/XecScore.kt`，工具箱不另造评分逻辑。
    // 进页面刷一次：工具箱可能先于首页被打开（从设置直达、或进程恢复直接落在这一页），
    // 那样 ViewModel 还是初始态，不刷就会显示"未安装 / 0 分"。
    val homeViewModel = viewModel<HomeViewModel>()
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { homeViewModel.refresh() }

    var showSuperkey by rememberSaveable { mutableStateOf(false) }
    var stealthOn by rememberSaveable { mutableStateOf(false) }
    var stealthBusy by rememberSaveable { mutableStateOf(false) }
    val showStealthConfirm = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stealthOn = StealthEntrance.isIconHidden(context)
    }
    val stealthConfirm = rememberConfirmDialog(
        onConfirm = {
            stealthBusy = true
            Thread {
                val ok = StealthEntrance.enable(context)
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
                StealthEntrance.adbFallback(context),
            ),
        )
        showStealthConfirm.value = false
    }
    // 行点击与开关共用同一入口：开 -> 先弹确认；关 -> 直接执行。
    val onToggleStealth: (Boolean) -> Unit = { want ->
        if (want) {
            showStealthConfirm.value = true
        } else if (!stealthBusy) {
            stealthBusy = true
            Thread {
                StealthEntrance.disable(context)
                stealthOn = false
                stealthBusy = false
            }.start()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
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
                .padding(horizontal = 22.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    // 结论先于工具：评分是"该修哪一项"的诊断入口，点开跳去检测页看九项明细。
                    XecScoreCard(state = homeState) {
                        navigator.pop()
                        mainPager.animateToPage(3)
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.tools_section_tools),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        // 提权入口（原首页未激活态那块）。放在工具箱里恒显示：
                        // 它不只是"没 root 时的补救"，也是 root 掉了之后重跑 late-load 的入口。
                        Xc8Row(
                            title = stringResource(R.string.privilege_title),
                            subtitle = stringResource(R.string.privilege_entry_subtitle),
                            leading = Icons.Rounded.Bolt,
                            onClick = { navigator.push(Route.Privilege(updateMode = false)) },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.tools_terminal_title),
                            subtitle = stringResource(id = R.string.tools_terminal_summary),
                            leading = Icons.Rounded.Code,
                            onClick = { navigator.push(Route.Terminal) },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.tools_external_modules_title),
                            subtitle = stringResource(id = R.string.tools_external_modules_summary),
                            leading = Icons.Rounded.Extension,
                            onClick = { navigator.push(Route.ExternalModules) },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.superkey_settings_title),
                            subtitle = stringResource(id = R.string.superkey_settings_summary),
                            leading = Icons.Rounded.Key,
                            onClick = { showSuperkey = true },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.stealth_title),
                            subtitle = stringResource(id = R.string.stealth_summary),
                            leading = Icons.Rounded.VisibilityOff,
                            onClick = { onToggleStealth(!stealthOn) },
                            trailing = {
                                Xc8Switch(
                                    checked = stealthOn,
                                    onCheckedChange = onToggleStealth,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSuperkey) {
        SuperkeyDialog(onDismiss = { showSuperkey = false })
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

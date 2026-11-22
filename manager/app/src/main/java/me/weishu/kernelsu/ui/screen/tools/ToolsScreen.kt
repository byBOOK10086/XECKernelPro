package me.weishu.kernelsu.ui.screen.tools

/*
 * 工具箱：评分 / 收集密钥池 / 引擎 WebUI / 隐身入口 / 指纹自检。
 *
 * 2026-11-18（Ultra 精简，用户明确要求删除）：
 *   - 终端：整块功能下线（路由与实现文件一起删除），不是藏起来；
 *   - 安全锁（内核 superkey 的管理器侧）：入口与弹窗一起删除，避免"能开不能关"
 *     的死锁；内核里那套守卫逻辑保留，但管理器不再提供武装入口；
 *   - 内核版本：点开即闪退，直接下线（连同 KernelVersionDialog.kt）；
 *   - TEE 修复：与内置引擎自愈逻辑重复，下线（连同 TeeRepairDialog.kt）；
 *   - 扩展模块：TEE 引擎 / TA 增强改由安装流程与内置引擎自动处理，不再单列入口；
 *   - 提权：提权控制台仍由安装页的"提权更新"路径使用，但工具箱不再提供入口。
 *
 * 2026-11-09（扁平方案 A）：入口收进**一张面板**，行与行之间只有 1px 发丝线，
 * 不再一组一张卡；评分卡从雷达改成"大数字 + 九格"。
 */
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.ui.MainPagerBus
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8SectionLabel
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.screen.settings.PifSection
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import me.weishu.kernelsu.ui.webui.OmyWebUIActivity
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ToolsScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    // XEC SCORE 从首页搬到这里（2026-11-09）：数据仍然只有一个来源 —— HomeViewModel
    // 与 `ui/screen/home/XecScore.kt`，工具箱不另造评分逻辑。
    // 进页面刷一次：工具箱可能先于首页被打开（从设置直达、或进程恢复直接落在这一页），
    // 那样 ViewModel 还是初始态，不刷就会显示"未安装 / 0 分"。
    val homeViewModel = viewModel<HomeViewModel>()
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { homeViewModel.refresh() }

    var showVendorKeys by rememberSaveable { mutableStateOf(false) }
    var showKeyboxPool by rememberSaveable { mutableStateOf(false) }
    var stealthOn by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var stealthBusy by remember { mutableStateOf(false) }
    val showStealthConfirm = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stealthOn = StealthEntrance.isIconHidden(context)
    }
    fun changeStealth(enable: Boolean) {
        if (stealthBusy) return
        stealthBusy = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { if (enable) StealthEntrance.enable(context) else StealthEntrance.disable(context) }.getOrDefault(false)
                }
                stealthOn = StealthEntrance.isIconHidden(context)
                if (!ok) Toast.makeText(context, R.string.stealth_change_failed, Toast.LENGTH_LONG).show()
            } finally { stealthBusy = false }
        }
    }
    val stealthConfirm = rememberConfirmDialog(onConfirm = { changeStealth(true) })
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
            changeStealth(false)
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
                    // 结论先于工具：评分是"该修哪一项"的诊断入口，点它直接去检测页看九项明细。
                    // 2026-11-09：底栏不再常驻「检测」，这里是它唯一的入口。跨路由切页
                    // 走 MainPagerBus（有界事件通道），不再读 LocalMainPagerState（P0 源）。
                    XecScoreCard(state = homeState) {
                        MainPagerBus.jumpTo(3)
                        navigator.pop()
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.tools_section_tools),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.vendor_keys_title),
                            subtitle = stringResource(R.string.vendor_keys_summary),
                            leading = Icons.Rounded.Key,
                            onClick = { showVendorKeys = true },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = "收集密钥池",
                            subtitle = "获取上游密钥、查看时间并选择应用",
                            leading = Icons.Rounded.Key,
                            onClick = { showKeyboxPool = true },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(R.string.omy_webui_title),
                            subtitle = stringResource(R.string.omy_webui_summary),
                            leading = Icons.Rounded.Key,
                            onClick = {
                                context.startActivity(
                                    Intent(context, OmyWebUIActivity::class.java),
                                )
                            },
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

                    // 指纹这块是"出问题时自己排查"的工具，不是日常设置项，所以归到工具箱。
                    Xc8SectionLabel(
                        text = "指纹（自检）",
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    PifSection(modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    if (showVendorKeys) VendorKeyDialog(onDismiss = { showVendorKeys = false })
    if (showKeyboxPool) KeyboxPoolDialog(onDismiss = { showKeyboxPool = false })
}

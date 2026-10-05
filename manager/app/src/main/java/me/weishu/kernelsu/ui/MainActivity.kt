package me.weishu.kernelsu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.bottombar.BottomBar
import me.weishu.kernelsu.ui.component.bottombar.MainPagerState
import me.weishu.kernelsu.ui.component.bottombar.NavigationBadgeState
import me.weishu.kernelsu.ui.component.bottombar.SideRail
import me.weishu.kernelsu.ui.component.bottombar.rememberMainPagerState
import me.weishu.kernelsu.ui.component.bottombar.useNavigationRail
import me.weishu.kernelsu.ui.component.dialog.LocalXDialogBackdrop
import me.weishu.kernelsu.ui.component.dialog.LocalXDialogHost
import me.weishu.kernelsu.ui.component.dialog.XDialogHost
import me.weishu.kernelsu.ui.component.dialog.XDialogHostState
import me.weishu.kernelsu.ui.design.liquid.XDropletHost
import me.weishu.kernelsu.ui.design.token.XcTheme
import me.weishu.kernelsu.ui.navigation3.IntentDispatcher
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.navigation3.rememberNavigator
import me.weishu.kernelsu.ui.screen.about.AboutScreen
import me.weishu.kernelsu.ui.screen.appprofile.AppProfileScreen
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreen
import me.weishu.kernelsu.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import me.weishu.kernelsu.ui.screen.flash.FlashScreen
import me.weishu.kernelsu.ui.screen.home.HomePager
import me.weishu.kernelsu.ui.screen.detect.DetectPager
import me.weishu.kernelsu.ui.screen.hidepack.HideEnvListScreen
import me.weishu.kernelsu.ui.screen.hidepack.OneTapHideScreen
import me.weishu.kernelsu.ui.screen.remote.RemoteAssistantScreen
import me.weishu.kernelsu.ui.screen.install.InstallScreen
import me.weishu.kernelsu.ui.screen.kpm.KpmPager
import me.weishu.kernelsu.ui.screen.module.ModuleActionSheet
import me.weishu.kernelsu.ui.screen.module.ModulePager
import me.weishu.kernelsu.ui.screen.module.ModuleSheetState
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoDetailScreen
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoScreen
import me.weishu.kernelsu.ui.screen.settings.SettingPager
import me.weishu.kernelsu.ui.screen.sulog.SulogScreen
import me.weishu.kernelsu.ui.screen.superuser.SuperUserPager
import me.weishu.kernelsu.ui.screen.template.AppProfileTemplateScreen
import me.weishu.kernelsu.ui.screen.templateeditor.TemplateEditorScreen
import me.weishu.kernelsu.ui.screen.terminal.TerminalPager
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBar
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBarBlur
import me.weishu.kernelsu.ui.theme.LocalEnableNavigationBadge
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.install
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.rememberContentReady
import me.weishu.kernelsu.ui.viewmodel.MainActivityViewModel
import me.weishu.kernelsu.ui.viewmodel.MainPagerConfig
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L


    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            !contentReady || SystemClock.uptimeMillis() - splashStartedAt < splashAnimationDurationMs
        }

        val isManager = Natives.isManager
        if (isManager && Natives.kernelUAPIVersion == Natives.managerUAPIVersion) install()

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
            ) {
                // XcTheme 必须包在 KernelSUTheme 外面：miuix 主题内部会重新下发
                // 一次 LocalIndication，圆角高光只有在它之内才覆盖得住。
                XcTheme(isDark = isInDarkTheme(), isAmoled = appSettings.colorMode.isAmoled) {
                    KernelSUTheme(appSettings = appSettings) {
                        // 对话框的玻璃要采"整个窗口"的图层，所以 backdrop 只能建在根层：
                        // 各页自己的 rememberBlurBackdrop 只覆盖那一页，而对话框宿主是
                        // 5 个 nav entry（Main / Home / SuperUser / Module / Settings）
                        // 共用的，拿不到任何单独一页的采样源。
                        val dialogBackdrop = rememberBlurBackdrop(uiState.enableBlur)
                        val dialogHostState = remember { XDialogHostState() }

                        CompositionLocalProvider(
                            LocalXDialogHost provides dialogHostState,
                            LocalXDialogBackdrop provides dialogBackdrop,
                        ) {
                            XDropletHost {
                                IntentDispatcher(intentChannel = intentChannel)
                                val mainScreenEntry = @Composable {
                                    MainScreen(
                                        initialPage = selectedMainPage,
                                        onPageChanged = viewModel::setSelectedMainPage,
                                    )
                                }

                                val navDisplay = @Composable {
                                    NavDisplay(
                                        backStack = navigator.backStack,
                                        entryDecorators = listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(),
                                            rememberViewModelStoreNavEntryDecorator()
                                        ),
                                        onBack = {
                                            when (val top = navigator.current()) {
                                                is Route.TemplateEditor -> {
                                                    if (!top.readOnly) {
                                                        navigator.setResult("template_edit", true)
                                                    } else {
                                                        navigator.pop()
                                                    }
                                                }

                                                else -> navigator.pop()
                                            }
                                        },
                                        entryProvider = entryProvider {
                                            entry<Route.Main> { mainScreenEntry() }
                                            entry<Route.About> { AboutScreen() }
                                            entry<Route.Sulog> { SulogScreen() }
                                            entry<Route.ColorPalette> { ColorPaletteScreen() }
                                            entry<Route.AppProfileTemplate> { AppProfileTemplateScreen() }
                                            entry<Route.TemplateEditor> { key -> TemplateEditorScreen(key.template, key.readOnly) }
                                            entry<Route.AppProfile> { key -> AppProfileScreen(key.uid) }
                                            entry<Route.ModuleRepo> { ModuleRepoScreen() }
                                            entry<Route.ModuleRepoDetail> { key -> ModuleRepoDetailScreen(key.module) }
                                            entry<Route.Install> { InstallScreen() }
                                            entry<Route.Flash> { key -> FlashScreen(key.flashIt) }
                                            entry<Route.ExecuteModuleAction> { key -> ExecuteModuleActionScreen(key.moduleId, key.fromShortcut) }
                                            entry<Route.HideEnvList> { HideEnvListScreen() }
                                            entry<Route.OneTapHide> { OneTapHideScreen() }
                                            entry<Route.RemoteAssistant> { RemoteAssistantScreen() }
                                            entry<Route.Home> { mainScreenEntry() }
                                            entry<Route.SuperUser> { mainScreenEntry() }
                                            entry<Route.Module> { mainScreenEntry() }
                                            entry<Route.Settings> { mainScreenEntry() }
                                        }
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 根层 backdrop 的采样源 = 壁纸 + 全部导航内容。
                                        // 对话框**不**放进这个 Box，免得把自己也采进采样源、
                                        // 出现自采样把背景糊成一团。
                                        .then(
                                            if (dialogBackdrop != null) {
                                                Modifier.layerBackdrop(dialogBackdrop)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                ) {
                                    Image(
                                        painter = painterResource(
                                            if (isManager) R.drawable.bg_lkm_active else R.drawable.bg_not_patched
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Scaffold(containerColor = Color.Transparent) {
                                        navDisplay()
                                    }
                                }

                                // 同窗口浮层靠绘制顺序盖住内容，所以必须排在 Box 之后；
                                // 排在这里才有意义——此时根层 backdrop 里已经录好了
                                // "对话框背后的那一帧"。
                                //
                                // 模块长按操作面板也挂在根层（视口固定）：它此前渲染在
                                // pager 第 2 页的子树里，会被翻页几何带走/错位——表现为
                                // "长按后不出现、切到相邻页才冒出来、按钮点不中"。
                                // 根层渲染保证面板永远盖在"当前可见的模块页"上。
                                ModuleActionSheet(
                                    module = ModuleSheetState.module,
                                    updateUrl = ModuleSheetState.updateUrl,
                                    backdrop = dialogBackdrop,
                                    bottomInnerPadding = bottomBarClearPadding(),
                                    onDismissRequest = { ModuleSheetState.hide() },
                                    onExecuteAction = {
                                        ModuleSheetState.onExecuteAction?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onOpenWebUi = {
                                        ModuleSheetState.onOpenWebUi?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUpdate = {
                                        ModuleSheetState.onUpdate?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUninstall = {
                                        ModuleSheetState.onUninstall?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUndoUninstall = {
                                        ModuleSheetState.onUndoUninstall?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onAddActionShortcut = { type ->
                                        ModuleSheetState.onAddActionShortcut?.invoke(type)
                                    },
                                )
                                XDialogHost(
                                    state = dialogHostState,
                                    backdrop = dialogBackdrop,
                                )
                                SideEffect { contentReady = true }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    val isFullFeatured = Natives.isFullFeatured()
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }

    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state -> state.groupedApps.count { it.anyAllowSu } }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(0)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured) {
        if (!isFullFeatured || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing) moduleViewModel.fetchModuleList()
            moduleViewModel.uiState.first { it.hasLoaded }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    // Loading the app list just for a badge is too expensive; read the kernel allowlist instead.
    var superuserCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(badgeEnabled, grantedUidCount) {
        superuserCount = if (badgeEnabled) withContext(Dispatchers.IO) { getSuperuserCount() } else 0
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleUiState.modules.count { it.enabled },
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }
    val surfaceColor = MiuixTheme.colorScheme.surface.copy(alpha = 1f)
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                    HorizontalPager(
                        modifier = Modifier
                            .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                        state = mainPagerState.pagerState,
                        beyondViewportPageCount = if (contentReady) 3 else 0,
                        overscrollEffect = null,
                        userScrollEnabled = userScrollEnabled && !mainPagerState.isGestureLocked,
                    ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (contentReady || isCurrentPage) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> if (contentReady || isCurrentPage) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> if (contentReady || isCurrentPage) ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> if (contentReady || isCurrentPage) KpmPager(bottomInnerPadding, isCurrentPage)
                        4 -> if (contentReady || isCurrentPage) DetectPager(bottomInnerPadding, isCurrentPage)
                        5 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                        6 -> if (contentReady || isCurrentPage) TerminalPager(bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            Scaffold(containerColor = Color.Transparent) { _ ->
                Row {
                    SideRail(navigationBadge)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .consumeWindowInsets(startInsets)
                    ) {
                        pagerContent(navBarBottomPadding)
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            Scaffold(
                bottomBar = bottomBar,
                containerColor = Color.Transparent,
            ) { innerPadding ->
                pagerContent(innerPadding.calculateBottomPadding())
            }
        }
    }
}


/** 模块操作面板贴底时为底栏让位：导航栏 inset + 底栏高度（含悬浮 margin）的安全余量。 */
@Composable
private fun bottomBarClearPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

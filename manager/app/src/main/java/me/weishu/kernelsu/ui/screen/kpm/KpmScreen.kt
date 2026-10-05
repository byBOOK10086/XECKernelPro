package me.weishu.kernelsu.ui.screen.kpm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.ObserveAsEvents
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.viewmodel.KpmViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun KpmPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<KpmViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val miuixSnackbarHostState = remember { MiuixSnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            viewModel.load()
        }
    }

    fun showMessage(message: String) {
        scope.launch {
            miuixSnackbarHostState.showSnackbar(message)
        }
    }

    ObserveAsEvents(viewModel.kpmEvent) { event ->
        when (event) {
            is KpmEffect.SnackBar -> showMessage(event.message)
        }
    }

    val pickKpmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(viewModel::flash)
    }

    val navigator = LocalNavigator.current
    val onEmbed = {
        navigator.push(Route.Flash(FlashIt.FlashBootKpmEmbed))
    }

    KpmPagerMiuix(
        uiState = uiState,
        snackbarHostState = miuixSnackbarHostState,
        onFlash = { pickKpmLauncher.launch("*/*") },
        onEmbed = onEmbed,
        bottomInnerPadding = bottomInnerPadding,
    )
}

@Composable
private fun KpmPagerMiuix(
    uiState: KpmUiState,
    snackbarHostState: MiuixSnackbarHostState,
    onFlash: () -> Unit,
    onEmbed: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface.copy(alpha = 1f)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.kpm),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        snackbarHost = {
            SnackbarHost(
                state = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomInnerPadding + 20.dp),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    KpmStatusCardMiuix(uiState)
                    KpmModuleListMiuix(uiState.modules)
                    if (!uiState.active) {
                        Card(
                            modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                            onClick = onEmbed,
                        ) {
                            BasicComponent(
                                title = stringResource(R.string.kpm_embed),
                                summary = stringResource(R.string.kpm_embed_hint),
                                startAction = {
                                    MiuixIcon(
                                        imageVector = Icons.Rounded.Memory,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                    )
                                },
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
                        colors = CardDefaults.defaultColors(color = Color.Transparent),
                        onClick = onFlash,
                    ) {
                        BasicComponent(
                            title = if (uiState.isFlashing) stringResource(R.string.processing) else stringResource(R.string.kpm_flash),
                            startAction = {
                                MiuixIcon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun KpmStatusCardMiuix(uiState: KpmUiState) {
    val active = uiState.active
    val title = if (active) {
        stringResource(R.string.kpm_activated)
    } else {
        stringResource(R.string.kpm_not_activated)
    }
    val summary = if (active) {
        stringResource(R.string.kpm_version, uiState.version)
    } else {
        stringResource(R.string.kpm_not_activated_hint)
    }

    Card {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = {
                MiuixIcon(
                    imageVector = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Block,
                    contentDescription = title,
                    tint = if (active) Color(0xFF36D167) else colorScheme.onSurface,
                )
            },
        )
    }
}

@Composable
private fun KpmModuleListMiuix(modules: List<String>) {
    Card {
        Column {
            if (modules.isEmpty()) {
                BasicComponent(
                    title = stringResource(R.string.kpm_empty),
                    startAction = {
                        MiuixIcon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = colorScheme.onSurface,
                        )
                    },
                )
            } else {
                modules.forEach { module ->
                    BasicComponent(
                        title = module,
                        startAction = {
                            MiuixIcon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = colorScheme.onSurface,
                            )
                        },
                    )
                }
            }
        }
    }
}

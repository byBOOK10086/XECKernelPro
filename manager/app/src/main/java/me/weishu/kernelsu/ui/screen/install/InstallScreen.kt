package me.weishu.kernelsu.ui.screen.install

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.kpatch.KPatch
import me.weishu.kernelsu.ui.component.choosekmidialog.ChooseKmiDialog
import me.weishu.kernelsu.ui.component.dialog.DownloadDialog
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.util.LkmSelection
import me.weishu.kernelsu.ui.util.getAvailablePartitions
import me.weishu.kernelsu.ui.util.getCurrentKmi
import me.weishu.kernelsu.ui.util.getDefaultPartition
import me.weishu.kernelsu.ui.util.getSlotSuffix
import me.weishu.kernelsu.ui.util.isAbDevice
import me.weishu.kernelsu.ui.util.probeRemoteBootPartitions
import me.weishu.kernelsu.ui.util.rootAvailable
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState

@Composable
fun InstallScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val miuixSnackbarHost = remember { MiuixSnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var probeJob by remember { mutableStateOf<Job?>(null) }
    val loadingDialog = rememberLoadingDialog()

    var installMethod by rememberSaveable { mutableStateOf<InstallMethod?>(null) }
    var downloadDialogShown by rememberSaveable { mutableStateOf(false) }
    var remotePartitions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var remotePartitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var lkmSelection by rememberSaveable { mutableStateOf<LkmSelection>(LkmSelection.KmiNone) }
    var partitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var hasCustomSelected by rememberSaveable { mutableStateOf(false) }
    val showChooseKmiDialog = rememberSaveable { mutableStateOf(false) }
    var advancedOptionsShown by rememberSaveable { mutableStateOf(false) }
    var allowShell by rememberSaveable { mutableStateOf(false) }
    var enableAdb by rememberSaveable { mutableStateOf(false) }
    var forceBackup by rememberSaveable { mutableStateOf(false) }

    val currentKmi by produceState(initialValue = "") { value = getCurrentKmi() }
    val partitions by produceState(initialValue = emptyList()) { value = getAvailablePartitions() }
    val defaultPartition by produceState(initialValue = "") { value = getDefaultPartition() }
    // root 与 GKI 用 null 表示「尚未探测完成」：探测期间这几项按可用渲染，
    // 结果回来后再决定是否降级成「可见但不可用」，避免首帧闪一下灰态。
    val rootAvailable by produceState<Boolean?>(initialValue = null) { value = rootAvailable() }
    val isAbDevice by produceState(initialValue = false) { value = isAbDevice() }
    val isGkiDevice by produceState<Boolean?>(initialValue = null) { value = getKernelVersion().isGKI() }

    val selectFileTip = stringResource(id = R.string.select_file_tip, defaultPartition)
    val selectFileTipNoGki = stringResource(id = R.string.select_file_tip_nogki)
    val selectFileKpmTip = stringResource(id = R.string.select_file_kpm_tip)
    val downloadFileMsg = stringResource(id = R.string.download_dialog_msg)
    val anyKernelTip = stringResource(id = R.string.install_anykernel_tip)
    val kernelPatchTip = stringResource(id = R.string.kernel_patch_summary)
    val installRequiresRoot = stringResource(id = R.string.install_requires_root)
    val installRequiresGki = stringResource(id = R.string.install_requires_gki)
    // kpatch 补丁成品（cache 内），等待用户选保存位置。
    var pendingPatchedFile by remember { mutableStateOf<java.io.File?>(null) }

    // AnyKernel3 / 直接安装 / 安装到未使用槽位这三项以前被
    // `rootAvailable && isGkiDevice` 在 buildList 阶段整体丢弃，条件不满足时
    // 界面上不留任何痕迹，用户会以为这个版本干脆没有 GKI 功能。现在三项始终
    // 入列，只在条件不满足时降级成「可见但不可用」，并把原因写进 summary。
    // `null`（尚未探测完成）视为可用，避免首帧闪灰；只有明确探测出为 false 才降级。
    // 这里用 `== false` 而不是 `!rootAvailable`：这两个值来自 produceState 的委托属性，
    // 委托属性不支持 smart cast，取反后仍是 Boolean? 会编译不过。
    val gatedInstallBlockedReason: String? = when {
        rootAvailable == false -> installRequiresRoot
        isGkiDevice == false -> installRequiresGki
        else -> null
    }
    // `isGkiDevice` 必须留在 key 里：探测完成时它从 null 变成 true/false，
    // 下面 SelectFile 的 summary 要跟着从「非 GKI」提示换成 GKI 提示。
    val installMethodOptions = remember(isAbDevice, isGkiDevice, selectFileTip, selectFileTipNoGki, selectFileKpmTip, downloadFileMsg, anyKernelTip, kernelPatchTip) {
        buildList {
            add(InstallMethod.SelectFile(summary = if (isGkiDevice == true) selectFileTip else selectFileTipNoGki))
            add(InstallMethod.SelectFileForKpm(summary = selectFileKpmTip))
            add(InstallMethod.DownloadFile(summary = downloadFileMsg))
            add(InstallMethod.AnyKernel(summary = anyKernelTip))
            add(InstallMethod.DirectInstall)
            if (isAbDevice) add(InstallMethod.DirectInstallToInactiveSlot)
            add(InstallMethod.PrivilegeUpdate)
            // 自动检测内核匹配刷写方案：非 GKI（<5.10）内核才出现 KernelPatch
            // 路线（kptools + kpimg 打 boot.img）；GKI 设备继续走上面的直刷。
            if (isGkiDevice == false) {
                add(InstallMethod.KernelPatch(summary = kernelPatchTip))
            }
        }
    }

    val isOta = installMethod is InstallMethod.DirectInstallToInactiveSlot
    val slotSuffix by produceState(initialValue = "", isOta) { value = getSlotSuffix(isOta) }
    val defaultIndex = remember(partitions, defaultPartition) {
        partitions.indexOf(defaultPartition).coerceAtLeast(0)
    }

    LaunchedEffect(partitions, defaultIndex, hasCustomSelected) {
        if (partitions.isEmpty()) return@LaunchedEffect
        if (!hasCustomSelected) {
            partitionSelectionIndex = defaultIndex.coerceIn(0, partitions.lastIndex)
        } else if (partitionSelectionIndex > partitions.lastIndex) {
            partitionSelectionIndex = partitions.lastIndex
        }
    }

    val displayPartitions = remember(partitions, defaultPartition) {
        partitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }
    val remoteDisplayPartitions = remember(remotePartitions, defaultPartition) {
        remotePartitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }

    fun showMessage(message: String) {
        scope.launch {
            miuixSnackbarHost.showSnackbar(message)
        }
    }

    val onInstall = {
        installMethod?.let { method ->
            navigator.push(
                Route.Flash(
                    when (method) {
                        is InstallMethod.DownloadFile -> FlashIt.DownloadBoot(
                            url = method.url ?: return@let,
                            partition = method.partition ?: return@let,
                            lkm = lkmSelection,
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            backup = forceBackup
                        )
                        is InstallMethod.SelectFileForKpm -> FlashIt.FlashBootKpm(
                            boot = method.uri ?: return@let
                        )
                        is InstallMethod.AnyKernel -> FlashIt.FlashAnyKernel(
                            uri = method.uri ?: return@let
                        )
                        else -> FlashIt.FlashBoot(
                            boot = if (method is InstallMethod.SelectFile) method.uri else null,
                            lkm = lkmSelection,
                            ota = method is InstallMethod.DirectInstallToInactiveSlot,
                            partition = partitions.getOrNull(partitionSelectionIndex),
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            backup = method is InstallMethod.SelectFile && forceBackup
                        )
                    }
                )
            )
        }
    }

    ChooseKmiDialog(
        show = showChooseKmiDialog.value,
        onDismissRequest = { showChooseKmiDialog.value = false },
        onSelected = { kmi ->
            kmi?.let {
                lkmSelection = LkmSelection.KmiString(it)
                onInstall()
            }
        }
    )

    DownloadDialog(
        show = downloadDialogShown,
        onConfirm = { url ->
            downloadDialogShown = false
            probeJob?.cancel()
            probeJob = scope.launch {
                try {
                    loadingDialog.showLoading()
                    val result = probeRemoteBootPartitions(url)
                    if (result.partitions.isEmpty()) {
                        showMessage(resources.getString(R.string.download_no_boot_partition))
                    } else {
                        val defaultIdx = result.partitions.indexOf(defaultPartition).coerceAtLeast(0)
                        remotePartitions = result.partitions
                        remotePartitionSelectionIndex = defaultIdx
                        installMethod = InstallMethod.DownloadFile(
                            url = url,
                            partition = result.partitions[defaultIdx],
                            summary = downloadFileMsg,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showMessage(
                        resources.getString(R.string.download_probe_failed, e.message ?: "")
                    )
                } finally {
                    loadingDialog.hide()
                }
            }
        },
        onDismiss = { downloadDialogShown = false }
    )

    val selectLkmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                if (isKoFile(context, uri)) {
                    lkmSelection = LkmSelection.LkmUri(uri)
                } else {
                    lkmSelection = LkmSelection.KmiNone
                    showMessage(resources.getString(R.string.install_only_support_ko_file))
                }
            }
        }
    }
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.SelectFile(uri, summary = if (isGkiDevice == true) selectFileTip else selectFileTipNoGki)
            }
        }
    }
    val selectKpmImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.SelectFileForKpm(uri, summary = selectFileKpmTip)
            }
        }
    }
    val selectAnyKernelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.AnyKernel(uri, summary = anyKernelTip)
            }
        }
    }
    val selectKpatchImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.KernelPatch(uri, summary = kernelPatchTip)
            }
        }
    }
    // kpatch 补丁产物保存：用户选位置后把 cache 里的成品拷过去。
    val savePatchedLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val patched = pendingPatchedFile
        if (uri != null && patched != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    patched.inputStream().use { it.copyTo(out) }
                } ?: error("cannot open $uri")
            }.onSuccess {
                showMessage(resources.getString(R.string.kernel_patch_saved))
            }.onFailure {
                showMessage(resources.getString(R.string.kernel_patch_failed, it.message ?: ""))
            }
        }
    }

    val state = InstallUiState(
        installMethod = installMethod,
        lkmSelection = lkmSelection,
        partitionSelectionIndex = partitionSelectionIndex,
        displayPartitions = displayPartitions,
        remoteDisplayPartitions = remoteDisplayPartitions,
        remotePartitionSelectionIndex = remotePartitionSelectionIndex,
        currentKmi = currentKmi,
        slotSuffix = slotSuffix,
        installMethodOptions = installMethodOptions,
        gatedInstallBlockedReason = gatedInstallBlockedReason,
        canSelectPartition = installMethod is InstallMethod.DirectInstall ||
            installMethod is InstallMethod.DirectInstallToInactiveSlot ||
            installMethod is InstallMethod.DownloadFile,
        advancedOptionsShown = advancedOptionsShown,
        allowShell = allowShell,
        enableAdb = enableAdb,
        forceBackup = forceBackup,
        canForceBackup = installMethod is InstallMethod.SelectFile,
    )
    val actions = InstallScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSelectMethod = { method -> installMethod = method },
        onDownloadFile = { downloadDialogShown = true },
        onSelectBootImage = {
            selectImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onSelectBootImageForKpm = {
            selectKpmImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onSelectAnyKernel = {
            selectAnyKernelLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" })
        },
        onSelectBootImageForKpatch = {
            selectKpatchImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onPrivilegeUpdate = dropUnlessResumed { navigator.push(Route.Privilege(updateMode = true)) },
        onUploadLkm = {
            selectLkmLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onClearLkm = { lkmSelection = LkmSelection.KmiNone },
        onSelectPartition = { index ->
            hasCustomSelected = true
            val method = installMethod
            if (method is InstallMethod.DownloadFile) {
                remotePartitionSelectionIndex = index
                installMethod = method.copy(partition = remotePartitions.getOrNull(index))
            } else {
                partitionSelectionIndex = index
            }
        },
        onNext = {
            val method = installMethod
            if (method is InstallMethod.KernelPatch) {
                // KernelPatch 路线（<5.10）：选 boot.img → kptools 打 kpimg →
                // 成品另存（fastboot/recovery 手动刷）。不进 Flash 流程。
                val uri = method.uri
                if (uri == null) {
                    selectKpatchImageLauncher.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" }
                    )
                } else {
                    scope.launch {
                        try {
                            loadingDialog.showLoading()
                            val bootFile = java.io.File(context.cacheDir, "kpatch_boot.img")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                bootFile.outputStream().use(input::copyTo)
                            } ?: error("cannot open boot image")
                            val result = KPatch.patch(context, bootFile) { }
                            if (result.success && result.output != null) {
                                pendingPatchedFile = result.output
                                savePatchedLauncher.launch("boot-patched.img")
                            } else {
                                val tail = result.log.lineSequence()
                                    .lastOrNull { it.isNotBlank() } ?: result.log.take(120)
                                showMessage(resources.getString(R.string.kernel_patch_failed, tail))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            showMessage(resources.getString(R.string.kernel_patch_failed, e.message ?: ""))
                        } finally {
                            loadingDialog.hide()
                        }
                    }
                }
            } else {
                val isLkmSelected = lkmSelection != LkmSelection.KmiNone
                val isKmiUnknown = currentKmi.isBlank()
                val isKmiUnresolved = when (installMethod) {
                    // The download flow extracts the KMI itself; no manual
                    // selection needed.
                    is InstallMethod.DownloadFile -> false
                    is InstallMethod.SelectFile -> true
                    is InstallMethod.SelectFileForKpm -> false
                    is InstallMethod.AnyKernel -> false
                    else -> isKmiUnknown
                }
                if (!isLkmSelected && isKmiUnresolved) {
                    showChooseKmiDialog.value = true
                } else {
                    onInstall()
                }
            }
        },
        onAdvancedOptionsClicked = {
            advancedOptionsShown = !advancedOptionsShown
        },
        onSelectAllowShell = {
            allowShell = it
        },
        onSelectEnableAdb = {
            enableAdb = it
        },
        onSelectForceBackup = {
            forceBackup = it
        }
    )

    InstallScreenMiuix(state, actions, miuixSnackbarHost)
}

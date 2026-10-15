package me.weishu.kernelsu.ui.screen.module

import android.net.Uri
import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo
import me.weishu.kernelsu.ui.component.SearchStatus

sealed interface ModuleConfirmRequest {
    data class Update(
        val module: Module,
        val downloadUrl: String,
        val fileName: String,
    ) : ModuleConfirmRequest

    data class Uninstall(
        val module: Module,
    ) : ModuleConfirmRequest
}

@Immutable
data class ModuleConfirmDialogState(
    val request: ModuleConfirmRequest,
    val title: String,
    val content: String? = null,
    val markdown: Boolean = false,
    val html: Boolean = false,
    val confirm: String? = null,
    val dismiss: String? = null,
)

sealed interface ModuleEffect {
    data class Toast(
        val message: String,
    ) : ModuleEffect

    data class SnackBar(
        val message: String,
    ) : ModuleEffect
}

@Immutable
data class ModuleUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val modules: List<Module> = emptyList(),
    val moduleList: List<Module> = emptyList(),
    val updateInfo: Map<String, ModuleUpdateInfo> = emptyMap(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<Module> = emptyList(),
    val sortEnabledFirst: Boolean = false,
    val sortActionFirst: Boolean = false,
    val checkModuleUpdate: Boolean = true,
    val isSafeMode: Boolean = false,
    val magiskInstalled: Boolean = false,
    /**
     * 这台设备是不是"Magisk 路线"（挂载态：没有 KSU 驱动应答且内核低于 5.10）。
     *
     * 判据与 [me.weishu.kernelsu.ui.util.isMountState] 同一处，不另立口径。与 [magiskInstalled]
     * 的区别很关键：内核态设备（5.10+，以及 <5.10 但有 KSU 驱动的 KPatch）上出现真 Magisk
     * 才是**冲突**，而挂载态设备上 Magisk 是预期内的 —— 那种设备模块列表、模块操作都照常
     * 可用（目录同为 `/data/adb/modules`）。
     */
    val magiskRoute: Boolean = false,
    val confirmDialogState: ModuleConfirmDialogState? = null,
) {
    /**
     * 真正与 Magisk 冲突（内核态设备上另有真 Magisk）时，才禁掉模块操作。
     *
     * 等价于 `magiskInstalled && Natives.version > 0`（有驱动应答 = 内核态），外加
     * "GKI 但还没装驱动"这一种：那同样是内核态设备，Magisk 在它上面就是另一套 root 实现。
     */
    val magiskConflict: Boolean
        get() = magiskInstalled && !magiskRoute

    val installButtonVisible: Boolean
        get() = !(isSafeMode || magiskConflict)
}

@Immutable
data class ModuleActions(
    val onRefresh: () -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onRequestUpdateConfirmation: (Module, ModuleUpdateInfo) -> Unit,
    val onRequestUninstallConfirmation: (Module) -> Unit,
    val onDismissConfirmRequest: () -> Unit,
    val onConfirmUpdate: (ModuleConfirmRequest.Update) -> Unit,
    val onOpenRepo: () -> Unit,
    val onToggleSortActionFirst: () -> Unit,
    val onToggleSortEnabledFirst: () -> Unit,
    val onOpenWebUi: (Module) -> Unit,
    val onToggleModule: (Module) -> Unit,
    val onUninstallModule: (Module) -> Unit,
    val onUndoUninstallModule: (Module) -> Unit,
    val onOpenFlash: (List<Uri>) -> Unit,
    val onExecuteModuleAction: (Module) -> Unit,
)

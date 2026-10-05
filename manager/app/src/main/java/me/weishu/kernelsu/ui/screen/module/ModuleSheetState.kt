package me.weishu.kernelsu.ui.screen.module

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.weishu.kernelsu.data.model.Module

/**
 * 模块长按操作面板的全局状态。
 *
 * 面板必须渲染在 MainActivity 根层（视口固定、窗口级），而不是 pager 某一页的
 * 子树里：页面级渲染会被 pager 的翻页几何带走/错位，表现为"长按后不出现、
 * 切到相邻页才冒出来、按钮点不中"。长按发生时由模块页把 Module 对象和
 * 当时的动作闭包（捕获 ModuleActions / ViewModel）一起存进来，根层观察到
 * 非 null 就渲染面板；动作闭包在展示期间保持有效。
 */
object ModuleSheetState {

    var module by mutableStateOf<Module?>(null)
        private set

    var updateUrl by mutableStateOf("")
        private set

    var onExecuteAction: (() -> Unit)? = null
        private set
    var onOpenWebUi: (() -> Unit)? = null
        private set
    var onUpdate: (() -> Unit)? = null
        private set
    var onUninstall: (() -> Unit)? = null
        private set
    var onUndoUninstall: (() -> Unit)? = null
        private set
    var onAddActionShortcut: ((ShortcutType) -> Unit)? = null
        private set

    fun show(
        module: Module,
        updateUrl: String,
        onExecuteAction: () -> Unit,
        onOpenWebUi: () -> Unit,
        onUpdate: () -> Unit,
        onUninstall: () -> Unit,
        onUndoUninstall: () -> Unit,
        onAddActionShortcut: (ShortcutType) -> Unit,
    ) {
        this.module = module
        this.updateUrl = updateUrl
        this.onExecuteAction = onExecuteAction
        this.onOpenWebUi = onOpenWebUi
        this.onUpdate = onUpdate
        this.onUninstall = onUninstall
        this.onUndoUninstall = onUndoUninstall
        this.onAddActionShortcut = onAddActionShortcut
    }

    fun hide() {
        module = null
        updateUrl = ""
        onExecuteAction = null
        onOpenWebUi = null
        onUpdate = null
        onUninstall = null
        onUndoUninstall = null
        onAddActionShortcut = null
    }
}

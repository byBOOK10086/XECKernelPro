package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeRepository

/**
 * 主题屏的状态与动作（Screen / Miuix / UiState 三件套里的一员）。
 *
 * 逻辑全在 `ThemeViewModel`，这里只描述"界面要画什么、能点什么"，UI 侧是纯函数式的。
 */
@Immutable
data class ThemeUiState(
    /** 0 = 商城，1 = 我的，2 = 编辑器。 */
    val tab: Int = 0,
    // ---- 商城 ----
    val store: List<StoreTheme> = emptyList(),
    val storeLoading: Boolean = false,
    val storeError: String? = null,
    val sourceLabel: String = "",
    val fellBack: Boolean = false,
    val query: String = "",
    /** 正在下载的主题 id（同时只会有一个）。 */
    val downloadingId: String? = null,
    val downloadProgress: Int = 0,
    /** 正在应用/删除/导入导出时的忙碌标记（按钮禁用用）。 */
    val busy: Boolean = false,
    // ---- 我的 ----
    val activeId: String? = null,
    val mine: List<ThemeRepository.Entry> = emptyList(),
    // ---- 编辑器 ----
    val editor: ThemeEditorState = ThemeEditorState(),
) {
    val filteredStore: List<StoreTheme>
        get() = if (query.isBlank()) {
            store
        } else {
            val q = query.trim().lowercase()
            store.filter {
                it.name.lowercase().contains(q) ||
                    it.themeId.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.summary.lowercase().contains(q)
            }
        }

    val activeName: String?
        get() = activeId?.let { id -> mine.firstOrNull { it.spec.id == id }?.spec?.name }
}

/**
 * 编辑器的草稿。
 *
 * [spec] 里存的就是"将要写进 theme.json 的东西"：只有用户改过的令牌才会出现在
 * [ThemeCodec.Spec.dark]/[ThemeCodec.Spec.light]/shapes/glass 里，未改的沿用内置默认
 * —— 这样导出的主题包在不同版本的管理器上都是向前兼容的。
 */
@Immutable
data class ThemeEditorState(
    val spec: ThemeCodec.Spec? = null,
    /** 是从哪个已装主题改过来的（用于"覆盖保存"提示）。 */
    val basedOn: String? = null,
    val dirty: Boolean = false,
    /** 未保存前选中的背景图（content:// 字符串）。 */
    val pendingDarkBackground: String? = null,
    val pendingLightBackground: String? = null,
    /**
     * 未保存前选中的**区块背景图**：区块 id -> 图（`content://` 字符串或已装文件的绝对路径）
     * 或**空串**（= 用户在草稿里清掉了这张图，保存时要删掉）。
     *
     * 空串是必须的：`null` 与"没碰过"没法区分，只有三态（碰过并清空 / 碰过并选了一张 /
     * 没碰过）才能让"以已装主题为底稿"这条路径在保存时不把原有图又捞回来。
     */
    val pendingBlocks: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L,
)

/** 主题屏的动作集合。 */
@Immutable
data class ThemeActions(
    val onBack: () -> Unit,
    val onSelectTab: (Int) -> Unit,
    val onRefreshStore: () -> Unit,
    val onSearch: (String) -> Unit,
    val onDownload: (StoreTheme) -> Unit,
    val onApply: (String) -> Unit,
    val onRestoreDefault: () -> Unit,
    val onDelete: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onImport: () -> Unit,
    // 编辑器
    val onStartNew: () -> Unit,
    val onEditFrom: (String) -> Unit,
    val onEditMeta: (name: String?, id: String?, author: String?, version: String?, summary: String?, description: String?) -> Unit,
    /** 模式牌元素：只开放前后字与文字色（模式词系统生成，不可编辑）。 */
    val onEditBadge: (prefix: String?, suffix: String?, textColor: String?) -> Unit,
    val onSetColor: (isDark: Boolean, token: String, hex: String) -> Unit,
    val onResetColor: (isDark: Boolean, token: String) -> Unit,
    val onSetShape: (token: String, dp: Int) -> Unit,
    val onSetGlass: (token: String, value: Float) -> Unit,
    /** 模块页霓虹板（`neon.dark` / `neon.light`）：不写就完全不动那一页。 */
    val onSetNeon: (isDark: Boolean, token: String, hex: String) -> Unit,
    val onResetNeon: (isDark: Boolean, token: String) -> Unit,
    val onPickBackground: (isDark: Boolean) -> Unit,
    val onClearBackground: (isDark: Boolean) -> Unit,
    /**
     * 卡片（区块）背景：`blocks.<区块 id>` 那一条。
     *
     * 四个动作都按区块 id 走（不是按"第几行"）：id 是写进主题包的键，界面上的顺序只是
     * 展示顺序，两者不该互相绑定。
     */
    val onSetBlockColor: (id: String, hex: String) -> Unit,
    val onClearBlockColor: (id: String) -> Unit,
    val onPickBlockImage: (id: String) -> Unit,
    val onClearBlockImage: (id: String) -> Unit,
    /** 透明度 / 黑纱的步进（`-` / `+` 各一条，界面只传方向）。 */
    val onSetBlockNumber: (id: String, token: String, step: Int) -> Unit,
    val onSetBlockWhen: (id: String, mode: String) -> Unit,
    val onResetBlock: (id: String) -> Unit,
    val onSaveEditor: () -> Unit,
    val onPublish: (String) -> Unit,
    /**
     * 商城页顶部的「发布我的主题」入口：打开一个列出本机主题的对话框。
     *
     * 单独留这个动作是因为「发布」原先只藏在「我的主题」每张卡片的按钮里，
     * 用户根本找不到（实际上报的就是这个）。
     */
    val onOpenPublishPicker: () -> Unit,
)

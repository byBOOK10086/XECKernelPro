package me.weishu.kernelsu.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.theme.ThemeApplier
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.data.theme.ThemeResolver
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.design.token.XcBlocks
import me.weishu.kernelsu.ui.screen.theme.ThemeEditorState
import me.weishu.kernelsu.ui.screen.theme.ThemeUiState
import me.weishu.kernelsu.ui.screen.remote.RemoteUploader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主题屏的逻辑：商城拉取/下载/应用/删除、本地主题的导入导出、编辑器草稿与发布。
 *
 * 三条设计约束（都是审计里点出来的）：
 *  1. **不要在主线程做 IO**：拉索引、下载、读写主题包、导入背景图全在 IO 线程；
 *  2. **主题切换靠 prefs**：应用/恢复只写 `theme_id`，`MainActivityViewModel` 监听到之后
 *     整棵树重组 —— 这里不持有任何"当前主题"的 UI 状态之外的副本；
 *  3. **导出/导入走 SAF**：主题包不写公共目录（避免卸载后的残留）。
 */
class ThemeViewModel : ViewModel() {

    private val context: Context get() = ksuApp

    private val _ui = MutableStateFlow(ThemeUiState())
    val ui: StateFlow<ThemeUiState> = _ui.asStateFlow()

    /** 编辑器里"选过但还没保存"的背景图（Uri 字符串）。 */
    private var pendingDark: String? = null
    private var pendingLight: String? = null

    companion object {
        /**
         * 主题包上传到的子目录（`/uploads/pending`）。
         *
         * 主题仓库的 `ingest-pending.yml` 每 10 分钟扫一次这里自动收录，
         * 所以"发布"不需要用户有 GitHub 账号、也不需要打开 github.com。
         */
        private const val PUBLISH_DIR = "pending"

        /** 透明度 / 黑纱的步长：0.05 一档，够细也点得动。 */
        private const val BLOCK_STEP = 0.05f

        /** `onSetBlockNumber` 认的两个 token（界面上就是这两行步进）。 */
        private const val BLOCK_TOKEN_OPACITY = "opacity"
        private const val BLOCK_TOKEN_DIM = "dim"
    }

    init {
        refreshLocal()
    }

    // ------------------------------------------------------------------ 本地

    fun refreshLocal() {
        // 扫目录 + 逐个读 theme.json 是磁盘 IO：init 里直接调会压在主线程上
        // （主题多了以后进"我的"那一页会掉帧），统一扔到 IO 再回主线程更新状态。
        viewModelScope.launch {
            val active = withContext(Dispatchers.IO) { ThemeRepository.activeId(context) }
            val mine = withContext(Dispatchers.IO) { ThemeRepository.list(context) }
            _ui.update { it.copy(activeId = active, mine = mine) }
        }
    }

    fun selectTab(tab: Int) = _ui.update { it.copy(tab = tab.coerceIn(0, 2)) }

    fun apply(id: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = ThemeApplier.apply(context, id)
            ThemeResolver.invalidate()
            _ui.update { state ->
                state.copy(
                    busy = false,
                    activeId = ThemeRepository.activeId(context),
                    mine = ThemeRepository.list(context),
                )
            }
            onDone(
                result.isSuccess,
                result.exceptionOrNull()?.let { it.message ?: it.toString() },
            )
        }
    }

    fun restoreDefault(onDone: (Boolean) -> Unit = {}) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = ThemeApplier.restoreDefault(context)
            ThemeResolver.invalidate()
            _ui.update { it.copy(busy = false, activeId = ThemeRepository.activeId(context)) }
            onDone(result.isSuccess)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ThemeRepository.remove(context, id) }
            ThemeResolver.invalidate()
            refreshLocal()
        }
    }

    /** 导出成字节；由界面通过 SAF 写出去（不落公共目录）。 */
    fun exportBytes(id: String): ByteArray? = runCatching { ThemeRepository.export(context, id) }.getOrNull()

    fun exportFile(id: String): String? =
        ThemeRepository.find(context, id)?.spec?.let { "${it.id}-${it.versionCode}.xectheme" }

    /** 导入一个 `.xectheme`（字节来自 SAF）。 */
    fun importPackage(bytes: ByteArray, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ThemeRepository.install(context, bytes, ThemeRepository.Origin.IMPORTED)
                }
            }
            refreshLocal()
            onDone(result.isSuccess, result.exceptionOrNull()?.let { it.message ?: it.toString() })
        }
    }

    /**
     * 发布：把主题包传到 XEC 网盘，返回直链（调用方把它展示/复制给用户）。
     *
     * 走 OpenList 的专用上传账号（与远程模块助手同一条通道），所以**不需要 GitHub 账号**。
     * 包落在 `/uploads/pending/`：主题仓库的定时任务（`ingest-pending.yml`，每 10 分钟一轮）
     * 扫这个目录、校验、按 sha256 去重后自动收进索引 —— **不用去 GitHub 开 issue**。
     * 直链同样可以贴进 issue 表单，那条路仍然保留（想留言/想要回执时用）。
     */
    fun publish(id: String, onDone: (Boolean, String?) -> Unit) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ThemeRepository.export(context, id)
                    val name = exportFile(id) ?: "$id.xectheme"
                    val tmp = File(context.cacheDir, name)
                    tmp.writeBytes(bytes)
                    try {
                        RemoteUploader.upload(
                            context,
                            Uri.fromFile(tmp),
                            name,
                            { _, _ -> },
                            dir = PUBLISH_DIR,
                        )
                    } finally {
                        tmp.delete()
                    }
                }
            }
            _ui.update { it.copy(busy = false) }
            onDone(result.isSuccess, result.getOrNull() ?: result.exceptionOrNull()?.message)
        }
    }

    // ------------------------------------------------------------------ 编辑器

    fun startNew() {
        pendingDark = null
        pendingLight = null
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        _ui.update { state ->
            state.copy(
                tab = 2,
                editor = ThemeEditorState(
                    spec = ThemeCodec.Spec(
                        id = "my-theme",
                        name = "",
                        version = "1.0",
                        versionCode = (System.currentTimeMillis() / 1000).toInt(),
                        createdAt = now,
                    ),
                    basedOn = null,
                    dirty = false,
                ),
            )
        }
    }

    /** 以某个已装主题为底稿继续改（"改别人的主题"这条路径）。 */
    fun editFrom(id: String) {
        val entry = ThemeRepository.find(context, id) ?: return
        pendingDark = ThemeRepository.backgroundDark(context, id)?.absolutePath
        pendingLight = ThemeRepository.backgroundLight(context, id)?.absolutePath
        // 区块图也接着改：主题目录里的那张图就是"草稿里已有的那张"，保存时按原路径读回来。
        val pendingBlocks = LinkedHashMap<String, String>()
        entry.spec.blocks.forEach { (blockId, block) ->
            val name = block.image ?: return@forEach
            val file = File(entry.dir, name)
            if (file.isFile) pendingBlocks[blockId] = file.absolutePath
        }
        _ui.update { state ->
            state.copy(
                tab = 2,
                editor = ThemeEditorState(
                    spec = entry.spec,
                    basedOn = id,
                    dirty = false,
                    pendingBlocks = pendingBlocks,
                ),
            )
        }
    }

    fun editMeta(
        name: String? = null,
        id: String? = null,
        author: String? = null,
        version: String? = null,
        summary: String? = null,
        description: String? = null,
    ) = mutateSpec { spec ->
        spec.copy(
            name = name ?: spec.name,
            id = (id ?: spec.id).trim(),
            author = author ?: spec.author,
            version = version ?: spec.version,
            summary = summary ?: spec.summary,
            description = description ?: spec.description,
        )
    }

    fun setColor(isDark: Boolean, token: String, hex: String) = mutateSpec { spec ->
        if (isDark) spec.copy(dark = spec.dark + (token to hex)) else spec.copy(light = spec.light + (token to hex))
    }

    /**
     * 编辑模式牌元素（前后字 + 文字色）。模式词本身（LKM/GKI/KernelPatch）是系统
     * 生成的，编辑器只开放前后缀 —— 与首页外观对话框同一红线。前后缀与颜色全空
     * 且元素没有图/字体时，视为移除该元素（写出的包 schema 回 1）。
     */
    fun editBadge(prefix: String?, suffix: String?, textColor: String?) = mutateSpec { spec ->
        val old = spec.modeBadge ?: ThemeCodec.ModeBadgeSpec()
        val next = old.copy(
            prefix = (prefix ?: old.prefix).trim(),
            suffix = (suffix ?: old.suffix).trim(),
            textColor = textColor ?: old.textColor,
        )
        val cleared = next.prefix.isBlank() && next.suffix.isBlank() &&
            next.textColor.isNullOrBlank() && next.image.isNullOrEmpty() && next.font.isNullOrEmpty()
        spec.copy(modeBadge = if (cleared) null else next)
    }

    fun resetColor(isDark: Boolean, token: String) = mutateSpec { spec ->
        if (isDark) spec.copy(dark = spec.dark - token) else spec.copy(light = spec.light - token)
    }

    fun setShape(token: String, dp: Int) = mutateSpec { spec -> spec.copy(shapes = spec.shapes + (token to dp)) }

    fun setGlass(token: String, value: Float) = mutateSpec { spec -> spec.copy(glass = spec.glass + (token to value)) }

    fun setNeon(isDark: Boolean, token: String, hex: String) = mutateSpec { spec ->
        if (isDark) spec.copy(neonDark = spec.neonDark + (token to hex)) else spec.copy(neonLight = spec.neonLight + (token to hex))
    }

    fun resetNeon(isDark: Boolean, token: String) = mutateSpec { spec ->
        if (isDark) spec.copy(neonDark = spec.neonDark - token) else spec.copy(neonLight = spec.neonLight - token)
    }

    fun pickBackground(isDark: Boolean, uri: Uri?) {
        if (isDark) pendingDark = uri?.toString() else pendingLight = uri?.toString()
        mutateSpec { it }
    }

    fun clearBackground(isDark: Boolean) {
        if (isDark) pendingDark = null else pendingLight = null
        mutateSpec { spec ->
            if (isDark) spec.copy(backgroundDark = null) else spec.copy(backgroundLight = null)
        }
    }

    // ------------------------------------------------------------------ 区块背景

    /**
     * 区块背景：`blocks.<id>` 那一条。
     *
     * 所有动作只认 [XcBlocks.HOME_ALL] 里的 id：界面上列的就是这六个，草稿里不该出现
     * 第七个 —— 认不出的 id 会被写进主题包，然后在别的管理器上变成一条永远不生效的配置。
     */
    fun setBlockColor(id: String, hex: String) = mutateBlock(id) { it.copy(color = hex) }

    fun clearBlockColor(id: String) = mutateBlock(id) { it.copy(color = null) }

    fun clearBlockImage(id: String) {
        // 先改草稿、再记 pending：两件事各自是一次状态更新，不嵌套（嵌套会让内层那次
        // 更新基于外层还没提交的中间状态）。
        mutateBlock(id) { block -> block.copy(image = null) }
        markBlockPending(id, "")
    }

    /** 选过但还没保存的图：记在草稿里（`content://`），保存时才读成字节写进主题目录。 */
    fun pickBlockImage(id: String, uri: Uri?) {
        val image = uri?.toString() ?: return
        // 这里只记 pending：spec 的 `image` 由保存时的 `ThemeRepository.install` 归一成
        // 派生名（`blockAssetName`），编辑期间 spec 不该指向一个还不存在的文件。
        if (id !in XcBlocks.HOME_ALL) return
        markBlockPending(id, image)
    }

    /** `token` 只认 `opacity` / `dim`；[step] 是 ±1 个 0.05。 */
    fun setBlockNumber(id: String, token: String, step: Int) = mutateBlock(id) { block ->
        when (token) {
            BLOCK_TOKEN_DIM -> block.copy(dim = stepNumber(block.dim, step))
            BLOCK_TOKEN_OPACITY -> block.copy(opacity = stepNumber(block.opacity, step))
            else -> block
        }
    }

    /**
     * 明暗档：只认三档里的两档（第三种就是默认的 `both`）。
     *
     * 传进来的 mode 来自界面上的按钮，但**不信任**它：认不出的取值一律落回 `both`，
     * 免得把一个界面上的笔误写进主题包（包里的 `when` 只有三个合法值）。
     */
    fun setBlockWhen(id: String, mode: String) = mutateBlock(id) { block ->
        val safe = when (mode) {
            ThemeCodec.BLOCK_DARK, ThemeCodec.BLOCK_LIGHT -> mode
            else -> ThemeCodec.BLOCK_BOTH
        }
        block.copy(mode = safe)
    }

    /** 重置一整个区块：颜色、图、透明度、黑纱、明暗档全部回到"没设过"。 */
    fun resetBlock(id: String) {
        mutateSpec { spec -> spec.copy(blocks = spec.blocks - id) }
        markBlockPending(id, "")
    }

    /**
     * 草稿里那条区块背景（没有就现造一条空的）。
     *
     * 现造一条是"用户点开就在改"的语义：只要动过任何一个旋钮，这个 id 就该出现在
     * `blocks` 里；而"什么都没设"的条目在 `toJson` 里也不会被写出去（见 `ThemeCodec`），
     * 所以不会留下空壳。
     */
    private fun mutateBlock(id: String, block: (ThemeCodec.BlockSpec) -> ThemeCodec.BlockSpec) {
        if (id !in XcBlocks.HOME_ALL) return
        mutateSpec { spec ->
            val current = spec.blocks[id] ?: ThemeCodec.BlockSpec()
            spec.copy(blocks = spec.blocks + (id to block(current)))
        }
    }

    /** 记一笔"这张图在草稿里被换掉了"；空串 = 被清掉了。 */
    private fun markBlockPending(id: String, value: String) {
        _ui.update { state ->
            val editor = state.editor
            state.copy(editor = editor.copy(pendingBlocks = editor.pendingBlocks + (id to value)))
        }
    }

    /** 按 0.05 取整地加减，避免浮点累积出 0.6500001 这种值；结果夹在 0..1。 */
    private fun stepNumber(current: Float, step: Int): Float {
        val steps = (current / BLOCK_STEP).roundToInt() + step
        return (steps * BLOCK_STEP).coerceIn(0f, 1f)
    }

    /**
     * 保存草稿：把（可能刚选的）背景图与区块图读成字节一起写进包。
     *
     * 区块图走 [ThemeCodec.blockAssetName] 派生出来的名字（`block-home-status.jpg`），
     * 与渲染侧查文件的名字**同一个来源**，所以"保存完却看不到"这种问题不可能出现。
     */
    fun saveEditor(onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val editor = _ui.value.editor
        val draft = editor.spec ?: return
        val pendingBlocks = editor.pendingBlocks
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val backgrounds = LinkedHashMap<String, ByteArray>()
                    val assets = LinkedHashMap<String, ByteArray>()
                    pendingDark?.let { path ->
                        val bytes = readImage(path)
                        if (bytes != null) {
                            backgrounds["background-dark.jpg"] = bytes
                            assets["background-dark.jpg"] = bytes
                        }
                    }
                    pendingLight?.let { path ->
                        val bytes = readImage(path)
                        if (bytes != null) {
                            backgrounds["background-light.jpg"] = bytes
                            assets["background-light.jpg"] = bytes
                        }
                    }
                    // 区块图：草稿里记过"换过/清过"的那些才动，其余沿用主题目录里已有的那张。
                    val settled = draft.blocks.toMutableMap()
                    pendingBlocks.forEach { (blockId, path) ->
                        val current = settled[blockId]
                        if (path.isEmpty()) {
                            // 用户在草稿里清掉了这张图：spec 里也不能再留着那个文件名。
                            if (current != null) settled[blockId] = current.copy(image = null)
                            return@forEach
                        }
                        val bytes = readImage(path)
                        if (bytes == null) {
                            if (current != null) settled[blockId] = current.copy(image = null)
                            return@forEach
                        }
                        val name = ThemeCodec.blockAssetName(blockId)
                        backgrounds[name] = bytes
                        assets[name] = bytes
                        settled[blockId] = (current ?: ThemeCodec.BlockSpec()).copy(image = name)
                    }
                    // 既没颜色也没图（只在草稿里被"摸"过一下、或者图没读出来）的条目不留：
                    // `ThemeCodec.toJson` 本来也不会写它，但留在草稿状态里只会让界面继续
                    // 显示一条空配置。
                    settled.entries.removeAll { it.value.color == null && it.value.image == null }
                    val finalSpec = draft.copy(
                        backgroundDark = if (backgrounds.containsKey("background-dark.jpg")) {
                            "background-dark.jpg"
                        } else {
                            draft.backgroundDark
                        },
                        backgroundLight = if (backgrounds.containsKey("background-light.jpg")) {
                            "background-light.jpg"
                        } else {
                            draft.backgroundLight
                        },
                        blocks = settled,
                    )
                    ThemeRepository.install(
                        context,
                        finalSpec,
                        ThemeCodec.Assets(files = assets),
                        ThemeRepository.Origin.LOCAL,
                    )
                    ThemeRepository.find(context, finalSpec.id)?.spec ?: finalSpec
                }
            }
            ThemeResolver.invalidate()
            refreshLocal()
            // 保存成功后把"草稿里的临时改动"清掉（界面依据它画"已选/已清"的标记）；
            // 失败就留着，用户不用重新选一遍图。
            val saved = result.getOrNull()
            if (saved != null) {
                // 紧接着再保存一次时不能再读那两个 content://（一次性读权限已经用过了）：
                // 改成指向刚落地的那份文件，与 editFrom 的形态一致。
                pendingDark = ThemeRepository.backgroundDark(context, saved.id)?.absolutePath
                pendingLight = ThemeRepository.backgroundLight(context, saved.id)?.absolutePath
            }
            _ui.update { state ->
                state.copy(
                    busy = false,
                    editor = if (saved != null) {
                        state.editor.copy(
                            spec = saved,
                            pendingBlocks = emptyMap(),
                            dirty = false,
                            savedAt = System.currentTimeMillis(),
                        )
                    } else {
                        state.editor
                    },
                )
            }
            onDone(result.isSuccess, saved?.name ?: result.exceptionOrNull()?.let { it.message ?: it.toString() })
        }
    }

    /** 校验草稿：id 合法、名字非空。返回错误文案 key（由界面翻译）。 */
    fun validateEditor(): String? {
        val spec = _ui.value.editor.spec ?: return "empty"
        if (!Regex("^[a-zA-Z][a-zA-Z0-9._-]{1,63}$").matches(spec.id)) return "id"
        if (spec.name.isBlank()) return "name"
        return null
    }

    private fun mutateSpec(block: (ThemeCodec.Spec) -> ThemeCodec.Spec) {
        _ui.update { state ->
            val spec = state.editor.spec ?: return@update state
            state.copy(editor = state.editor.copy(spec = block(spec), dirty = true))
        }
    }

    /**
     * 读一张待打包的背景图。
     *
     * 两种来源都要认：编辑器里新选的（`content://`，得走 contentResolver）与
     * "以已装主题为底稿"时已有的文件（绝对路径）。两者都读成字节交给主题包。
     */
    private fun readImage(reference: String): ByteArray? = runCatching {
        if (reference.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(reference))?.use { it.readBytes() }
        } else {
            val file = File(reference)
            if (file.isFile) file.readBytes() else null
        }
    }.getOrNull()
}

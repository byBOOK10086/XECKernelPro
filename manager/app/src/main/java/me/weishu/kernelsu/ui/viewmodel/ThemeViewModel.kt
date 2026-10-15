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
import me.weishu.kernelsu.data.theme.StoreTheme
import me.weishu.kernelsu.data.theme.ThemeApplier
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.data.theme.ThemeResolver
import me.weishu.kernelsu.data.theme.ThemeStoreRepository
import me.weishu.kernelsu.data.theme.ThemeStoreSettings
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.theme.ThemeEditorState
import me.weishu.kernelsu.ui.screen.theme.ThemeUiState
import me.weishu.kernelsu.ui.screen.remote.RemoteUploader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    }

    init {
        refreshLocal()
        loadStore()
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

    fun search(text: String) = _ui.update { it.copy(query = text) }

    // ------------------------------------------------------------------ 商城

    fun loadStore() {
        _ui.update { it.copy(storeLoading = true, storeError = null) }
        viewModelScope.launch {
            val result = ThemeStoreRepository.fetch(context)
            _ui.update { state ->
                result.fold(
                    onSuccess = { list ->
                        state.copy(
                            store = list,
                            storeLoading = false,
                            storeError = null,
                            sourceLabel = ThemeStoreSettings.labelOf(
                                ThemeStoreRepository.lastSource ?: ThemeStoreSettings.primaryUrl
                            ),
                            fellBack = ThemeStoreRepository.lastSource != null &&
                                ThemeStoreRepository.lastSource != ThemeStoreSettings.primaryUrl,
                            mine = ThemeRepository.list(context),
                            activeId = ThemeRepository.activeId(context),
                        )
                    },
                    onFailure = { error ->
                        state.copy(storeLoading = false, storeError = error.message ?: error.toString())
                    },
                )
            }
        }
    }

    /** 下载并安装；成功后刷新"我的"。 */
    fun download(theme: StoreTheme) {
        if (_ui.value.downloadingId != null) return
        _ui.update { it.copy(downloadingId = theme.themeId, downloadProgress = 0) }
        viewModelScope.launch {
            val result = ThemeStoreRepository.downloadAndInstall(context, theme) { read, total ->
                if (total > 0) {
                    val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                    _ui.update { state ->
                        if (state.downloadingId == theme.themeId) {
                            state.copy(downloadProgress = percent)
                        } else {
                            state
                        }
                    }
                }
            }
            _ui.update { state ->
                state.copy(
                    downloadingId = null,
                    downloadProgress = 0,
                    storeError = result.exceptionOrNull()?.let { it.message ?: it.toString() },
                    mine = ThemeRepository.list(context),
                )
            }
        }
    }

    fun apply(id: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = ThemeApplier.apply(context, id)
            ThemeResolver.invalidate()
            _ui.update { state ->
                state.copy(
                    busy = false,
                    activeId = ThemeRepository.activeId(context),
                    storeError = result.exceptionOrNull()?.let { it.message ?: it.toString() },
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
            _ui.update { it.copy(busy = false, storeError = result.exceptionOrNull()?.message) }
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
        _ui.update { state ->
            state.copy(
                tab = 2,
                editor = ThemeEditorState(spec = entry.spec, basedOn = id, dirty = false),
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

    /** 保存草稿：把（可能刚选的）背景图读成字节一起写进包。 */
    fun saveEditor(onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val editor = _ui.value.editor
        val spec = editor.spec ?: return
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
                    val darkName = if (backgrounds.containsKey("background-dark.jpg")) "background-dark.jpg" else spec.backgroundDark
                    val lightName = if (backgrounds.containsKey("background-light.jpg")) "background-light.jpg" else spec.backgroundLight
                    val finalSpec = spec.copy(backgroundDark = darkName, backgroundLight = lightName)
                    ThemeRepository.install(
                        context,
                        finalSpec,
                        ThemeCodec.Assets(files = assets),
                        ThemeRepository.Origin.LOCAL,
                    )
                    finalSpec.name
                }
            }
            ThemeResolver.invalidate()
            refreshLocal()
            _ui.update { state ->
                state.copy(
                    busy = false,
                    editor = state.editor.copy(dirty = false, savedAt = System.currentTimeMillis()),
                )
            }
            onDone(result.isSuccess, result.getOrNull() ?: result.exceptionOrNull()?.let { it.message ?: it.toString() })
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

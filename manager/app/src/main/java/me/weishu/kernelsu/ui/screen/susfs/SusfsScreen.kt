package me.weishu.kernelsu.ui.screen.susfs

/*
 * SUSFS 管理页（2026-11-18 用户要求：与 SukiSU Nova 同款的 SUSFS 管理）。
 *
 * 数据全部来自守护进程，管理器不自己造状态：
 *   - `ksud susfs version / variant / features`：内核侧的 SUSFS 身份与能力；
 *   - `ksud susfs config list`：持久配置（`/data/adb/ksu/susfs_config`）的 key=value 快照；
 *   - `ksud susfs config set <key> <value>`：写配置并**自动同步 SUSFS 启动模块**
 *     （cli.rs 里 set 会调 susfs_module::sync_module_from_config，失败会回滚）。
 *
 * 因此这一页没有"本地乐观修改"：每次写都是一次命令往返，成功后重新 list 一遍，
 * 失败就把 CLI 的错误原文显示出来——比把开关翻过去再悄悄回弹诚实。
 *
 * 生效时机要说清楚：配置本身是持久层，运行中的内核状态由 SUSFS 模块在开机
 * （post-fs-data）时落实，所以页面底部固定给一行"改完建议重启"。
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Note
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8SectionLabel
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.design.panel.Xc8Value
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.execKsud
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.shQuote
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

// key 名与 userspace/ksud/src/susfs_config.rs 里的常量一一对应，改名要两边一起改。
private const val KEY_UNAME = "uname_value"
private const val KEY_BUILD_TIME = "build_time_value"
private const val KEY_AUTO_START = "auto_start_enabled"
private const val KEY_SUSFS_ENABLED = "susfs_enabled"
private const val KEY_SUS_PATHS = "sus_paths"
private const val KEY_SUS_LOOP_PATHS = "sus_loop_paths"
private const val KEY_SUS_MAPS = "sus_maps"
private const val KEY_ENABLE_LOG = "enable_log"
private const val KEY_EXECUTE_IN_POST_FS_DATA = "execute_in_post_fs_data"
private const val KEY_KSTAT_CONFIGS = "kstat_configs"
private const val KEY_ADD_KSTAT_PATHS = "add_kstat_paths"
private const val KEY_HIDE_SUS_MOUNTS = "hide_sus_mounts_for_all_procs"
private const val KEY_CLEANUP_RESIDUE = "enable_cleanup_residue"
private const val KEY_HIDE_BL = "enable_hide_bl"
private const val KEY_AVC_LOG_SPOOFING = "enable_avc_log_spoofing"

/** 多值 key 的分隔符：susfs_config 用 `;` 存路径、用 `;;` 存 kstat 配置。 */
private const val PATH_SEP = ";"
private const val KSTAT_SEP = ";;"

/** 一条 susfs CLI 写操作的上限。root shell 卡死时用来解锁 busy。 */
private const val SUSFS_CMD_TIMEOUT_MS = 20_000L

private data class SusfsSnapshot(
    val version: String = "",
    val variant: String = "",
    val features: String = "",
    val config: Map<String, String> = emptyMap(),
) {
    fun flag(key: String, default: Boolean = false): Boolean =
        when (config[key]) {
            null -> default
            "true" -> true
            "false" -> false
            else -> default
        }

    fun text(key: String): String = config[key].orEmpty()
}

private suspend fun loadSnapshot(): SusfsSnapshot = withContext(Dispatchers.IO) {
    val shell = runCatching { getRootShell() }.getOrNull()
        ?: return@withContext SusfsSnapshot()
    if (!shell.isRoot) return@withContext SusfsSnapshot()
    fun cmd(args: String): String =
        runCatching { ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $args") }
            .getOrDefault("")
            .trim()
    val config = cmd("susfs config list").lineSequence()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index > 0) line.substring(0, index) to line.substring(index + 1) else null
        }
        .toMap()
    SusfsSnapshot(
        version = cmd("susfs version"),
        variant = cmd("susfs variant"),
        features = cmd("susfs features"),
        config = config,
    )
}

private fun splitList(raw: String, separator: String): List<String> =
    if (raw.isEmpty()) emptyList() else raw.split(separator).filter { it.isNotEmpty() }

@Composable
fun SusfsScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<SusfsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<String?>(null) }
    var editingList by remember { mutableStateOf<String?>(null) }
    var showReset by remember { mutableStateOf(false) }

    // 这些文案在协程/dialog 回调里用，必须先在这里（组合上下文）取出来：
    // stringResource 只能在 @Composable 里调用。
    val savedText = stringResource(R.string.susfs_saved)
    val failedText = stringResource(R.string.susfs_save_failed)
    val loadingText = stringResource(R.string.susfs_loading)
    val noRootText = stringResource(R.string.susfs_no_root)
    val defaultText = stringResource(R.string.susfs_default_value)

    suspend fun refresh() {
        loading = true
        try {
            snapshot = loadSnapshot()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 一次写入 = 一条 CLI + 一次回读。不做本地乐观更新：失败时开关不会"假装成功又弹回"。
    fun write(key: String, value: String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                // 超时必须兜底：root shell 卡住时 busy 会永远置位，整个页面所有开关
                // 变成 enabled=false（真机反馈“SUSFS 界面点不动”就是这么来的）。
                val ok = withTimeoutOrNull(SUSFS_CMD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { execKsud("susfs config set $key ${shQuote(value)}", true) }
                } == true
                snapshot = loadSnapshot()
                message = if (ok) savedText else failedText
            } finally {
                busy = false
            }
        }
    }

    fun toggle(key: String, current: Boolean) {
        write(key, if (current) "false" else "true")
    }

    val resetConfirm = rememberConfirmDialog(
        onConfirm = {
            if (!busy) {
                busy = true
                message = null
                scope.launch {
                    try {
                        val ok = withTimeoutOrNull(SUSFS_CMD_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) { execKsud("susfs config reset", true) }
                        } == true
                        snapshot = loadSnapshot()
                        message = if (ok) savedText else failedText
                    } finally {
                        busy = false
                    }
                }
            }
        }
    )
    if (showReset) {
        resetConfirm.showConfirm(
            title = stringResource(R.string.susfs_reset),
            content = stringResource(R.string.susfs_reset_confirm),
        )
        showReset = false
    }

    val state = snapshot
    val version = state?.version.orEmpty()
    val variant = state?.variant.orEmpty()
    val features = state?.features.orEmpty()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.susfs_manager_title),
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
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
                    // 内核身份：版本 / 变体 / 能力。空字符串 = 内核没打 SUSFS 补丁或探测失败。
                    Xc8SectionLabel(text = stringResource(R.string.susfs_section_kernel))
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.susfs_version),
                            subtitle = version.ifEmpty { if (loading) loadingText else noRootText },
                            leading = Icons.Rounded.AdminPanelSettings,
                        )
                        if (variant.isNotEmpty()) {
                            Xc8Row(
                                title = stringResource(R.string.susfs_variant),
                                subtitle = variant,
                                leading = Icons.Rounded.SettingsSuggest,
                            )
                        }
                        if (features.isNotEmpty()) {
                            Xc8Row(
                                title = stringResource(R.string.susfs_features),
                                subtitle = features,
                                leading = Icons.Rounded.Tune,
                            )
                        }
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.susfs_section_switch),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.susfs_opt_enabled),
                            subtitle = stringResource(R.string.susfs_opt_enabled_summary),
                            leading = Icons.Rounded.AdminPanelSettings,
                            onClick = { state?.let { toggle(KEY_SUSFS_ENABLED, it.flag(KEY_SUSFS_ENABLED, true)) } },
                            enabled = state != null && !busy,
                            trailing = {
                                Xc8Switch(
                                    checked = state?.flag(KEY_SUSFS_ENABLED, true) == true,
                                    enabled = state != null && !busy,
                                    onCheckedChange = {
                                        toggle(KEY_SUSFS_ENABLED, state?.flag(KEY_SUSFS_ENABLED, true) == true)
                                    },
                                )
                            },
                        )
                        Xc8Row(
                            title = stringResource(R.string.susfs_opt_auto_start),
                            subtitle = stringResource(R.string.susfs_opt_auto_start_summary),
                            leading = Icons.Rounded.RestartAlt,
                            onClick = { state?.let { toggle(KEY_AUTO_START, it.flag(KEY_AUTO_START)) } },
                            enabled = state != null && !busy,
                            trailing = {
                                Xc8Switch(
                                    checked = state?.flag(KEY_AUTO_START) == true,
                                    enabled = state != null && !busy,
                                    onCheckedChange = {
                                        toggle(KEY_AUTO_START, state?.flag(KEY_AUTO_START) == true)
                                    },
                                )
                            },
                        )
                        Xc8Row(
                            title = stringResource(R.string.susfs_opt_early),
                            subtitle = stringResource(R.string.susfs_opt_early_summary),
                            leading = Icons.Rounded.RestartAlt,
                            onClick = {
                                state?.let { toggle(KEY_EXECUTE_IN_POST_FS_DATA, it.flag(KEY_EXECUTE_IN_POST_FS_DATA)) }
                            },
                            enabled = state != null && !busy,
                            trailing = {
                                Xc8Switch(
                                    checked = state?.flag(KEY_EXECUTE_IN_POST_FS_DATA) == true,
                                    enabled = state != null && !busy,
                                    onCheckedChange = {
                                        toggle(
                                            KEY_EXECUTE_IN_POST_FS_DATA,
                                            state?.flag(KEY_EXECUTE_IN_POST_FS_DATA) == true,
                                        )
                                    },
                                )
                            },
                        )
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.susfs_section_spoof),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(R.string.susfs_opt_uname),
                            subtitle = state?.text(KEY_UNAME)?.ifEmpty { defaultText },
                            leading = Icons.Rounded.Smartphone,
                            onClick = if (state != null && !busy) {
                                { editingText = KEY_UNAME }
                            } else null,
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(R.string.susfs_opt_build_time),
                            subtitle = state?.text(KEY_BUILD_TIME)?.ifEmpty { defaultText },
                            leading = Icons.Rounded.Badge,
                            onClick = if (state != null && !busy) {
                                { editingText = KEY_BUILD_TIME }
                            } else null,
                            trailing = { Xc8Chevron() },
                        )
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.susfs_section_hide),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        listRow(
                            title = stringResource(R.string.susfs_opt_sus_paths),
                            raw = state?.text(KEY_SUS_PATHS).orEmpty(),
                            separator = PATH_SEP,
                            enabled = state != null && !busy,
                            onEdit = { editingList = KEY_SUS_PATHS },
                        )
                        listRow(
                            title = stringResource(R.string.susfs_opt_sus_loop_paths),
                            raw = state?.text(KEY_SUS_LOOP_PATHS).orEmpty(),
                            separator = PATH_SEP,
                            enabled = state != null && !busy,
                            onEdit = { editingList = KEY_SUS_LOOP_PATHS },
                        )
                        listRow(
                            title = stringResource(R.string.susfs_opt_sus_maps),
                            raw = state?.text(KEY_SUS_MAPS).orEmpty(),
                            separator = PATH_SEP,
                            enabled = state != null && !busy,
                            onEdit = { editingList = KEY_SUS_MAPS },
                        )
                        listRow(
                            title = stringResource(R.string.susfs_opt_add_kstat_paths),
                            raw = state?.text(KEY_ADD_KSTAT_PATHS).orEmpty(),
                            separator = PATH_SEP,
                            enabled = state != null && !busy,
                            onEdit = { editingList = KEY_ADD_KSTAT_PATHS },
                        )
                        listRow(
                            title = stringResource(R.string.susfs_opt_kstat_configs),
                            raw = state?.text(KEY_KSTAT_CONFIGS).orEmpty(),
                            separator = KSTAT_SEP,
                            enabled = state != null && !busy,
                            onEdit = { editingList = KEY_KSTAT_CONFIGS },
                        )
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.susfs_section_misc),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        optionRow(
                            title = stringResource(R.string.susfs_opt_hide_sus_mounts),
                            key = KEY_HIDE_SUS_MOUNTS,
                            default = true,
                            state = state,
                            busy = busy,
                            onToggle = { toggle(KEY_HIDE_SUS_MOUNTS, state?.flag(KEY_HIDE_SUS_MOUNTS, true) == true) },
                        )
                        optionRow(
                            title = stringResource(R.string.susfs_opt_hide_bl),
                            key = KEY_HIDE_BL,
                            default = true,
                            state = state,
                            busy = busy,
                            onToggle = { toggle(KEY_HIDE_BL, state?.flag(KEY_HIDE_BL, true) == true) },
                        )
                        optionRow(
                            title = stringResource(R.string.susfs_opt_cleanup_residue),
                            key = KEY_CLEANUP_RESIDUE,
                            default = false,
                            state = state,
                            busy = busy,
                            onToggle = { toggle(KEY_CLEANUP_RESIDUE, state?.flag(KEY_CLEANUP_RESIDUE) == true) },
                        )
                        optionRow(
                            title = stringResource(R.string.susfs_opt_enable_log),
                            key = KEY_ENABLE_LOG,
                            default = false,
                            state = state,
                            busy = busy,
                            onToggle = { toggle(KEY_ENABLE_LOG, state?.flag(KEY_ENABLE_LOG) == true) },
                        )
                        optionRow(
                            title = stringResource(R.string.susfs_opt_avc_log_spoofing),
                            key = KEY_AVC_LOG_SPOOFING,
                            default = false,
                            state = state,
                            busy = busy,
                            onToggle = { toggle(KEY_AVC_LOG_SPOOFING, state?.flag(KEY_AVC_LOG_SPOOFING) == true) },
                        )
                    }

                    message?.let {
                        Xc8Note(text = it, modifier = Modifier.padding(top = 12.dp))
                    }
                    Xc8Panel(modifier = Modifier.padding(top = 12.dp)) {
                        Xc8Row(
                            title = stringResource(R.string.susfs_reset),
                            subtitle = stringResource(R.string.susfs_reset_summary),
                            leading = Icons.Rounded.Delete,
                            onClick = if (state != null && !busy) {
                                { showReset = true }
                            } else null,
                            trailing = { Xc8Chevron() },
                        )
                    }
                    Xc8Note(
                        text = stringResource(R.string.susfs_need_reboot),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    val editingKey = editingText
    if (editingKey != null) {
        val title = when (editingKey) {
            KEY_UNAME -> stringResource(R.string.susfs_opt_uname)
            else -> stringResource(R.string.susfs_opt_build_time)
        }
        TextValueDialog(
            title = title,
            initial = snapshot?.text(editingKey).orEmpty(),
            onDismiss = { editingText = null },
            onSave = { value ->
                editingText = null
                write(editingKey, value)
            },
        )
    }
    val listKey = editingList
    if (listKey != null) {
        val title = when (listKey) {
            KEY_SUS_PATHS -> stringResource(R.string.susfs_opt_sus_paths)
            KEY_SUS_LOOP_PATHS -> stringResource(R.string.susfs_opt_sus_loop_paths)
            KEY_SUS_MAPS -> stringResource(R.string.susfs_opt_sus_maps)
            KEY_ADD_KSTAT_PATHS -> stringResource(R.string.susfs_opt_add_kstat_paths)
            else -> stringResource(R.string.susfs_opt_kstat_configs)
        }
        val separator = if (listKey == KEY_KSTAT_CONFIGS) KSTAT_SEP else PATH_SEP
        PathListDialog(
            title = title,
            separator = separator,
            initial = splitList(snapshot?.text(listKey).orEmpty(), separator),
            onDismiss = { editingList = null },
            onSave = { entries ->
                editingList = null
                write(listKey, entries.joinToString(separator))
            },
        )
    }
}

/** 路径列表行：只显示条数，点开进编辑器。 */
@Composable
private fun listRow(
    title: String,
    raw: String,
    separator: String,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val count = splitList(raw, separator).size
    Xc8Row(
        title = title,
        subtitle = when {
            count > 0 -> stringResource(R.string.susfs_list_count, count)
            else -> stringResource(R.string.susfs_empty_list)
        },
        leading = Icons.Rounded.Folder,
        enabled = enabled,
        onClick = if (enabled) onEdit else null,
        trailing = { Xc8Chevron() },
    )
}

/** 布尔项行：开关与整行共用同一个入口。 */
@Composable
private fun optionRow(
    title: String,
    key: String,
    default: Boolean,
    state: SusfsSnapshot?,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    Xc8Row(
        title = title,
        leading = Icons.Rounded.BugReport,
        enabled = state != null && !busy,
        onClick = if (state != null && !busy) onToggle else null,
        trailing = {
            Xc8Switch(
                checked = state?.flag(key, default) == true,
                enabled = state != null && !busy,
                onCheckedChange = { onToggle() },
            )
        },
    )
}

/** 单值文本编辑（uname / build time）。 */
@Composable
private fun TextValueDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    XDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            XDialogTitle(text = title)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.susfs_edit_hint),
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.susfs_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.susfs_apply),
                    onClick = { onSave(text.trim()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 多值编辑器：逐条添加/删除，保存时由调用方用原分隔符拼回去。
 *
 * 条目允许重复吗？不允许 —— 同一条路径写两遍只会让内核侧多一次无意义 ioctl，
 * 所以添加时就按去重处理（保留第一次出现的位置）。
 */
@Composable
private fun PathListDialog(
    title: String,
    separator: String,
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var entries by remember { mutableStateOf(initial) }
    var draft by remember { mutableStateOf("") }
    XDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            XDialogTitle(text = title)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.susfs_empty_list),
                        fontSize = 13.sp,
                        color = Xc.colors.textMuted,
                    )
                }
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry,
                            fontSize = 13.sp,
                            color = Xc.colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { entries = entries - entry }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = Xc.colors.danger,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.susfs_list_add_hint),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.susfs_add),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val value = draft.trim()
                        if (value.isNotEmpty() && value !in entries) entries = entries + value
                        draft = ""
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.susfs_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.susfs_apply),
                    onClick = { onSave(entries) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

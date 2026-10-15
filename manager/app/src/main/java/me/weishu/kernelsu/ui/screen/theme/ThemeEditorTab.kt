package me.weishu.kernelsu.ui.screen.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.theme.ThemeCodec
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.XC_COLOR_TOKENS
import me.weishu.kernelsu.ui.design.token.XC_GLASS_TOKENS
import me.weishu.kernelsu.ui.design.token.XC_NEON_TOKENS
import me.weishu.kernelsu.ui.design.token.XC_SHAPE_TOKENS
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlocks
import me.weishu.kernelsu.ui.design.token.XcColors
import me.weishu.kernelsu.ui.design.token.XcNeonColors
import me.weishu.kernelsu.ui.design.token.xcColorsFor
import me.weishu.kernelsu.ui.design.token.xcNeonFor
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.theme.keyColorOptions
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 常量 / 纯函数

/** 形状（dp）与玻璃（倍率）的可调范围、步长。玻璃 1.0 = 今天的样子。 */
private const val SHAPE_MIN = 0
private const val SHAPE_MAX = 48
private const val SHAPE_STEP = 2
private const val GLASS_MIN = 0f
private const val GLASS_MAX = 3f
private const val GLASS_STEP = 0.1f

/** 色板预设：复用设置页那 15 个强调色，再补 3 个改 backdrop/surface 时用得上的中性色。 */
private val PickerSwatches: List<Color> = keyColorOptions.map { Color(it) } + listOf(
    Color(0xFFFFFFFF), Color(0xFF161D1F), Color(0xFF000000),
)

/** 取色输入框接受 `#RGB` / `#RRGGBB` / `#AARRGGBB`（`#` 可省）。 */
private val HEX_INPUT = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/**
 * 归一成 [ThemeCodec] 认可的写法（6/8 位、带 `#`、大写）。
 *
 * hex 会被原样写进 theme.json，导出/发布时 `ThemeCodec.colorMap` 还要校验一遍，
 * 格式不对**整个主题包都解析不了**，所以 `#RGB` 必须展开，不合格的输入直接返回 null。
 */
private fun normalizeHex(input: String): String? {
    val text = input.trim()
    if (!HEX_INPUT.matches(text)) return null
    val raw = text.removePrefix("#")
    val expanded = if (raw.length == 3) raw.map { "$it$it" }.joinToString("") else raw
    return ("#" + expanded).uppercase()
}

/** `Color` -> `#AARRGGBB`（内置默认色没有"原始字符串"，标签只能算出来）。 */
private fun hexOf(color: Color): String = String.format(Locale.US, "#%08X", color.toArgb())

/** 颜色令牌名 -> 默认色。与 `XcColors.withOverrides` 一一对应；对不上的令牌不画那一行。 */
private fun defaultColors(c: XcColors): Map<String, Color> = mapOf(
    "backdrop" to c.backdrop, "backdropScrim" to c.backdropScrim,
    "surface" to c.surface, "surfaceMuted" to c.surfaceMuted,
    "glassTint" to c.glassTint, "clearGlassTint" to c.clearGlassTint,
    "glassRim" to c.glassRim, "text" to c.text,
    "textSecondary" to c.textSecondary, "textMuted" to c.textMuted,
    "accent" to c.accent, "accentSoft" to c.accentSoft,
    "water" to c.water, "danger" to c.danger,
    "warning" to c.warning, "success" to c.success,
)

/** 霓虹板 25 个槽位的默认色（与 `XcNeonColors.withOverrides` 一一对应）。 */
private fun neonDefaults(c: XcNeonColors): Map<String, Color> = mapOf(
    "cardBg" to c.cardBg, "cardBorder" to c.cardBorder,
    "cardBorderPressed" to c.cardBorderPressed, "actionBorder" to c.actionBorder,
    "actionBgPressed" to c.actionBgPressed, "actionBorderPressed" to c.actionBorderPressed,
    "dangerBorderPressed" to c.dangerBorderPressed, "dangerBgPressed" to c.dangerBgPressed,
    "dangerBorder" to c.dangerBorder, "accentPurple" to c.accentPurple,
    "accentCyan" to c.accentCyan, "danger" to c.danger,
    "textMain" to c.textMain, "textSub" to c.textSub,
    "divider" to c.divider, "metaBg" to c.metaBg,
    "metaText" to c.metaText, "pillBg" to c.pillBg,
    "gradientStart" to c.gradientStart, "gradientEnd" to c.gradientEnd,
    "cardShadow" to c.cardShadow, "glow" to c.glow,
    "fabGlow" to c.fabGlow, "pageGlowPurple" to c.pageGlowPurple,
    "pageGlowCyan" to c.pageGlowCyan,
)

/**
 * 区块 id -> 显示名。
 *
 * 刻意放在**编辑器**里而不是令牌层：[XcBlocks] 那一层至今没有任何 Android 资源依赖，
 * 把 `R.string.*` 塞进去会让"纯设计令牌"变成"带资源的东西"。而"区块叫什么名字给人看"
 * 本来就是界面的事。对不上的 id（比如新增了区块却忘了补名字）整行不画 —— 与其编一个
 * 字段名，不如老实少一行（颜色令牌那一节也是这条规矩）。
 */
private fun blockLabelRes(id: String): Int? = when (id) {
    XcBlocks.HOME_UPDATE -> R.string.theme_editor_block_home_update
    XcBlocks.HOME_WARNING -> R.string.theme_editor_block_home_warning
    XcBlocks.HOME_STATUS -> R.string.theme_editor_block_home_status
    XcBlocks.HOME_PRIVILEGE -> R.string.theme_editor_block_home_privilege
    XcBlocks.HOME_INFO -> R.string.theme_editor_block_home_info
    XcBlocks.HOME_SUPPORT -> R.string.theme_editor_block_home_support
    else -> null
}

/**
 * 正在取色的目标；为 null 时面板不显示。
 *
 * [neon] = 这次改的是模块页霓虹板（`Spec.neonDark/neonLight`）而不是颜色令牌：
 * 两者共用同一个取色面板，但确认时要落到不同的动作上。
 *
 * [blockId] = 这次改的是某张卡片的背景色（`Spec.blocks.<id>.color`），第三条路。
 * 三者在面板里长得一样，落点却完全不同，所以必须由这个字段区分，而不是靠"token 名字
 * 看着像区块 id"来猜。
 */
private data class ColorTarget(
    val isDark: Boolean,
    val token: String,
    val neon: Boolean = false,
    val blockId: String? = null,
)

/**
 * 草稿里区块背景色**当前生效的值**（= 用户改过的那个）。
 *
 * 直接用 `ThemeCodec.parseColor` 解 `blocks` 里的字符串，而不是从渲染用的那套背景对象
 * 反推：编辑器里"这个区块的色块该画什么"必须与"保存时写进包里的东西"完全一致，
 * 走一遍渲染对象就多了一层可能对不上的转换（渲染那边还会因为 `when` 而藏起来）。
 * 解析不出来的（理论上进不来）当作没设色。
 */
private fun effectiveBlockColors(spec: ThemeCodec.Spec): Map<String, Int> {
    if (spec.blocks.isEmpty()) return emptyMap()
    val out = HashMap<String, Int>(spec.blocks.size)
    for ((id, block) in spec.blocks) {
        val argb = ThemeCodec.parseColor(block.color)
        if (argb != null) out[id] = argb
    }
    return out
}

// ------------------------------------------------------------------ 主界面

/**
 * 「主题编辑器」页：元信息 / 颜色 / 圆角 / 玻璃 / 霓虹 / 背景 / 区块背景 / 保存。
 *
 * 这一页只做两件事：把 `ThemeEditorState.spec`（= 将要写进 theme.json 的东西）画出来，
 * 以及把每次改动交给 [ThemeActions]。草稿状态不在这里 —— 所有值都从 spec 派生，
 * 于是"改了哪些令牌"只有一份真相，不会出现界面与包内容不一致。
 */
@Composable
fun ThemeEditorTab(
    state: ThemeUiState,
    actions: ThemeActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    val spec = state.editor.spec
    if (spec == null) {
        EmptyEditorCard(state.busy, actions, innerPadding, backdrop)
        return
    }

    // 默认值取 xcColorsFor(档位) 而不是 Xc.colors：后者是"当前生效主题"的色，
    // 编辑浅色档时会被当前主题串色，"默认值"就不是默认值了。
    val darkDefaults = remember { defaultColors(xcColorsFor(true)) }
    val lightDefaults = remember { defaultColors(xcColorsFor(false)) }
    // 霓虹板跟随当前明暗档（模块页自己也是这么取的，见 XcNeon.colors）。
    val neonIsDark = isInDarkTheme()
    val neonDefaultColors = remember(neonIsDark) { neonDefaults(xcNeonFor(neonIsDark)) }
    val neonOverrides = if (neonIsDark) spec.neonDark else spec.neonLight

    var target by remember { mutableStateOf<ColorTarget?>(null) }
    var neonExpanded by remember { mutableStateOf(false) }

    // 面板正在改的那个令牌当前是什么色：覆盖优先，否则内置默认。
    val targetColor = target?.let { t ->
        // 区块背景色没有"内置默认"（默认就是"没设过"），所以它单独一支：解不出来就留空。
        if (t.blockId != null) {
            ThemeCodec.parseColor(spec.blocks[t.blockId]?.color)?.let { Color(it) }
        } else {
            // 霓虹的目标色要从霓虹那两张表里取，不能落到颜色令牌上（两边是不同的键空间）。
            val source = when {
                t.neon && t.isDark -> spec.neonDark
                t.neon -> spec.neonLight
                t.isDark -> spec.dark
                else -> spec.light
            }
            ThemeCodec.parseColor(source[t.token])?.let { Color(it) }
                ?: (if (t.neon) neonDefaultColors else if (t.isDark) darkDefaults else lightDefaults)[t.token]
        }
    }
    val blockColors = effectiveBlockColors(spec)

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = null,
    ) {
        item(key = "meta") { MetaCard(spec, actions, backdrop) }
        item(key = "colors-dark") {
            ColorSection(R.string.theme_editor_colors_dark, true, spec, darkDefaults, actions, backdrop) { token ->
                target = ColorTarget(isDark = true, token = token)
            }
        }
        item(key = "colors-light") {
            ColorSection(R.string.theme_editor_colors_light, false, spec, lightDefaults, actions, backdrop) { token ->
                target = ColorTarget(isDark = false, token = token)
            }
        }
        item(key = "shapes") { ShapesCard(spec, actions, backdrop) }
        item(key = "glass") { GlassCard(spec, actions, backdrop) }
        item(key = "neon") {
            NeonCard(
                expanded = neonExpanded,
                overrides = neonOverrides,
                defaults = neonDefaultColors,
                isDark = neonIsDark,
                onToggle = { neonExpanded = !neonExpanded },
                onPick = { token -> target = ColorTarget(isDark = neonIsDark, token = token, neon = true) },
                onReset = { token -> actions.onResetNeon(neonIsDark, token) },
                backdrop = backdrop,
            )
        }
        item(key = "background") { BackgroundCard(state, actions, backdrop) }
        item(key = "blocks") {
            BlocksCard(
                spec = spec,
                pendingBlocks = state.editor.pendingBlocks,
                colors = blockColors,
                enabled = !state.busy,
                actions = actions,
                backdrop = backdrop,
                onPickColor = { id -> target = ColorTarget(isDark = false, token = id, blockId = id) },
            )
        }
        item(key = "save") { SaveRow(state, actions) }
        item(key = "tail") { Spacer(Modifier.height(12.dp)) }
    }

    ColorPickerDialog(
        target = target,
        current = targetColor,
        title = target?.blockId?.let { id -> blockLabelRes(id)?.let { stringResource(it) } },
        onDismiss = { target = null },
        onConfirm = { hex ->
            target?.let { t ->
                val blockId = t.blockId
                if (blockId != null) {
                    actions.onSetBlockColor(blockId, hex)
                } else if (t.neon) {
                    actions.onSetNeon(t.isDark, t.token, hex)
                } else {
                    actions.onSetColor(t.isDark, t.token, hex)
                }
            }
        },
    )
}

/** 没有底稿时的空态：只有"新建"和一句"主题是什么"的说明。 */
@Composable
private fun EmptyEditorCard(
    busy: Boolean,
    actions: ThemeActions,
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
) {
    // 只有一张卡片，不需要能滚的列表：直接铺在页面顶部，内边距照抄列表用的那份。
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
        ThemeCard(backdrop) {
            Text(
                text = stringResource(R.string.theme_editor_title),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Xc.colors.text,
            )
            MutedText(stringResource(R.string.theme_editor_hint_tokens), Modifier.padding(top = 4.dp))
            TextButton(
                text = stringResource(R.string.theme_editor_new),
                onClick = actions.onStartNew,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ------------------------------------------------------------------ 元信息

/**
 * 元信息卡片：每次只把改动的那一个字段传给 [ThemeActions.onEditMeta]，其余位置参数传 null
 * （ViewModel 侧 null = 保持原值），所以不会把没碰过的字段覆盖掉。
 */
@Composable
private fun MetaCard(
    spec: ThemeCodec.Spec,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    ThemeCard(backdrop) {
        Text(
            text = stringResource(R.string.theme_editor_title),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Xc.colors.text,
        )
        MetaField(R.string.theme_editor_name, spec.name) { actions.onEditMeta(it, null, null, null, null, null) }
        MetaField(R.string.theme_editor_id, spec.id) { actions.onEditMeta(null, it, null, null, null, null) }
        MetaField(R.string.theme_editor_author, spec.author) { actions.onEditMeta(null, null, it, null, null, null) }
        MetaField(R.string.theme_editor_version, spec.version) { actions.onEditMeta(null, null, null, it, null, null) }
        MetaField(R.string.theme_editor_summary, spec.summary) { actions.onEditMeta(null, null, null, null, it, null) }
        MetaField(R.string.theme_editor_description, spec.description) { actions.onEditMeta(null, null, null, null, null, it) }
        MutedText(stringResource(R.string.theme_editor_hint_tokens), Modifier.padding(top = 10.dp))
    }
}

/** 一行元信息输入框。 */
@Composable
private fun MetaField(labelRes: Int, value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(labelRes),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

// ------------------------------------------------------------------ 颜色

/**
 * 一档（深色/浅色）的全部颜色令牌。
 *
 * 每行显示的是**生效值**：spec 写了就用它（并把原始字符串显示出来），没写就用内置默认，
 * 于是"这个令牌改过没有"不用额外标注，hex 标签本身就是答案。
 */
@Composable
private fun ColorSection(
    titleRes: Int,
    isDark: Boolean,
    spec: ThemeCodec.Spec,
    defaults: Map<String, Color>,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
    onPick: (String) -> Unit,
) {
    val overrides = if (isDark) spec.dark else spec.light
    SectionTitle(titleRes)
    Spacer(Modifier.height(6.dp))
    ThemeCard(backdrop) {
        // 映射不到默认值的令牌整行不画：与其编一个字段名，不如老实少一行。
        XC_COLOR_TOKENS.filter { defaults.containsKey(it) }.forEach { token ->
            val override = overrides[token]
            val effective = ThemeCodec.parseColor(override)?.let { Color(it) } ?: defaults[token]
            if (effective != null) {
                ColorTokenRow(token, override, effective, { onPick(token) }) {
                    actions.onResetColor(isDark, token)
                }
            }
        }
    }
}

/** 一行颜色令牌：色块（点它开取色面板）+ 令牌名 + 当前 hex + 复位。 */
@Composable
private fun ColorTokenRow(
    token: String,
    override: String?,
    effective: Color,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SwatchBox(color = effective, size = 26.dp, modifier = Modifier.clickable { onClick() })
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(text = token, fontSize = 13.sp, color = Xc.colors.text)
            MutedText(override ?: hexOf(effective))
        }
        // 没改过的令牌没有"复位"可言，灰掉比点了没反应好。
        TextButton(
            text = stringResource(R.string.theme_editor_reset_token),
            onClick = onReset,
            enabled = override != null,
        )
    }
}

/** 霓虹板的一行：跟颜色令牌一样的形态，只是动作走霓虹那对回调。 */
@Composable
private fun NeonTokenRow(
    token: String,
    override: String?,
    effective: Color,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SwatchBox(color = effective, size = 26.dp, modifier = Modifier.clickable { onClick() })
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(text = token, fontSize = 13.sp, color = Xc.colors.text)
            MutedText(override ?: hexOf(effective))
        }
        TextButton(
            text = stringResource(R.string.theme_editor_reset_token),
            onClick = onReset,
            enabled = override != null,
        )
    }
}

// ------------------------------------------------------------------ 圆角 / 玻璃

/** 圆角：一行一个令牌，当前值（覆盖或默认）+ 两个步进按钮。 */
@Composable
private fun ShapesCard(
    spec: ThemeCodec.Spec,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    SectionTitle(R.string.theme_editor_shapes)
    Spacer(Modifier.height(6.dp))
    ThemeCard(backdrop) {
        XC_SHAPE_TOKENS.forEach { (token, defaultDp) ->
            // 先算出"现在是多少"，再看两个方向 —— 直接对 `?:` 的结果做加减会被优先级坑到。
            val current = (spec.shapes[token] ?: defaultDp).coerceIn(SHAPE_MIN, SHAPE_MAX)
            StepRow(
                label = token,
                value = "${current}dp",
                onMinus = { actions.onSetShape(token, (current - SHAPE_STEP).coerceIn(SHAPE_MIN, SHAPE_MAX)) },
                onPlus = { actions.onSetShape(token, (current + SHAPE_STEP).coerceIn(SHAPE_MIN, SHAPE_MAX)) },
            )
        }
    }
}

/**
 * 玻璃：全是倍率，1.0 = 今天的样子。步长 0.1 且夹在 0.0~3.0 —— 主题包校验到 8.0，
 * 但 UI 上点 80 下没人干，超过 3 倍已经不是"调玻璃"而是换了个东西。
 */
@Composable
private fun GlassCard(
    spec: ThemeCodec.Spec,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    SectionTitle(R.string.theme_editor_glass, R.string.theme_editor_glass_hint)
    Spacer(Modifier.height(6.dp))
    ThemeCard(backdrop) {
        XC_GLASS_TOKENS.forEach { token ->
            val current = (spec.glass[token] ?: 1f).coerceIn(GLASS_MIN, GLASS_MAX)
            StepRow(
                label = token,
                value = String.format(Locale.US, "%.2f", current),
                onMinus = { actions.onSetGlass(token, stepGlass(current, -1)) },
                onPlus = { actions.onSetGlass(token, stepGlass(current, 1)) },
            )
        }
    }
}

/** 按 0.1 取整地加减，避免浮点累积出 1.1000001 这种值。 */
private fun stepGlass(current: Float, direction: Int): Float {
    val steps = (current / GLASS_STEP).roundToInt() + direction
    return (steps * GLASS_STEP).coerceIn(GLASS_MIN, GLASS_MAX)
}

/** 一行"名字 + 当前值 + -/+ "，圆角与玻璃共用。 */
@Composable
private fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 13.sp, color = Xc.colors.text, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 12.sp, color = Xc.colors.textSecondary)
        TextButton(text = "-", onClick = onMinus)
        TextButton(text = "+", onClick = onPlus)
    }
}

// ------------------------------------------------------------------ 霓虹

/**
 * 模块页霓虹板：25 个槽位，**可改**。
 *
 * 霓虹只能落到 `Spec.neonDark/neonLight`，所以走的是 [ThemeActions.onSetNeon] 这一对回调
 * （不是颜色令牌那对）—— `XcNeon` 刻意不进 `XcTheme` 的默认下发，主题不写 neon 时模块页
 * 保持原样，写了才覆盖（见 `XcTheme.kt` 的 `LocalXcNeonOverride`）。
 *
 * 板子跟随当前明暗档（模块页自己也是这么取的），所以这里编辑的是"你现在看到的那一档"。
 */
@Composable
private fun NeonCard(
    expanded: Boolean,
    overrides: Map<String, String>,
    defaults: Map<String, Color>,
    isDark: Boolean,
    onToggle: () -> Unit,
    onPick: (String) -> Unit,
    onReset: (String) -> Unit,
    backdrop: LayerBackdrop?,
) {
    ThemeCard(backdrop) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.theme_editor_neon),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Xc.colors.text,
                )
                MutedText(stringResource(R.string.theme_editor_neon_hint))
            }
            Text(text = if (expanded) "-" else "+", fontSize = 16.sp, color = Xc.colors.textSecondary)
        }
        if (expanded) {
            XC_NEON_TOKENS.forEach { token ->
                val override = overrides[token]
                val effective = ThemeCodec.parseColor(override)?.let { Color(it) } ?: defaults[token]
                if (effective != null) {
                    NeonTokenRow(
                        token = token,
                        override = override,
                        effective = effective,
                        onClick = { onPick(token) },
                        onReset = { onReset(token) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 背景

/** 背景图两档：深色 / 浅色各一行，各带"选择"与"清除"。 */
@Composable
private fun BackgroundCard(
    state: ThemeUiState,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
) {
    val spec = state.editor.spec ?: return
    SectionTitle(R.string.theme_editor_background)
    Spacer(Modifier.height(6.dp))
    ThemeCard(backdrop) {
        BackgroundRow(
            titleRes = R.string.theme_editor_bg_dark,
            saved = spec.backgroundDark,
            pending = state.editor.pendingDarkBackground,
            enabled = !state.busy,
            onPick = { actions.onPickBackground(true) },
            onClear = { actions.onClearBackground(true) },
        )
        BackgroundRow(
            titleRes = R.string.theme_editor_bg_light,
            saved = spec.backgroundLight,
            pending = state.editor.pendingLightBackground,
            enabled = !state.busy,
            onPick = { actions.onPickBackground(false) },
            onClear = { actions.onClearBackground(false) },
        )
    }
}

/** 一档背景。还没保存的那张图（`content://`）优先显示：它才是用户刚选的东西。 */
@Composable
private fun BackgroundRow(
    titleRes: Int,
    saved: String?,
    pending: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val name = (pending ?: saved)?.takeIf { it.isNotEmpty() }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), fontSize = 13.sp, color = Xc.colors.text)
            // 只取最后一段：content:// 那条 URI 铺出来会把这一行撑爆。
            MutedText(name?.substringAfterLast('/') ?: stringResource(R.string.theme_editor_bg_none))
        }
        TextButton(text = stringResource(R.string.theme_editor_pick_image), onClick = onPick, enabled = enabled)
        TextButton(
            text = stringResource(R.string.theme_editor_bg_clear),
            onClick = onClear,
            enabled = enabled && name != null,
        )
    }
}

// ------------------------------------------------------------------ 区块背景

/**
 * 「区块背景」一节：六张首页卡片各一行。
 *
 * 为什么是"每张卡片一行"而不是一套全局设置：卡片背景本来就是**位置**语义的东西
 * （`home.status` 指的是状态卡那个位置），用户想要的也往往是"就给信息卡换张图"。
 *
 * 一行里能改五样：[颜色]（点色块开既有的取色面板）、[图]（选图/清图，走既有的相册启动器，
 * 只暂存在草稿里，保存时才落盘）、[透明度]、[黑纱]、[明档/暗档/两档]。外加一个「重置」把
 * 整行退回"没设过"。跟背景图那两行一样，未保存前显示的是**草稿里那张**（它才是用户刚选的）。
 */
@Composable
private fun BlocksCard(
    spec: ThemeCodec.Spec,
    pendingBlocks: Map<String, String>,
    colors: Map<String, Int>,
    enabled: Boolean,
    actions: ThemeActions,
    backdrop: LayerBackdrop?,
    onPickColor: (String) -> Unit,
) {
    // 三档的显示名。用字面量"亮档/暗档"而不是拿 `when` 的关键字当文案：
    // 关键字是给机器读的，界面上要写人话（"both" 在中文界面里没人看得懂）。
    val modes = listOf(
        ThemeCodec.BLOCK_LIGHT to stringResource(R.string.theme_editor_block_when_light),
        ThemeCodec.BLOCK_DARK to stringResource(R.string.theme_editor_block_when_dark),
        ThemeCodec.BLOCK_BOTH to stringResource(R.string.theme_editor_block_when_both),
    )
    SectionTitle(R.string.theme_editor_blocks, R.string.theme_editor_blocks_hint)
    Spacer(Modifier.height(6.dp))
    ThemeCard(backdrop) {
        XcBlocks.HOME_ALL.forEach { id ->
            val labelRes = blockLabelRes(id) ?: return@forEach
            val block = spec.blocks[id] ?: ThemeCodec.BlockSpec()
            BlockRow(
                labelRes = labelRes,
                block = block,
                color = colors[id]?.let { Color(it) },
                pending = pendingBlocks[id],
                enabled = enabled,
                modes = modes,
                onPickColor = { onPickColor(id) },
                onClearColor = { actions.onClearBlockColor(id) },
                onPickImage = { actions.onPickBlockImage(id) },
                onClearImage = { actions.onClearBlockImage(id) },
                onStep = { token, step -> actions.onSetBlockNumber(id, token, step) },
                onSetWhen = { mode -> actions.onSetBlockWhen(id, mode) },
                onReset = { actions.onResetBlock(id) },
            )
        }
    }
}

/**
 * 一行区块背景。
 *
 * 行的形态照着既有的 `ColorTokenRow` / `BackgroundRow` 来（色块 + 名字 + 当前值 + 右侧
 * 按钮），只是这一行的动作多几枚 —— 多出来的两行步进与三档开关放在名字下面，
 * 免得把一行挤成横条。
 */
@Composable
private fun BlockRow(
    labelRes: Int,
    block: ThemeCodec.BlockSpec,
    color: Color?,
    pending: String?,
    enabled: Boolean,
    modes: List<Pair<String, String>>,
    onPickColor: () -> Unit,
    onClearColor: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onStep: (token: String, step: Int) -> Unit,
    onSetWhen: (mode: String) -> Unit,
    onReset: () -> Unit,
) {
    // 三态：草稿里清过（空串）/ 草稿里选了一张（content:// 或绝对路径）/ 没碰过（用包里的）。
    val chosen = pending?.takeIf { it.isNotEmpty() }
    val cleared = pending != null && pending.isEmpty()
    val savedImage = if (cleared) null else block.image
    val imageName = chosen?.substringAfterLast('/') ?: savedImage
    val opacity = block.opacity.coerceIn(0f, 1f)
    val dim = block.dim.coerceIn(0f, 1f)
    // 明暗档不是默认值也算"动过"：只改了档位而没设颜色/图时它其实不会进包（见 ThemeCodec
    // 的过滤），但界面得说得通 —— 点了档位却按不动「重置」会让人以为按钮坏了。
    val touched = color != null || imageName != null || block.mode != ThemeCodec.BLOCK_BOTH

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwatchBox(color = color, size = 26.dp, modifier = Modifier.clickable(enabled = enabled) { onPickColor() })
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(text = stringResource(labelRes), fontSize = 13.sp, color = Xc.colors.text)
                MutedText(color?.let { hexOf(it) } ?: stringResource(R.string.theme_editor_block_no_color))
            }
            TextButton(
                text = stringResource(R.string.theme_editor_block_color_clear),
                onClick = onClearColor,
                enabled = enabled && color != null,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // 只取最后一段：content:// 那条 URI 铺出来会把这一行撑爆（同 BackgroundRow）。
                MutedText(imageName ?: stringResource(R.string.theme_editor_bg_none))
            }
            TextButton(
                text = stringResource(R.string.theme_editor_pick_image),
                onClick = onPickImage,
                enabled = enabled,
            )
            TextButton(
                text = stringResource(R.string.theme_editor_bg_clear),
                onClick = onClearImage,
                enabled = enabled && imageName != null,
            )
        }
        StepRow(
            label = stringResource(R.string.theme_editor_block_opacity),
            value = String.format(Locale.US, "%.2f", opacity),
            onMinus = { onStep("opacity", -1) },
            onPlus = { onStep("opacity", 1) },
        )
        StepRow(
            label = stringResource(R.string.theme_editor_block_dim),
            value = String.format(Locale.US, "%.2f", dim),
            onMinus = { onStep("dim", -1) },
            onPlus = { onStep("dim", 1) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.theme_editor_block_when),
                fontSize = 13.sp,
                color = Xc.colors.text,
                modifier = Modifier.weight(1f),
            )
            // 三档开关就画成三枚按钮：只有三档，做成下拉/弹层反而多两层交互。
            modes.forEach { (mode, label) ->
                TextButton(
                    text = label,
                    onClick = { onSetWhen(mode) },
                    enabled = enabled && block.mode != mode,
                    colors = if (block.mode == mode) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                text = stringResource(R.string.theme_editor_reset_token),
                onClick = onReset,
                enabled = enabled && touched,
            )
        }
    }
}

// ------------------------------------------------------------------ 保存

/** 底部：保存 + 未保存提示。 */
@Composable
private fun SaveRow(state: ThemeUiState, actions: ThemeActions) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            text = stringResource(R.string.theme_editor_save),
            onClick = actions.onSaveEditor,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        if (state.editor.dirty) {
            MutedText(
                text = stringResource(R.string.theme_editor_dirty),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 取色面板

/**
 * 取色面板：预设色格 + hex 输入。
 *
 * 没引 HSV 圆盘那类控件：主题改的多半是"这个绿再深一点"，预设色格 + 手写 hex 恰好覆盖
 * 这两种场景，还省掉一整套自绘控件。确认键只在校验通过时可点，输入框里始终是"将要写进
 * 主题包的那串字符"，所见即所得。
 *
 * [title] 为空时标题就是令牌名（颜色/霓虹那一套用法）；区块背景那边传显示名 ——
 * 区块 id 是 `home.status` 这种给机器看的串，标题栏里写着它没人看得懂。
 */
@Composable
private fun ColorPickerDialog(
    target: ColorTarget?,
    current: Color?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String? = null,
) {
    // 换一个令牌就重置草稿（target 变了 remember 的 key 也变），否则会把上一个的输入带过来。
    var draft by remember(target) { mutableStateOf(current?.let { hexOf(it) }.orEmpty()) }
    val normalized = normalizeHex(draft)
    val preview = normalized?.let { ThemeCodec.parseColor(it) }?.let { Color(it) }

    XDialog(show = target != null, onDismissRequest = onDismiss) {
        XDialogTitle(text = title ?: target?.token.orEmpty(), summary = current?.let { hexOf(it) })
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwatchBox(color = preview, size = 30.dp)
            Text(
                text = normalized ?: "",
                fontSize = 12.sp, color = Xc.colors.textSecondary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        // 预设色格：一行 6 个，18 个刚好三行（15 个强调色 + 3 个中性色）。
        PickerSwatches.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { swatch ->
                    SwatchBox(
                        color = swatch,
                        size = 30.dp,
                        selected = swatch == preview,
                        modifier = Modifier.clickable { draft = hexOf(swatch) },
                    )
                }
            }
        }
        TextField(
            value = draft,
            onValueChange = { draft = it },
            // 这里刻意用字面量而不是字符串资源：它描述的是"输入格式"，不是给人读的句子。
            label = "#RRGGBB / #AARRGGBB",
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    normalized?.let { onConfirm(it) }
                    onDismiss()
                },
                enabled = normalized != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ------------------------------------------------------------------ 公共小件

/** 分组小标题（miuix SmallTitle）+ 可选的一行说明。 */
@Composable
private fun SectionTitle(titleRes: Int, hintRes: Int? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(text = stringResource(titleRes))
        if (hintRes != null) {
            MutedText(stringResource(hintRes))
        }
    }
}

/** 玻璃卡片：本页所有分组的统一容器（内容自带 14dp 内边距）。 */
@Composable
private fun ThemeCard(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

/** 小号说明文字。 */
@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, fontSize = 11.sp, lineHeight = 15.sp, color = Xc.colors.textMuted, modifier = modifier)
}

/**
 * 一枚色块。
 *
 * [color] 为 null（令牌解析不出颜色）时画一块描边的"空位"，而不是留白 —— 留白会被读成
 * "这里少画了一块"。[selected] 只用于取色面板里标出当前选中的预设色。
 */
@Composable
private fun SwatchBox(
    color: Color?,
    size: Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(Xc.shapes.xs)
            .background(color ?: Xc.colors.surfaceMuted.copy(alpha = 0.55f))
            .border(
                if (selected) 2.dp else 0.5.dp,
                if (selected) Xc.colors.accent else Xc.colors.glassRim,
                Xc.shapes.xs,
            ),
    )
}

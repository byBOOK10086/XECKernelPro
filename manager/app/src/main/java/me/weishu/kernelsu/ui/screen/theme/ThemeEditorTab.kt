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
 * 正在取色的目标；为 null 时面板不显示。
 *
 * [neon] = 这次改的是模块页霓虹板（`Spec.neonDark/neonLight`）而不是颜色令牌：
 * 两者共用同一个取色面板，但确认时要落到不同的动作上。
 */
private data class ColorTarget(val isDark: Boolean, val token: String, val neon: Boolean = false)

// ------------------------------------------------------------------ 主界面

/**
 * 「主题编辑器」页：元信息 / 颜色 / 圆角 / 玻璃 / 霓虹 / 背景 / 保存。
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
        item(key = "save") { SaveRow(state, actions) }
        item(key = "tail") { Spacer(Modifier.height(12.dp)) }
    }

    ColorPickerDialog(
        target = target,
        current = targetColor,
        onDismiss = { target = null },
        onConfirm = { hex ->
            target?.let { t ->
                if (t.neon) actions.onSetNeon(t.isDark, t.token, hex)
                else actions.onSetColor(t.isDark, t.token, hex)
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
 */
@Composable
private fun ColorPickerDialog(
    target: ColorTarget?,
    current: Color?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 换一个令牌就重置草稿（target 变了 remember 的 key 也变），否则会把上一个的输入带过来。
    var draft by remember(target) { mutableStateOf(current?.let { hexOf(it) }.orEmpty()) }
    val normalized = normalizeHex(draft)
    val preview = normalized?.let { ThemeCodec.parseColor(it) }?.let { Color(it) }

    XDialog(show = target != null, onDismissRequest = onDismiss) {
        XDialogTitle(text = target?.token.orEmpty(), summary = current?.let { hexOf(it) })
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

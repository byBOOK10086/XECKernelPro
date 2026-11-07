package me.weishu.kernelsu.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.ui.design.panel.Xc8KeyValueCell
import me.weishu.kernelsu.ui.design.panel.Xc8Note
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlockBackground
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 徽标与说明里用到的品牌名：不翻译（LKM / GKI / Magisk 是术语，不是文案）。 */
private const val MAGISK_BRAND = "Magisk"

private const val MODE_CARD_PADDING_H = 15.dp

/**
 * 首页模式卡（MIUI 8 版）。
 *
 * 版式来自原型的 `.mode-card`：一行小字标签 + 收起/展开按钮，下面是
 * **56dp 模式徽标 + 模式名 + 一句话说明 + 状态胶囊**，展开后再列四格详情。
 *
 * 与旧状态卡的功能对账：
 *  * 旧卡片的"大对勾 + 140dp 定高"没有了：模式本身就是结论，不需要再画一个勾；
 *  * 旧卡片点击进安装页的行为只在**未装载**时保留（`clickable` 只在非激活态挂上），
 *    激活态点卡片什么都不做 —— 那张卡现在是信息，不是按钮；
 *  * 旧的"模式牌自定义"（前缀/后缀/字体/底图/颜色）随外观编辑一起下线：
 *    它调的是玻璃卡片的参数，方角面板里没有对应物；
 *  * 四种形态（LKM 内核模块 / GKI 内置 / <5.10 KernelPatch / Magisk 挂载态）
 *    以及"未安装 / 不支持"两支全部保留，只是都收进同一套版式。
 *
 * 收起状态记在 `rememberSaveable` 里：切页回来仍是用户上次的选择，
 * 但进程重启后回到默认（展开）—— 那是"首次看到"应该有的信息量。
 */
@Composable
fun HomeModeCard(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    block: XcBlockBackground? = null,
) {
    val installed = state.ksuVersion != null
    val mount = state.isMountState
    val active = installed || mount
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 主题可以给模式徽标换底图/字体/前后缀/文字色（`ModeBadgeStyle`，由 ThemeApplier
    // 导入导出）。这里**继续认这份样式**：删掉它会让"主题里的模式牌"静默失效，
    // 而主题侧的 schema 里那几个字段还在 —— 那就是最糟的一种破坏。
    val badgeStyle = rememberModeBadgeStyle()

    val badge = when {
        installed -> homeModeKeyword(state)
        mount -> MAGISK_BRAND
        else -> "GKI"
    }
    val title = when {
        installed -> when {
            !getKernelVersion().isGKI() -> stringResource(R.string.home8_mode_title_kpatch)
            state.lkmMode == true -> stringResource(R.string.home8_mode_title_lkm)
            else -> stringResource(R.string.home8_mode_title_gki)
        }
        mount -> stringResource(R.string.home8_mode_title_mount)
        state.kernelVersion.isGKI() -> stringResource(R.string.home_not_installed)
        else -> stringResource(R.string.home_unsupported)
    }
    val description = when {
        installed -> stringResource(R.string.home8_desc_active)
        mount -> stringResource(R.string.home8_desc_mount)
        state.kernelVersion.isGKI() -> stringResource(R.string.home_click_to_install)
        else -> stringResource(R.string.home_unsupported_reason)
    }

    val details: List<Pair<String, String>> = when {
        installed -> {
            // UAPI 两个版本号都要非空才能判"匹配"：内核没报版本时如实说"未知"，
            // 不能拿 null 去跟管理器的版本比然后判成不匹配。
            val kernelUapi = state.kernelUAPIVersion
            val uapiText = if (kernelUapi == null) {
                stringResource(R.string.home8_unknown)
            } else if (kernelUapi == state.managerUAPIVersion) {
                stringResource(R.string.home8_uapi_matched, kernelUapi)
            } else {
                stringResource(R.string.home8_uapi_mismatch, kernelUapi)
            }
            val daemonText = if (state.isDaemonPresent) {
                stringResource(R.string.home8_daemon_online)
            } else {
                stringResource(R.string.home8_daemon_missing)
            }
            listOf(
                stringResource(R.string.home8_ksu_version) to "${state.ksuVersion}",
                stringResource(R.string.home_kernel) to state.systemInfo.kernelVersion,
                stringResource(R.string.home8_uapi) to uapiText,
                stringResource(R.string.home8_daemon) to daemonText,
            )
        }

        mount -> listOf(
            stringResource(R.string.home8_daemon) to (state.magiskVersion ?: MAGISK_BRAND),
            stringResource(R.string.home_kernel) to state.systemInfo.kernelVersion,
        )

        else -> emptyList()
    }

    // 只有"还没装上内核、且不是越狱/晚加载模式"时，点卡片才是"去安装"。
    val installOnClick = !active && !state.isLateLoadMode
    Xc8Panel(
        modifier = modifier.then(
            if (installOnClick) Modifier.clickable { actions.onInstallClick() } else Modifier
        ),
        block = block,
    ) {
        // 顶部小字 + 收起/展开
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Xc.colors.surface)
                .padding(start = MODE_CARD_PADDING_H, end = MODE_CARD_PADDING_H, top = 11.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home8_mode_label),
                fontSize = 11.5.sp,
                color = Xc.colors.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Row(
                modifier = Modifier
                    .clip(Xc.shapes.xs)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.home8_collapse)
                    } else {
                        stringResource(R.string.home8_expand)
                    },
                    fontSize = 11.5.sp,
                    color = Xc.colors.textSecondary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Xc.colors.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // 徽标 + 模式名 + 说明 + 状态
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Xc.colors.surface)
                .padding(start = MODE_CARD_PADDING_H, end = MODE_CARD_PADDING_H, top = 4.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(min = 56.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (badgeStyle.value.isDefault()) {
                    // 默认态：MIUI 8 的橙色方牌。整块 56dp，字居中。
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(Xc.shapes.md)
                            .background(if (active) Xc.colors.accent else Xc.colors.surfaceMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) Color.White else Xc.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                } else {
                    // 主题自定义态：前缀 + 关键词 + 后缀，底图/字体/颜色都由主题给。
                    // 关键词仍由 homeModeKeyword 生成，不接受主题改写。
                    ModeBadge(
                        keyword = badge,
                        style = badgeStyle.value,
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .widthIn(min = 56.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Xc.colors.text,
                    maxLines = 1,
                )
                Text(
                    text = description,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = Xc.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(Xc.shapes.xs)
                    .background(if (active) Xc.colors.successTint else Xc.colors.glassRim)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (active) Xc.colors.success else Xc.colors.textMuted),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (active) {
                        stringResource(R.string.home8_running)
                    } else {
                        stringResource(R.string.home8_inactive)
                    },
                    fontSize = 11.sp,
                    color = if (active) Xc.colors.onSuccessTint else Xc.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && details.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.background(Xc.colors.surface)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Xc.colors.glassRim),
                )
                // 两列键值格：每格左对齐，格与格之间一条 1px 竖线。
                details.chunked(2).forEachIndexed { rowIndex, rowCells ->
                    if (rowIndex > 0) {
                        DividerBlock()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Xc.colors.surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowCells.forEachIndexed { cellIndex, cell ->
                            if (cellIndex > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(34.dp)
                                        .background(Xc.colors.glassRim),
                                )
                            }
                            Xc8KeyValueCell(
                                label = cell.first,
                                value = cell.second,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 两格的行必须凑满：只有一格时右半边留白，不能让它横跨整行，
                        // 否则同一块面板里的格子宽度会随行变化。
                        if (rowCells.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (active) {
                    Xc8Note(text = stringResource(R.string.home8_hint))
                }
            }
        }
    }
}

/** 面板内部横向 1px 分隔条（与 [me.weishu.kernelsu.ui.design.panel.Xc8Row] 的上边线同色同宽）。 */
@Composable
private fun DividerBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Xc.colors.glassRim),
    )
}

/**
 * 主页模式关键词（KernelPatch / LKM / GKI）。
 *
 * 由系统按设备真实路线生成，永远不落库、不可编辑（模式牌红线）。
 * 判据顺序：非 GKI 一律 KernelPatch（那些内核没有 GKI 内置这一说），
 * 再看 `lkmMode` 区分 LKM 与内置 GKI。
 */
@Composable
internal fun homeModeKeyword(state: HomeUiState): String = when {
    !getKernelVersion().isGKI() -> stringResource(R.string.home_working_kpatch)
    state.lkmMode == true -> "LKM"
    else -> "GKI"
}

package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.mountstate.MagiskRoot
import me.weishu.kernelsu.ui.component.AppIconImage
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.viewmodel.MagiskRootEntry
import me.weishu.kernelsu.ui.viewmodel.MagiskSuperUserUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 四个策略按钮的顺序：询问 / 允许 / 拒绝 / 受限（取值与 Magisk 的 SuPolicy 一致）。 */
private val POLICY_CHOICES = listOf(
    MagiskRoot.POLICY_QUERY to R.string.magisk_su_policy_query,
    MagiskRoot.POLICY_ALLOW to R.string.magisk_su_policy_allow,
    MagiskRoot.POLICY_DENY to R.string.magisk_su_policy_deny,
    MagiskRoot.POLICY_RESTRICT to R.string.magisk_su_policy_restrict,
)

@Immutable
data class MagiskSuperUserActions(
    val onRefresh: () -> Unit,
    val onSetPolicy: (Int, Int) -> Unit,
    val onSetLogging: (Int, Boolean) -> Unit,
    val onSetNotification: (Int, Boolean) -> Unit,
    val onRevoke: (Int) -> Unit,
    val onToggleZygisk: () -> Unit,
    val onToggleDenylist: () -> Unit,
)

/**
 * 挂载态（5.10 以下，方案甲）的超级用户页。
 *
 * 页面上的一切都来自 Magisk 自己的策略表，所以两件事同时成立：
 * 本管理器能管 Magisk 的 root；Magisk 应用（以及它弹的授权框）也管得到本管理器 ——
 * 本管理器的 root 授权就是同一张表里 uid 为本应用的那一行。
 */
@Composable
fun MagiskSuperUserPagerMiuix(
    uiState: MagiskSuperUserUiState,
    actions: MagiskSuperUserActions,
    bottomInnerPadding: Dp,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val magiskAppIntent = remember(context) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(MagiskRoot.MAGISK_PACKAGE)
        }.getOrNull()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.superuser),
                actions = {
                    TextButton(
                        text = stringResource(R.string.magisk_su_refresh),
                        onClick = actions.onRefresh,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            item {
                RouteCard(
                    uiState = uiState,
                    actions = actions,
                    onOpenMagisk = magiskAppIntent?.let { intent -> { context.startActivity(intent) } },
                )
            }
            item { SectionTitle(stringResource(R.string.magisk_su_policies)) }
            if (uiState.entries.isEmpty()) {
                item { HintCard(stringResource(R.string.magisk_su_empty)) }
            } else {
                items(uiState.entries, key = { it.uid }) { entry ->
                    PolicyCard(entry, actions, uiState.rootGranted)
                }
            }
            item { Spacer(Modifier.height(bottomInnerPadding + 12.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun HintCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun RouteCard(
    uiState: MagiskSuperUserUiState,
    actions: MagiskSuperUserActions,
    onOpenMagisk: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.magisk_su_route_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(R.string.magisk_su_route_summary),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            InfoRow(
                label = stringResource(R.string.magisk_su_version_label),
                value = uiState.magiskVersion.ifBlank { stringResource(R.string.magisk_su_version_unknown) },
            )
            InfoRow(
                label = stringResource(R.string.magisk_su_self_label),
                value = stringResource(
                    if (uiState.rootGranted) R.string.magisk_su_self_granted
                    else R.string.magisk_su_self_denied
                ),
            )
            uiState.error?.let { error ->
                Text(
                    text = error,
                    fontSize = 11.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ToggleRow(
                title = stringResource(R.string.magisk_su_zygisk),
                summary = stringResource(R.string.magisk_su_zygisk_summary),
                checked = uiState.zygiskEnabled,
                enabled = uiState.rootGranted,
                onChange = { actions.onToggleZygisk() },
            )
            ToggleRow(
                title = stringResource(R.string.magisk_su_denylist),
                summary = stringResource(R.string.magisk_su_denylist_count, uiState.denylistCount),
                checked = uiState.denylistEnforced,
                enabled = uiState.rootGranted,
                onChange = { actions.onToggleDenylist() },
            )
            Text(
                text = stringResource(R.string.magisk_su_sync_note),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (onOpenMagisk != null) {
                TextButton(
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    text = stringResource(R.string.magisk_su_open_magisk),
                    onClick = onOpenMagisk,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = colorScheme.onSurface)
            Text(text = summary, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
        }
        Switch(
            enabled = enabled,
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
        )
    }
}

@Composable
private fun PolicyCard(
    entry: MagiskRootEntry,
    actions: MagiskSuperUserActions,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column {
            BasicComponent(
                title = if (entry.isSelf) {
                    "${entry.label} · ${stringResource(R.string.magisk_su_self_tag)}"
                } else {
                    entry.label
                },
                summary = entrySummary(entry),
                startAction = {
                    val info = entry.applicationInfo
                    if (info != null) {
                        AppIconImage(
                            applicationInfo = info,
                            label = entry.label,
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .size(40.dp),
                        )
                    } else {
                        Spacer(Modifier.size(40.dp))
                    }
                },
                endActions = {
                    val (background, content) = policyColors(entry.policy)
                    StatusTag(
                        label = stringResource(policyLabel(entry.policy)),
                        backgroundColor = background,
                        contentColor = content,
                    )
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                POLICY_CHOICES.forEach { (policy, labelRes) ->
                    val label = stringResource(labelRes)
                    val onClick = { if (enabled) actions.onSetPolicy(entry.uid, policy) }
                    if (entry.policy == policy) {
                        // 当前生效的那一条用主题色标出来，其余用默认文字色。
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = label,
                            onClick = onClick,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    } else {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = label,
                            onClick = onClick,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.magisk_su_logging), fontSize = 12.sp)
                Spacer(Modifier.size(8.dp))
                Switch(
                    enabled = enabled,
                    checked = entry.logging,
                    onCheckedChange = { actions.onSetLogging(entry.uid, it) },
                )
                Spacer(Modifier.size(12.dp))
                Text(text = stringResource(R.string.magisk_su_notification), fontSize = 12.sp)
                Spacer(Modifier.size(8.dp))
                Switch(
                    enabled = enabled,
                    checked = entry.notification,
                    onCheckedChange = { actions.onSetNotification(entry.uid, it) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.magisk_su_revoke),
                    onClick = { if (enabled) actions.onRevoke(entry.uid) },
                )
            }
        }
    }
}

@Composable
private fun entrySummary(entry: MagiskRootEntry): String = buildString {
    append(entry.packageName ?: "uid ${entry.uid}")
    append(" · uid ").append(entry.uid)
    if (entry.userId > 0) {
        append(" · ").append(stringResource(R.string.magisk_su_user_tag, entry.userId))
    }
}

private fun policyLabel(policy: Int): Int =
    POLICY_CHOICES.firstOrNull { it.first == policy }?.second ?: R.string.magisk_su_policy_query

@Composable
private fun policyColors(policy: Int): Pair<Color, Color> = when (policy) {
    MagiskRoot.POLICY_ALLOW -> colorScheme.primary.copy(alpha = 0.8f) to colorScheme.onPrimary
    MagiskRoot.POLICY_DENY -> colorScheme.error.copy(alpha = 0.8f) to colorScheme.onError
    MagiskRoot.POLICY_RESTRICT -> colorScheme.secondary.copy(alpha = 0.8f) to colorScheme.onSecondary
    else -> colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
}

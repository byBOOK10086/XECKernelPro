package me.weishu.kernelsu.ui.screen.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text

/**
 * The "hero" card at the top of Home: a live XEC Score ring with an ambient
 * aurora + scanline. It is deliberately a single composable so the animated
 * state is local and the rest of the page stays cheap.
 */
@Composable
fun XecHeroCard(
    state: HomeUiState,
    score: XecScoreResult,
    onShare: () -> Unit,
    onDetect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accent = when (score.rank) {
        XecRank.S, XecRank.A -> Xc.colors.success
        XecRank.B -> Xc.colors.warning
        XecRank.C, XecRank.D -> Xc.colors.danger
    }
    val infinite = rememberInfiniteTransition(label = "xec-hero")
    val breath by infinite.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.64f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val scan by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scan",
    )
    val shownScore by animateFloatAsState(
        targetValue = score.total.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 180f),
        label = "score",
    )
    val days = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - info.firstInstallTime)
                .coerceAtLeast(0)
        }.getOrDefault(0L)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Xc.shapes.lg)
            .background(
                Brush.linearGradient(
                    listOf(
                        Xc.colors.surface.copy(alpha = 0.96f),
                        Xc.colors.accentSoft.copy(alpha = 0.92f),
                    )
                )
            )
            .border(1.dp, Xc.colors.glassRim.copy(alpha = 0.9f), Xc.shapes.lg)
            .clickable(onClick = onDetect)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = breath * 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.10f, size.height * 0.20f),
                        radius = size.maxDimension * 0.72f,
                    ),
                    radius = size.maxDimension * 0.72f,
                    center = Offset(size.width * 0.10f, size.height * 0.20f),
                )
                drawLine(
                    color = accent.copy(alpha = 0.18f),
                    start = Offset(0f, size.height * scan),
                    end = Offset(size.width, size.height * scan),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.xec_score_title),
                        color = Xc.colors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.xec_score_guard, days),
                        color = Xc.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.xec_score_share),
                        tint = Xc.colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier.size(138.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val trackColor = Xc.colors.textMuted
                    val waterColor = Xc.colors.water
                    Canvas(Modifier.size(138.dp)) {
                        val stroke = 12.dp.toPx()
                        drawCircle(
                            color = trackColor.copy(alpha = 0.16f),
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = accent.copy(alpha = 0.18f),
                            startAngle = -90f,
                            sweepAngle = 360f * (shownScore / 100f),
                            useCenter = false,
                            style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            brush = Brush.sweepGradient(listOf(accent, waterColor, accent)),
                            startAngle = -90f,
                            sweepAngle = 360f * (shownScore / 100f),
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = shownScore.roundToInt().toString(),
                            color = Xc.colors.text,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.xec_score_out_of),
                            color = Xc.colors.textMuted,
                            fontSize = 11.sp,
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.xec_score_rank, score.rank.name),
                            color = accent,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.xec_score_passed,
                                score.passed,
                                score.items.size,
                            ),
                            color = Xc.colors.textMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = if (score.isHealthy) {
                            stringResource(R.string.xec_score_healthy)
                        } else {
                            stringResource(R.string.xec_score_attention)
                        },
                        color = Xc.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    ScoreChips(score = score, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun ScoreChips(score: XecScoreResult, accent: Color) {
    val quick = listOf(
        XecScoreItemId.ROOT to R.string.xec_score_check_root,
        XecScoreItemId.SELINUX to R.string.xec_score_check_selinux,
        XecScoreItemId.SUSFS to R.string.xec_score_check_susfs,
        XecScoreItemId.TEE to R.string.xec_score_check_tee,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        quick.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                rowItems.forEach { (id, label) ->
                    val ok = score.items.firstOrNull { it.id == id }?.ok == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(Xc.shapes.pill)
                            .background(
                                if (ok) accent.copy(alpha = 0.14f)
                                else Xc.colors.warning.copy(alpha = 0.10f)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(label),
                            color = if (ok) accent else Xc.colors.textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

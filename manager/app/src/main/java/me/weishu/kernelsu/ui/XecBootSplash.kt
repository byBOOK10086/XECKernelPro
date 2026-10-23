package me.weishu.kernelsu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text

/**
 * A short in-app logo reveal after the Android 12 splash. It keeps the
 * branding visible while the first Home frame lays out, then fades away
 * without changing the system splash contract.
 */
@Composable
fun XecBootSplash(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(320)),
    ) {
        val infinite = rememberInfiniteTransition(label = "xec-boot")
        val pulse by infinite.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        val sweep by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        val accent = Xc.colors.accent
        val water = Xc.colors.water
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            Xc.colors.accentSoft,
                            Xc.colors.backdrop,
                            Color.Black,
                        )
                    )
                )
                // It is a visual splash, not a click-through overlay: eat any
                // stray tap during the 900 ms reveal.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier.size(176.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(176.dp)) {
                        drawArc(
                            color = accent.copy(alpha = 0.2f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(
                                    accent,
                                    water,
                                    accent,
                                )
                            ),
                            startAngle = sweep,
                            sweepAngle = 110f,
                            useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_splash),
                        contentDescription = null,
                        modifier = Modifier
                            .size(116.dp)
                            .graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                            },
                    )
                }
                Text(
                    text = "XECKernelPro",
                    color = Xc.colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "LIQUID HOME",
                    color = Xc.colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = "设备安全，第一眼可见",
                color = Xc.colors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 54.dp),
            )
        }
    }
}

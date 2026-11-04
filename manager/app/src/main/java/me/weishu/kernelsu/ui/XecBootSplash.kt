package me.weishu.kernelsu.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.weishu.kernelsu.R

/**
 * 开屏动画：播一遍 `res/raw/xec_boot_intro.mp4`，播完淡出交给首页。
 *
 * 2026-11-04 起取代原来的「图标脉冲 + 扫描环」自绘动画。系统 splash 那侧的静止
 * 图标也一起撤了（见 `res/values/themes.xml`），否则会先闪一帧静止 logo 再跳进
 * 视频，接缝很明显。
 *
 * 几条刻意的行为：
 * - **静音**：开屏带声音会跟用户当时的媒体播放打架，启动画面不该抢音频焦点。
 * - **centerCrop**：素材比例和屏幕比例基本不会一致，留边会露出背景色，所以用
 *   `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` 裁切填满。
 * - **任何失败都算播完**：素材解码失败、URI 打不开、设备没有播放器，统统立刻回调
 *   `onFinished`，绝不能让开屏变成一块挡住整个 App 的黑屏。
 * - **离开组合就停播**：`onRelease` 里 `stopPlayback()`，否则淡出后播放器还在后台跑。
 * - **吃点击**：开屏期间用户误触不应该穿透到下面的首页。
 *
 * 素材必须保持**未压缩**进包：`android.resource://` 走的是 AssetFileDescriptor，
 * 压缩过的 raw 资源没有它，MediaPlayer 会直接打不开。aapt2 的默认不压缩扩展名
 * 列表本来就含 `.mp4`，所以这里不额外声明 `noCompress`，靠默认即可。
 */
@Composable
fun XecBootSplash(visible: Boolean, onFinished: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(280)),
    ) {
        val context = LocalContext.current
        val finished = rememberUpdatedState(onFinished)
        val videoUri = remember(context) {
            Uri.parse("android.resource://${context.packageName}/${R.raw.xec_boot_intro}")
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // It is a visual splash, not a click-through overlay: eat any
                // stray tap while the intro plays.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { player ->
                            // Mute before the first frame so a stray audio frame
                            // never leaks out during a cold start.
                            player.setVolume(0f, 0f)
                            player.isLooping = false
                            player.setVideoScalingMode(
                                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                            )
                        }
                        setOnCompletionListener { finished.value() }
                        // A broken asset must not trap the user behind a black
                        // screen: treat every error as "intro over".
                        setOnErrorListener { _, _, _ ->
                            finished.value()
                            true
                        }
                        setVideoURI(videoUri)
                    }
                },
                onRelease = { view -> view.stopPlayback() },
            )
        }
    }
}

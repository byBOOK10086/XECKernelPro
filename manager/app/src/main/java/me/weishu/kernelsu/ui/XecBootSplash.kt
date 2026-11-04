package me.weishu.kernelsu.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
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
 * 开屏动画：播一遍 `res/raw/xec_boot_intro.mp4`，播完缩小淡出交给首页。
 *
 * 为什么用 [TextureView] + [MediaPlayer] 而不是 `VideoView`：
 * `VideoView` 内部是 `SurfaceView`，画面在独立的合成层上，**Compose 的
 * `graphicsLayer`（alpha / scale）对它不起作用**。之前用 VideoView 配 `fadeOut`
 * 时，背景层能淡、视频层却一直是不透明的一整块，直到视图被移除才"啪"地消失——
 * 看起来就是结尾一下黑边再硬切。TextureView 是普通视图，走正常视图合成，
 * 缩放和淡出才真正作用在画面上。
 *
 * 结束动画按用户要求做成**缓慢缩小 + 淡出**（1.0 → 0.92），不是放大：放大到超过
 * 屏幕时边缘反而会露出背景色。
 *
 * 其余刻意的行为：
 * - **静音**：开屏带声音会跟用户当时的媒体播放打架。
 * - **centerCrop**：素材比例和屏幕比例基本不会一致，留边会露出黑底，所以用
 *   `TextureView.setTransform()` 按 `max(scale)` 裁切填满。
 * - **任何失败都算播完**：解码失败、URI 打不开、没有播放器，统统立刻回调
 *   `onFinished`，绝不能让开屏变成一块挡住整个 App 的黑屏。
 * - **离开组合就 release**：否则播放器会在后台继续持有画面。
 * - **吃点击**：开屏期间误触不应该穿透到下面的首页。
 *
 * 素材必须**未压缩**进包：`android.resource://` 走的是 AssetFileDescriptor，
 * 压缩过的 raw 资源没有它，MediaPlayer 会直接打不开。aapt2 的默认不压缩扩展名
 * 列表本来就含 `.mp4`，所以这里不额外声明 `noCompress`。
 */
@Composable
fun XecBootSplash(visible: Boolean, onFinished: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(420, easing = FastOutSlowInEasing)) +
            scaleOut(tween(420, easing = FastOutSlowInEasing), targetScale = 0.92f),
    ) {
        val context = LocalContext.current
        val finished = rememberUpdatedState(onFinished)
        // 播放器活在视图之外：surface 回调拿不到组合作用域的局部变量，而
        // onRelease 必须能把它彻底 release 掉，否则每进一次开屏就漏一个解码器。
        val playback = remember { PlaybackHolder() }
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
                    val player = MediaPlayer()
                    playback.player = player
                    // 必须先拿到视图引用：下面那个匿名 SurfaceTextureListener 里的
                    // `this` 指的是监听器自己，不是 TextureView，直接传 `this` 会编不过。
                    val texture = TextureView(ctx)
                    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            runCatching {
                                player.setDataSource(ctx, videoUri)
                                player.setSurface(Surface(surfaceTexture))
                                // Mute before the first frame so a stray audio
                                // frame never leaks out on cold start.
                                player.setVolume(0f, 0f)
                                player.isLooping = false
                                player.setOnPreparedListener { it.start() }
                                player.setOnCompletionListener { finished.value() }
                                player.setOnErrorListener { _, _, _ ->
                                    finished.value()
                                    true
                                }
                                player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                                    fitCenterCrop(texture, videoWidth, videoHeight)
                                }
                                player.prepareAsync()
                            }.onFailure { finished.value() }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(
                            surfaceTexture: SurfaceTexture,
                        ): Boolean = true

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                    texture
                },
                onRelease = { view ->
                    view.surfaceTextureListener = null
                    playback.release()
                },
            )
        }
    }
}

/**
 * 持有开屏播放器，让组合作用域之外的回调（surface 生命周期、onRelease）都能拿到它。
 * 只被一个协程/一个 UI 线程访问，不需要同步。
 */
private class PlaybackHolder {
    var player: MediaPlayer? = null

    fun release() {
        val current = player ?: return
        player = null
        runCatching {
            current.setSurface(null)
            current.reset()
            current.release()
        }
    }
}

/**
 * 把画面按 `max(scale)` 放大并居中，等价于 `centerCrop`：宁可裁掉两侧也不留黑边。
 */
private fun fitCenterCrop(view: TextureView, videoWidth: Int, videoHeight: Int) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return
    val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
    val offsetX = (viewWidth - videoWidth * scale) / 2f
    val offsetY = (viewHeight - videoHeight * scale) / 2f
    view.setTransform(
        Matrix().apply {
            setScale(scale, scale)
            postTranslate(offsetX, offsetY)
        }
    )
}

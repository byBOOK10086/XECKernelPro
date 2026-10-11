package me.weishu.kernelsu.ui.screen.module

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.design.token.XcNeon

/**
 * 模块卡 DIY 背景的存储 + 解码 + 渲染。
 *
 * 存储：SharedPreferences（`module_backgrounds`），键 = 模块 id，值 = content Uri 字符串。
 * 图片经系统文件选择器（OpenDocument）选取并 takePersistableUriPermission，重启后仍有效。
 *
 * 解码：内存 LruCache（约 1/8 堆），按「Uri @ 目标宽度」缓存；大图按 inSampleSize
 * 降到 ~1024px 宽再解，模块卡用不到全分辨率。
 *
 * 渲染契约（用户要求「不能掩盖字体」）：图片铺满卡体后必须压两层
 * 「介质色 scrim」——一层整体 alpha 0.68 的 cardBg 均匀罩 + 一层自上而下加重的
 * 竖向渐变（标题行最重）。文字永远画在 scrim 之上，深浅主题下对比度都不塌。
 * scrim 色取 [XcNeon.colors.cardBg]（与卡体同介质），所以有背景的卡和普通玻璃卡
 * 观感是同一语言。
 */
object ModuleBackgrounds {

    private const val PREFS = "module_backgrounds"

    /** Compose 可订阅的 id -> uri 映射；[backgroundOf] 在组合里读它，写入自动刷新。 */
    private val backgrounds: SnapshotStateMap<String, String> = mutableStateMapOf()

    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> (v as? String)?.let { backgrounds[k] = it } }
    }

    /** 卡片渲染处调用（组合内）：该模块当前是否挂了自定义背景。 */
    fun backgroundOf(context: Context, moduleId: String): String? {
        ensureLoaded(context)
        return backgrounds[moduleId]
    }

    fun set(context: Context, moduleId: String, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 某些 provider 不给持久授权：本次会话内仍可用，重启后消失，可接受。
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(moduleId, uri.toString()).apply()
        backgrounds[moduleId] = uri.toString()
    }

    fun clear(context: Context, moduleId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(moduleId).apply()
        backgrounds.remove(moduleId)
        bitmapCache.remove(moduleId)
    }

    // ---- 位图解码与缓存 ----------------------------------------------------

    private const val TARGET_WIDTH = 1024
    private val bitmapCache = object : LruCache<String, Bitmap>(maxSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun maxSizeKb(): Int {
        val heapKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (heapKb / 8).coerceAtLeast(8 * 1024)
    }

    private suspend fun decode(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val src = Uri.parse(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, src)
                ImageDecoder.decodeBitmap(source) { info, _, _ ->
                    // 以 1024px 宽为上限等比降采样（ImageDecoder 的 allocator 默认硬件位图，
                    // 这里强制软位图，便于后续可能的像素操作与低内存设备兼容）。
                    val maxOf = maxOf(info.size.width, info.size.height)
                    if (maxOf > TARGET_WIDTH * 2) {
                        val ratio = (TARGET_WIDTH * 2f) / maxOf
                        info.targetSize = android.util.Size(
                            (info.size.width * ratio).toInt().coerceAtLeast(1),
                            (info.size.height * ratio).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(src)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(src)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        }.getOrNull()
    }
}

/**
 * 模块卡的背景层：图片 + 双层 scrim，整体画在卡玻璃体之上、内容之下。
 * `uri == null` 时不渲染任何东西（零开销路径）。
 */
@Composable
fun ModuleCardBackdrop(uri: String?, shape: Shape, modifier: Modifier = Modifier) {
    if (uri.isNullOrEmpty()) return
    val context = LocalContext.current
    val neon = XcNeon.colors
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        val cached = ModuleBackgrounds.bitmapCache.get(uri)
        if (cached != null) {
            value = cached
        } else {
            val decoded = ModuleBackgrounds.decode(context, uri)
            if (decoded != null) ModuleBackgrounds.bitmapCache.put(uri, decoded)
            value = decoded
        }
    }
    val bm = bitmap ?: return

    Box(modifier = modifier.matchParentSize()) {
        Image(
            bitmap = bm.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 不能掩盖字体：先整体罩一层介质色，再在标题侧加重。
        Box(
            Modifier
                .fillMaxSize()
                .background(neon.cardBg.copy(alpha = 0.68f))
                .background(
                    Brush.verticalGradient(
                        0f to neon.cardBg.copy(alpha = 0.42f),
                        0.55f to Color.Transparent,
                        1f to neon.cardBg.copy(alpha = 0.30f),
                    )
                ),
        )
    }
}

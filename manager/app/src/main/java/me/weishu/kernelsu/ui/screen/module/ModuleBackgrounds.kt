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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
 * 存储：SharedPreferences（`module_backgrounds`），键 = 模块 id（首页状态卡这类没有模块 id
 * 的位置用固定槽位，如 `home_status`），值 = content Uri 字符串。
 * 图片经系统文件选择器（OpenDocument）选取并 takePersistableUriPermission，重启后仍有效。
 *
 * 解码：内存 LruCache（约 1/8 堆），按「Uri @ 目标宽度」缓存；大图按 inSampleSize
 * 降到 ~1024px 宽再解，模块卡用不到全分辨率。
 *
 * 渲染契约（用户要求「图要当主角，又不能掩盖字体」）：图铺满整卡后只压
 * [cardImageScrim] 那两层——一层很轻的介质薄纱 + 一条**底部加重**的竖向渐变
 * （中段全透，标题行与描述行两端各留一档）。文字永远画在 scrim 之上，深浅主题下
 * 对比度都不塌。scrim 色由调用方给「卡体介质色」（模块卡取 [XcNeon.colors.cardBg]），
 * 所以有背景的卡和普通玻璃卡观感是同一语言。
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
    internal val bitmapCache = object : LruCache<String, Bitmap>(maxSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun maxSizeKb(): Int {
        val heapKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (heapKb / 8).coerceAtLeast(8 * 1024)
    }

    internal suspend fun decode(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val src = Uri.parse(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, src)
                // 以 1024px 宽为上限等比降采样（ImageDecoder 的 allocator 默认硬件位图，
                // 这里强制软位图，便于后续可能的像素操作与低内存设备兼容）。
                ImageDecoder.decodeBitmap(source, object : ImageDecoder.OnHeaderDecodedListener {
                    // Android 的参数序：decoder 在前，info/source 在后。
                    override fun onHeaderDecoded(
                        decoder: ImageDecoder,
                        info: ImageDecoder.ImageInfo,
                        decoderSource: ImageDecoder.Source,
                    ) {
                        val w = info.size.width
                        val h = info.size.height
                        val maxDim = maxOf(w, h)
                        if (maxDim > TARGET_WIDTH * 2) {
                            val ratio = (TARGET_WIDTH * 2f) / maxDim
                            // setTargetSize(int,int) 挂在 ImageDecoder 上，
                            // 不在 ImageInfo 上（官方回调内用法 decoder.setTargetSize）。
                            decoder.setTargetSize(
                                (w * ratio).toInt().coerceAtLeast(1),
                                (h * ratio).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                })
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

// ---- 背景图上的 scrim：唯一一份实现，模块卡与首页状态卡共用 -------------------

/** 均匀薄纱浓度：旧版 0.68 会把照片糊成一块色斑；0.28 放图出来，中段正文还站得住。 */
private const val SCRIM_VEIL_ALPHA = 0.28f

/** 顶部一档：标题行 / 开关直接压在图上，靠它保住对比度。 */
private const val SCRIM_TOP_ALPHA = 0.32f

/** 底部一档：名称下方那几行（版本 / 描述 / 进度条）都在下半张，压得最重。 */
private const val SCRIM_BOTTOM_ALPHA = 0.58f

/**
 * 自定义背景图上的 scrim：一层薄纱 + 一条**底部加重**的竖向渐变。
 *
 * [medium] 必须是「卡体介质色」而不是写死的黑：深色档文字近白、要压深，浅色档文字
 * 近黑、只能压浅——写死 `Color.Black` 在浅色档会把近黑字压成糊。模块卡传
 * `XcNeon.colors.cardBg`，首页状态卡传 `Xc.colors.backdropScrim`。
 *
 * 渐变中段全透是有意的：图要露出来才有意义；两端各留一档，字才读得出来。
 * 尺寸与圆角由调用方负责（本 modifier 只画，不筛形状）。
 */
fun Modifier.cardImageScrim(medium: Color): Modifier = this
    .background(medium.copy(alpha = SCRIM_VEIL_ALPHA))
    .background(
        Brush.verticalGradient(
            0f to medium.copy(alpha = SCRIM_TOP_ALPHA),
            0.5f to medium.copy(alpha = 0f),
            1f to medium.copy(alpha = SCRIM_BOTTOM_ALPHA),
        )
    )

/**
 * 组合内取某个槽位的自定义背景图（已解码、已进 LruCache）。
 *
 * 槽位键与存储格式完全复用 [ModuleBackgrounds]（`module_backgrounds` 里的一条）：
 * 模块卡传模块 id，首页状态卡传固定槽位（`home_status`）。没有另起一套格式，
 * 将来任意入口给某个槽位写一条，这里就即时生效。
 *
 * Uri 从快照 map 读（[ModuleBackgrounds.backgroundOf]），所以设置 / 清除后自动重组；
 * 解码与缓存走 [rememberBackgroundBitmap] 那一条，和模块卡用的是同一份
 * [ModuleBackgrounds.bitmapCache]，不额外占内存。
 */
@Composable
fun rememberModuleBackground(slotId: String?): ImageBitmap? {
    if (slotId.isNullOrEmpty()) return null
    val context = LocalContext.current
    return rememberBackgroundBitmap(ModuleBackgrounds.backgroundOf(context, slotId))
}

/**
 * Uri → 解码后的背景图，模块卡直接拿着 Uri 时走这一条（不必再回查槽位）。
 *
 * `uri == null/空` 时整段提前返回 ⇒ `produceState` 的 remember 组一并销毁，槽位被清掉时
 * 不会残留上一张图（produceState 的 state 本身不随 key 自动清空，靠这层提前返回兜住）。
 */
@Composable
fun rememberBackgroundBitmap(uri: String?): ImageBitmap? {
    if (uri.isNullOrEmpty()) return null
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        val current = uri ?: return@produceState
        val cached = ModuleBackgrounds.bitmapCache.get(current)
        if (cached != null) {
            value = cached
        } else {
            val decoded = ModuleBackgrounds.decode(context, current)
            if (decoded != null) ModuleBackgrounds.bitmapCache.put(current, decoded)
            value = decoded
        }
    }
    // asImageBitmap() 每次都新建一层包装，包在 remember 里让 Image 拿到稳定实例。
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/**
 * 模块卡的背景层：图铺满整卡（圆角按 [shape] 裁）＋ [cardImageScrim]，
 * 整体画在卡玻璃体之上、内容之下。`uri == null` 时不渲染任何东西（零开销路径）。
 */
@Composable
fun ModuleCardBackdrop(uri: String?, shape: Shape, modifier: Modifier = Modifier) {
    if (uri.isNullOrEmpty()) return
    // 注意形参是「已解析好的背景 Uri」，不是槽位 id —— 别再回查一遍槽位。
    val image = rememberBackgroundBitmap(uri) ?: return
    Box(modifier = modifier.clip(shape)) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 介质色取模块页的 cardBg：深色档是夜蓝、浅色档是白，两档都压得住字。
        Box(
            Modifier
                .fillMaxSize()
                .cardImageScrim(XcNeon.colors.cardBg),
        )
    }
}

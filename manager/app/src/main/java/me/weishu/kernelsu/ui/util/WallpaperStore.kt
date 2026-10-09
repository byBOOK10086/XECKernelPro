package me.weishu.kernelsu.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 自定义背景（本地壁纸）的落盘仓库。
 *
 * 定位：**仅改本地**。这里只往应用私有目录写文件，不申请任何存储权限、不进媒体库、
 * 不联网；卸载应用时随私有目录一起消失。全应用的液态玻璃都采这张图（根层壁纸
 * `Image` 挂着 `layerBackdrop`），所以换图 = 换掉整屏玻璃的折射源。
 *
 * 几个刻意的取舍：
 * - **落盘前重编码**：相册原图动辄 10–30MB（HEIC / PNG / 超采样 JPEG）。直接拷进私有目录
 *   既白占空间，每次冷启动还要多解码一遍。统一降到最长边 [STORE_MAX_EDGE]、JPEG 92 质量，
 *   几百 KB 量级，肉眼无差。
 * - **文件名带时间戳**：同名覆盖会让"按路径 remember 的位图"命中旧内容（路径没变、字节变了）。
 *   这是所有图片类本地缓存都会踩的坑，带时间戳即天然失效。
 * - **解码走 [ImageDecoder]**：它会自动应用 EXIF 方向，`BitmapFactory` 不会——用户竖拍的照片
 *   用后者会横躺 90°。代价是 API 28+，而本模块 minSdk 31，不构成约束。
 */
object WallpaperStore {

    /** 浅色档槽位 id，同时是文件名前缀。 */
    const val LIGHT = "light"

    /** 深色档槽位 id，同时是文件名前缀。 */
    const val DARK = "dark"

    /**
     * 纯色档的哨兵"文件名"。
     *
     * 用 `@` 前缀是为了和真实文件名（`light-<时间戳>.jpg`）在结构上不可能撞车——这两个档位
     * 不落盘、不解码，只是"这一档固定用某个纯色"的标记。放在同一个设置项里，是为了让
     * "有没有自定义背景""要不要走内置随机池"这些已有判断一次都不用改。
     */
    const val SOLID_WHITE = "@white"

    /** 纯黑档哨兵，见 [SOLID_WHITE]。 */
    const val SOLID_BLACK = "@black"

    /** 该档位是不是纯色档（纯白 / 纯黑）。 */
    fun isSolid(name: String): Boolean = name == SOLID_WHITE || name == SOLID_BLACK

    /**
     * 纯色档对应的 ARGB 颜色；不是纯色档返回 null。
     *
     * 返回 Int 而不是 Compose 的 `Color`：这个文件是纯工具层（不依赖 Compose），
     * 调用侧用 `Color(argb)` 包一层即可。
     */
    fun solidArgb(name: String): Int? = when (name) {
        SOLID_WHITE -> 0xFFFFFFFF.toInt()
        SOLID_BLACK -> 0xFF000000.toInt()
        else -> null
    }

    /** 落盘最长边：2K 屏横屏放大也够，同时把原图压到几百 KB。 */
    private const val STORE_MAX_EDGE = 2560

    /** 根层壁纸的显示最长边：超过屏幕像素没有意义，只会白占内存。 */
    const val DISPLAY_MAX_EDGE = 2048

    /** 设置页缩略图的最长边：没必要为了一个小方块解码整张图。 */
    const val PREVIEW_MAX_EDGE = 512

    /** 背景模糊 100% 对应的降采样倍率上限（1 + 15 = 16 倍，2048 → 128px）。 */
    private const val BLUR_MAX_SHRINK = 15f

    /** 降采样步长：量化到 64px 一档，拖动条每动 1% 不至于重新解码一次。 */
    private const val BLUR_EDGE_STEP = 64

    private const val DIR_NAME = "wallpapers"
    private const val JPEG_QUALITY = 92

    /** 私有目录下的壁纸目录，按需创建。 */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun fileOf(context: Context, name: String): File = File(dir(context), name)

    /**
     * 背景模糊百分比 → 根层壁纸的解码最长边。
     *
     * 模糊不用 `Modifier.blur` 实现，而是"解码时就解小、绘制时放大"：根层壁纸同时是全应用
     * 玻璃的采样源，在它上面挂 RenderEffect 会让每一次采样都重跑一遍全屏模糊，滚动时肉眼
     * 可见掉帧；而把 2048px 的图解成 128px 再放大，只在换图/松手时算一次，之后每帧零开销，
     * 观感正是 iOS 那种糊化壁纸。
     *
     * 0% 返回 [DISPLAY_MAX_EDGE]（与原行为完全一致，不多解一次小图）；
     * 其余按 1+15×p 倍降采样并量化到 [BLUR_EDGE_STEP] 一档，下限 128px。
     */
    fun displayEdgeFor(blurPercent: Int): Int {
        val percent = blurPercent.coerceIn(0, 100)
        if (percent == 0) return DISPLAY_MAX_EDGE
        val shrink = 1f + percent / 100f * BLUR_MAX_SHRINK
        val edge = (DISPLAY_MAX_EDGE / shrink / BLUR_EDGE_STEP).roundToInt() * BLUR_EDGE_STEP
        return edge.coerceIn(128, DISPLAY_MAX_EDGE)
    }

    /**
     * 把内置池的 drawable 按最长边 [maxEdge] 解成位图（模糊档专用）。
     *
     * 用 [BitmapFactory] 而不是 `ImageDecoder`：这里要的正是"粗糙的降采样"，
     * `inSampleSize` 的 2 的幂次步进足够，且不必再走一遍 ImageDecoder 的开销。
     * 解码失败返回 null，调用方回落到原来的 `painterResource` 路径。
     */
    fun decodeResource(context: Context, resId: Int, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeResource(
            context.resources,
            resId,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()

    /** 名字为空、纯色档标记、或文件已被清掉都算"没有自定义图"，但前两者各自另有渲染路径。 */
    fun exists(context: Context, name: String): Boolean =
        name.isNotEmpty() && (isSolid(name) || fileOf(context, name).isFile)

    /** 解码一张本地壁纸；文件不存在、格式不支持时返回 null（调用方回落到内置池）。 */
    fun decode(file: File, maxEdge: Int): Bitmap? {
        if (!file.isFile) return null
        return runCatching {
            decodeSource(ImageDecoder.createSource(file), maxEdge)
        }.getOrNull()
    }

    /**
     * 把相册选中的图导入 [slot]，返回新文件名（调用方把它写进设置）。
     *
     * 失败会抛出异常，由调用方提示；成功时同槽位的旧文件会被清掉，不留孤儿文件。
     */
    suspend fun import(context: Context, uri: Uri, slot: String): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSource(ImageDecoder.createSource(context.contentResolver, uri), STORE_MAX_EDGE)
        val target = File(dir(context), "$slot-${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(target).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    "failed to encode wallpaper"
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        prune(context, slot, keep = target.name)
        target.name
    }

    /** 丢弃一张壁纸（切回内置池或换成纯色档时调用）。纯色档没有文件，直接忽略。 */
    fun discard(context: Context, name: String) {
        if (name.isEmpty() || isSolid(name)) return
        fileOf(context, name).delete()
    }

    /** 清空全部自定义壁纸（"恢复内置随机池"）。 */
    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * 统一解码入口。
     *
     * `allocator` 必须显式要软件位图：`ImageDecoder` 默认给硬件位图，而硬件位图读不回像素，
     * 后续 `Bitmap.compress()` 会直接失败——这是导入路径上唯一一个"看起来能跑但一定炸"的点。
     *
     * 缩放在解码器内部完成（`setTargetSize`），不是先解全图再缩放：一张 100MP 的原图
     * 若先整解再缩小，峰值内存能到几百 MB。注意 `setTargetSize` 与 `setTargetSampleSize`
     * 互斥，同时调用会抛 `IllegalStateException`，所以这里只用前者。
     */
    private fun decodeSource(source: ImageDecoder.Source, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (maxEdge > 0 && longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }

    /** 只保留 [keep]，同槽位的其余历史文件一并清理。 */
    private fun prune(context: Context, slot: String, keep: String) {
        dir(context).listFiles()?.forEach { file ->
            if (file.name.startsWith("$slot-") && file.name != keep) file.delete()
        }
    }
}

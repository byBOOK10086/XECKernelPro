package me.weishu.kernelsu.ui.design.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.theme.ThemeResolver
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlockBackground
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.WallpaperStore
import java.io.File

/**
 * 卡片背景图的解码上限。
 *
 * 卡片在屏幕上最宽也就几百 dp，原图（用户随手一张上千万像素的照片）整张解码进来只是白占
 * 内存 —— 超过这个边长的按比例缩到这条边上，跟 `MyThemesTab` 的预览缩略图同一个思路。
 * 512 是"高密度屏上铺满一张卡片还有余量"的档位。
 */
private const val XC_BLOCK_MAX_EDGE = 512

/**
 * 解码好的卡片背景图缓存。
 *
 * 为什么必须有这一层：
 *  * 首页每次滚动/重组都会走到卡片的绘制，读盘 + 解码绝不能落在组合里；
 *  * 同一张图会被**反复问**（重组一次问一次），没有缓存就等于每次都进一次 IO 队列。
 *
 * 用快照态的表：解码在 IO 线程写入，这个表本身是线程安全的（内部的快照机制负责同步），
 * 主线程读到的是"当时这一刻已经解好的那些"。
 *
 * 失效只有一条路：[clearXcBlockImages]，由 `ThemeResolver.invalidate()` 调用 —— 换主题、
 * 保存主题、删除主题都会走到那里。缓存以文件路径为键：
 * 路径本身带着主题 id（`filesDir/themes/<id>/block-….jpg`），重装同一 id 会整目录替换、
 * 路径不变但内容变了时，代数变化会让仍在屏幕上的区块重新解码。
 */
internal val XcBlockImageCache = mutableStateMapOf<String, ImageBitmap>()
private val imageCacheLock = Any()
private var imageGeneration by mutableIntStateOf(0)

/** 清空解码缓存（主题变了必须清，见上）。 */
fun clearXcBlockImages() {
    synchronized(imageCacheLock) {
        Snapshot.withMutableSnapshot {
            imageGeneration++
            XcBlockImageCache.clear()
        }
    }
}

/**
 * 取某个区块当前生效的背景，并把它的图解好。
 *
 * 拿到非 null 的返回值 = "这张卡片有背景，且图（如果有）已经可以画了"；返回 null =
 * 今天的样子（[xGlassBody] 的 `block` 形参拿到 null 就一次绘制都不用多）。
 *
 * 三条规则：
 *  * `when` 是 `dark` / `light` 的区块在另一档**连解码都不发生**（直接返回 null）；
 *  * 没设图（只设了颜色）的区块没有解码这一步，颜色是纯数据，改一下立刻生效；
 *  * 解码走 [WallpaperStore.decode] —— 这是本仓库里既有的"文件 -> Bitmap -> asImageBitmap"
 *    链路（主题预览缩略图、壁纸缩略图都是它），不引第三方图片库。
 *
 * 生产者跟随 [ThemeResolver] 给的路径与缓存代数：颜色 / 透明度都是纯数据，
 * 改了直接重算就行，把整条背景当 key 会让"拖一下透明度"顺手丢掉已经解好的位图。
 */
@Composable
fun rememberXcBlock(id: String, isDark: Boolean = isInDarkTheme()): XcBlockBackground? {
    val block = Xc.block(id)
    val path = block?.imagePath
    val visible = block != null && block.visibleIn(isDark)
    // 这一档不画它的区块根本没有"图要解"这回事（key = null 就是"不用解"）。
    val key = if (!visible || path == null) null else path
    val generation = imageGeneration
    LaunchedEffect(key, generation) {
        if (key == null || XcBlockImageCache.containsKey(key)) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            val file = File(path.orEmpty())
            if (file.isFile) WallpaperStore.decode(file, XC_BLOCK_MAX_EDGE)?.asImageBitmap() else null
        }
        if (decoded != null) {
            synchronized(imageCacheLock) {
                // A decode started before invalidation must not refill the new cache.
                if (generation == imageGeneration) XcBlockImageCache[key] = decoded
            }
        }
    }
    // 纯色区块不需要位图；切回可见档时优先复用当前代数的缓存。
    if (!visible) return null
    if (path != null && !XcBlockImageCache.containsKey(path)) return null
    return block
}

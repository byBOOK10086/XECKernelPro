package me.weishu.kernelsu.ui.design.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
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
 * 保存主题、删除主题都会走到那里，所以不存在"换了主题还画着旧图"。键就是**文件路径**：
 * 路径本身带着主题 id（`filesDir/themes/<id>/block-….jpg`），重装同一 id 会整目录替换、
 * 路径不变但内容变了 —— 那种情况正是靠 [clearXcBlockImages] 兜住的，不是靠键。
 */
internal val XcBlockImageCache = mutableStateMapOf<String, ImageBitmap>()

/** 清空解码缓存（主题变了必须清，见上）。 */
fun clearXcBlockImages() {
    XcBlockImageCache.clear()
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
 * 生产者只钉 [ThemeResolver] 给的那条路径与主题 id：颜色 / 透明度 / 明暗档都是纯数据，
 * 改了直接重算就行，把整条背景当 key 会让"拖一下透明度"顺手丢掉已经解好的位图。
 */
@Composable
fun rememberXcBlock(id: String, isDark: Boolean = isInDarkTheme()): XcBlockBackground? {
    val block = Xc.block(id)
    val path = block?.imagePath
    val visible = block != null && block.visibleIn(isDark)
    // 这一档不画它的区块根本没有"图要解"这回事（key = null 就是"不用解"）。
    val key = if (!visible || path == null) null else path
    val ready by produceState<Boolean>(
        // 颜色和透明/黑纱都是纯数据：没图可解时这里一开始就算"准备好了"，
        // 少跑一次多余的组合（真正的取舍在下面那个 `path == null`）。
        initialValue = key == null,
        key,
    ) {
        if (key == null) return@produceState
        if (XcBlockImageCache.containsKey(key)) {
            value = true
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            val file = File(path.orEmpty())
            if (file.isFile) WallpaperStore.decode(file, XC_BLOCK_MAX_EDGE)?.asImageBitmap() else null
        }
        if (decoded != null) {
            // 先落表（下次重组直接命中），再落 value（叫醒这一次）。
            XcBlockImageCache[key] = decoded
            value = true
        }
    }
    // `visible` 不参与生产者的键：明暗档换了不用重跑，图已经在缓存里，切回去立刻就能画。
    if (!visible || path == null || !ready) return null
    return block
}

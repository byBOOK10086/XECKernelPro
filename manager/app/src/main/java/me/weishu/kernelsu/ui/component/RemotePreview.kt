package me.weishu.kernelsu.ui.component

/*
 * 远程预览图：主题商店卡片/横幅用的最小异步图片组件。
 *
 * 不引入 Coil/Glide：主题索引的预览图是**低频、小尺寸、强缓存**的场景，
 * 一个 URLConnection + 降采样解码 + 进程内缓存就够了——与项目
 * "零反射、零重依赖"的口径一致。失败安静回落占位块，不重试不打扰。
 */
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PREVIEW_DIM = 1440
private const val CACHE_BYTES = 24 * 1024 * 1024
/** 响应体上限：预览图不需要更大；防止恶意/错误 URL 拖爆内存。 */
private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val USER_AGENT = "XECKernelPro-Manager"

private val cacheMap: MutableMap<String, ImageBitmap> =
    Collections.synchronizedMap(LinkedHashMap<String, ImageBitmap>())

/** 每个 URL 一把锁：同 URL 并发进入时只有一个真正发请求（single-flight），其余等缓存。 */
private val urlLocks = ConcurrentHashMap<String, Mutex>()

private fun cacheGet(url: String): ImageBitmap? = synchronized(cacheMap) { cacheMap[url] }

private fun cachePut(url: String, bmp: ImageBitmap) = synchronized(cacheMap) {
    cacheMap[url] = bmp
    // 超限整体换血：预览图都是小图，逐条 LRU 记账不值得。
    var bytes = cacheMap.values.sumOf { it.asAndroidBitmap().byteCount }
    while (bytes > CACHE_BYTES && cacheMap.isNotEmpty()) {
        val first = cacheMap.keys.first()
        bytes -= cacheMap.remove(first)!!.asAndroidBitmap().byteCount
    }
}

private fun openGet(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        instanceFollowRedirects = true
    }

private suspend fun getPreview(url: String): ImageBitmap? =
    urlLocks.getOrPut(url) { Mutex() }.withLock {
        cacheGet(url)?.let { return it }
        fetchPreview(url)?.also { cachePut(url, it) }
    }

private suspend fun fetchPreview(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = openGet(url)
        try {
            val length = conn.contentLengthLong
            if (length > MAX_DOWNLOAD_BYTES) return@runCatching null
            conn.inputStream.use { input ->
                val bounded = if (length > 0) input else input.let { s ->
                    // 无 Content-Length 时也按上限截断，读满即弃。
                    object : java.io.FilterInputStream(s) {
                        private var read: Long = 0
                        override fun read(): Int {
                            if (++read > MAX_DOWNLOAD_BYTES) return -1
                            return super.read()
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (read > MAX_DOWNLOAD_BYTES) return -1
                            val n = super.read(b, off, len)
                            if (n > 0) read += n
                            return n
                        }
                    }
                }
                val bmp = BitmapFactory.decodeStream(bounded) ?: return@runCatching null
                val over = maxOf(bmp.width, bmp.height)
                val scaled = if (over > MAX_PREVIEW_DIM) {
                    val f = MAX_PREVIEW_DIM.toFloat() / over
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * f).toInt().coerceAtLeast(1),
                        (bmp.height * f).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bmp
                }
                scaled.asImageBitmap()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/**
 * 远程预览图。加载中/失败画占位底色；成功后按 [contentScale] 铺满。
 * [url] 为空或重复进入组合时直接命中缓存，不重复请求。
 */
@Composable
fun RemotePreview(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = Color.Transparent,
) {
    var bitmap by remember(url) { mutableStateOf(url?.let { cacheGet(it) }) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        // getPreview 内部是 per-URL 单飞：并发进入同一 URL 只发一次请求。
        bitmap = getPreview(url)
    }
    Box(modifier = modifier.background(placeholder)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

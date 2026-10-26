package me.weishu.kernelsu.ui.component

/*
 * 远程预览图：主题商店卡片/横幅/详情页用的异步图片组件。
 *
 * 不引入 Coil/Glide：索引里的图是**低频、小尺寸、强缓存**的场景，一个 URLConnection
 * + 降采样解码 + 两级缓存就够了 —— 与项目"零反射、零重依赖"的口径一致。
 *
 * 三级保障（都是被真实网络教出来的）：
 *  1. **进程内 LRU**：按字节数封顶，访问序淘汰（不是清空重来）。
 *  2. **磁盘缓存**：`filesDir/store-img/<url sha256>`；进程重启/换页后不再重新下载，
 *     离线也能把商店画满。目录超过上限按最近使用时间删旧。
 *  3. **镜像回退**：索引里的图可能挂在 jsDelivr（国内有时抽风），这里复用
 *     [me.weishu.kernelsu.data.theme.StoreMirrors] 把同一张图在 jsDelivr ↔ raw 之间换域名重试。
 *
 * 加载态对外暴露（[RemoteImageState]）：卡片可以用它画骨架屏，而不是永远一个死灰块。
 */
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.theme.StoreMirrors
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PREVIEW_DIM = 1440
private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
private const val DISK_CACHE_BYTES = 48L * 1024 * 1024
private const val DISK_CACHE_DIR = "store-img"
/** 响应体上限：预览图不需要更大；防止恶意/错误 URL 拖爆内存。 */
private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val USER_AGENT = "XECKernelPro-Manager"

/** 一张远程图的三种状态。 */
sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data class Ready(val bitmap: ImageBitmap) : RemoteImageState
    data object Failed : RemoteImageState
}

/** 访问序 LRU（`accessOrder = true`）：命中就挪到队尾，淘汰从队首开始。 */
private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean = false
}

private val cacheLock = Any()

/** 每个 URL 一把锁：同 URL 并发进入时只有一个真正发请求（single-flight）。 */
private val urlLocks = ConcurrentHashMap<String, Mutex>()

private fun cacheGet(url: String): ImageBitmap? = synchronized(cacheLock) { memoryCache[url] }

private fun cachePut(url: String, bitmap: ImageBitmap) = synchronized(cacheLock) {
    memoryCache[url] = bitmap
    var bytes = memoryCache.values.sumOf { it.asAndroidBitmap().byteCount }
    val iterator = memoryCache.entries.iterator()
    while (bytes > MEMORY_CACHE_BYTES && iterator.hasNext()) {
        val entry = iterator.next()
        bytes -= entry.value.asAndroidBitmap().byteCount
        iterator.remove()
    }
}

private fun digestOf(value: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(value.toByteArray())
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value2 = byte.toInt() and 0xFF
        if (value2 < 0x10) out.append('0')
        out.append(Integer.toHexString(value2))
    }
    return out.toString()
}

private fun diskFile(context: Context, url: String) =
    java.io.File(java.io.File(context.filesDir, DISK_CACHE_DIR).apply { mkdirs() }, digestOf(url))

private fun diskGet(context: Context, url: String): ByteArray? = runCatching {
    val file = diskFile(context, url)
    if (!file.isFile || file.length() <= 0L) return null
    // 触碰 mtime，让 LRU 清理按"最近使用"来删。
    file.setLastModified(System.currentTimeMillis())
    file.readBytes()
}.getOrNull()

private fun diskPut(context: Context, url: String, bytes: ByteArray) {
    runCatching {
        val dir = java.io.File(context.filesDir, DISK_CACHE_DIR).apply { mkdirs() }
        diskFile(context, url).writeBytes(bytes)
        var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= DISK_CACHE_BYTES) return
        val victims = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        for (victim in victims) {
            if (total <= DISK_CACHE_BYTES) break
            val length = victim.length()
            if (victim.delete()) total -= length
        }
    }
}

private fun openGet(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        instanceFollowRedirects = true
    }

/** 下载原始字节：只取 HTTP 200，限长，超长/失败返回 null。 */
private fun fetchBytes(url: String): ByteArray? = runCatching {
    val conn = openGet(url)
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val length = conn.contentLengthLong
        if (length > MAX_DOWNLOAD_BYTES) return null
        conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(if (length > 0) length.toInt() else 8192)
            val buffer = ByteArray(16 * 1024)
            var read: Long = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                read += n
                if (read > MAX_DOWNLOAD_BYTES) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

/** 降采样解码：先读边界算采样率，避免把 4K 图整张读进内存。 */
private fun decode(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var over = maxOf(bounds.outWidth, bounds.outHeight)
    while (over / sample > MAX_PREVIEW_DIM) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return bitmap.asImageBitmap()
}

private suspend fun loadImage(context: Context, url: String): ImageBitmap? =
    urlLocks.getOrPut(url) { Mutex() }.withLock {
        cacheGet(url)?.let { return it }
        withContext(Dispatchers.IO) {
            // 1) 磁盘缓存；2) 原地址；3) 镜像地址（jsDelivr ↔ raw）。
            diskGet(context, url)?.let { decode(it) }?.let { bitmap ->
                cachePut(url, bitmap)
                return@withContext bitmap
            }
            val candidates = runCatching { StoreMirrors.candidates(url) }
                .getOrElse { listOf(url) }
                .ifEmpty { listOf(url) }
            for (candidate in candidates) {
                val bytes = fetchBytes(candidate) ?: continue
                val bitmap = decode(bytes) ?: continue
                diskPut(context, url, bytes)
                cachePut(url, bitmap)
                return@withContext bitmap
            }
            null
        }
    }

/**
 * 记一张远程图的状态（Loading → Ready/Failed）。
 *
 * 命中内存缓存时初始值就是 Ready，不会先闪一下骨架屏。
 */
@Composable
fun rememberRemoteImage(url: String?): State<RemoteImageState> {
    val context = LocalContext.current
    return produceState<RemoteImageState>(
        initialValue = url?.let { cacheGet(it) }?.let { RemoteImageState.Ready(it) }
            ?: RemoteImageState.Loading,
        key1 = url,
    ) {
        if (url.isNullOrEmpty()) {
            value = RemoteImageState.Failed
            return@produceState
        }
        cacheGet(url)?.let {
            value = RemoteImageState.Ready(it)
            return@produceState
        }
        value = RemoteImageState.Loading
        val bitmap = loadImage(context, url)
        value = if (bitmap != null) RemoteImageState.Ready(bitmap) else RemoteImageState.Failed
    }
}

/**
 * 远程预览图。加载中/失败画占位底色；成功后按 [contentScale] 铺满。
 * [url] 为空时只画占位块；重复进入组合直接命中缓存，不重复请求。
 */
@Composable
fun RemotePreview(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = Color.Transparent,
) {
    val state by rememberRemoteImage(url)
    Box(modifier = modifier.background(placeholder)) {
        val ready = state as? RemoteImageState.Ready
        if (ready != null) {
            Image(
                bitmap = ready.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

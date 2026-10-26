package me.weishu.kernelsu.data.theme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.hasInternetTransport
import me.weishu.kernelsu.ui.util.sha256Of
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 商城索引仓库：**网络（多镜像 + ETag）→ 解析 → 磁盘快照**。
 *
 * 三条硬约束：
 *  1. **首屏不许等网络**：[cached] / [load] 先给磁盘快照，商店 0ms 就能画出来；
 *     快照新鲜（[AUTO_REFRESH_MS] 内）时干脆不发请求。
 *  2. **快照必须原子落地**：先写 `themes.json.tmp` 再 `renameTo`，进程被杀也不会留下
 *     半截 JSON 把下次启动带沟里（模块仓库那边踩过一次）。
 *  3. **失败也尽量有内容**：网络全挂但磁盘有旧快照时返回旧快照（[Snapshot.fromCache]），
 *     只有连快照都没有才把失败抛给 UI。
 *
 * 网络闸门用 [hasInternetTransport] 而不是"VALIDATED"：校园网 / 强制门户会让
 * `NET_CAPABILITY_VALIDATED` 长期为 false，但请求往往能通 —— 能不能通交给请求层判断。
 *
 * 缓存目录：`filesDir/theme-store/`（`themes.json` + `meta.json`），与主题包目录分开，
 * 卸载/清数据时一起走，不会留公共目录残留。
 */
object StoreCatalogRepository {

    /** 快照超过这个年龄就主动联网刷新（用户点刷新无条件走网络）。 */
    const val AUTO_REFRESH_MS: Long = 30L * 60 * 1000

    /** 索引体积上限：正常索引几十 KB，给到 4MB 已经很宽松。 */
    private const val MAX_INDEX_BYTES: Long = 4L * 1024 * 1024

    private const val DIR_NAME = "theme-store"
    private const val FILE_INDEX = "themes.json"
    private const val FILE_META = "meta.json"
    private const val USER_AGENT = "XECKernelPro-Manager"

    /** 一次成功加载的结果。[fromCache] = true 表示这次没有真的联网成功（用的是磁盘快照）。 */
    data class Snapshot(
        val catalog: StoreCatalog,
        val fetchedAt: Long,
        val sourceUrl: String,
        val fromCache: Boolean,
    ) {
        val ageMs: Long get() = (System.currentTimeMillis() - fetchedAt).coerceAtLeast(0L)
        val stale: Boolean get() = ageMs > AUTO_REFRESH_MS
    }

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun indexFile(context: Context) = File(dir(context), FILE_INDEX)

    private fun metaFile(context: Context) = File(dir(context), FILE_META)

    // ------------------------------------------------------------------ 读缓存

    /** 同步读磁盘快照（调用方负责放到 IO 线程）。坏快照直接删掉，别让它一直拖后腿。 */
    fun cached(context: Context): Snapshot? {
        val file = indexFile(context)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val body = file.readText()
            val meta = runCatching { JSONObject(metaFile(context).readText()) }.getOrNull()
            val installed = installedMap(context)
            val catalog = StoreIndexCodec.decode(body, installed)
            Snapshot(
                catalog = catalog,
                fetchedAt = meta?.optLong("fetchedAt", 0L) ?: file.lastModified(),
                sourceUrl = meta?.optString("source", "").orEmpty(),
                fromCache = true,
            )
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    /** 缓存的年龄（毫秒）；没有缓存返回 [Long.MAX_VALUE]。 */
    fun cacheAge(context: Context): Long {
        val file = indexFile(context)
        if (!file.isFile) return Long.MAX_VALUE
        val meta = runCatching { JSONObject(metaFile(context).readText()) }.getOrNull()
        val fetchedAt = meta?.optLong("fetchedAt", 0L) ?: file.lastModified()
        if (fetchedAt <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - fetchedAt).coerceAtLeast(0L)
    }

    fun clearCache(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    private fun writeCache(context: Context, body: String, source: String, etag: String?) {
        val target = indexFile(context)
        val tmp = File(target.parentFile, "$FILE_INDEX.tmp")
        runCatching {
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                // rename 在极少数文件系统上会失败（已有同名文件），退化成覆盖写。
                target.writeText(body)
                tmp.delete()
            }
            val oldEtag = runCatching { JSONObject(metaFile(context).readText()) }
                .getOrNull()
                ?.optJSONObject("etags")
            val etags = oldEtag ?: JSONObject()
            if (!etag.isNullOrEmpty()) etags.put(source, etag)
            val meta = JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("source", source)
                .put("schema", StoreIndexCodec.SCHEMA_LATEST)
                .put("etags", etags)
            metaFile(context).writeText(meta.toString())
        }
    }

    // ------------------------------------------------------------- 联网 + 解析

    /**
     * 加载索引。
     *
     * @param force true = 用户主动刷新：跳过新鲜度判断，直接联网（仍会在失败时退回旧快照）。
     */
    suspend fun load(context: Context, force: Boolean = false): Result<Snapshot> =
        withContext(Dispatchers.IO) {
            val installed = installedMap(context)
            val snapshot = cached(context)
            if (!force && snapshot != null && snapshot.ageMs < AUTO_REFRESH_MS) {
                return@withContext Result.success(snapshot)
            }
            if (!hasInternetTransport(ksuApp)) {
                return@withContext when {
                    snapshot != null -> Result.success(snapshot)
                    else -> Result.failure(StoreFailure.NoNetwork())
                }
            }

            var attempts = 0
            var lastCode = 0
            for (source in ThemeStoreSettings.sources()) {
                attempts++
                try {
                    when (val outcome = fetch(context, source, ifNoneMatch(context, source), force)) {
                        is Fetch.NotModified -> {
                            val current = snapshot
                            if (current != null) {
                                touchMeta(context, current.fetchedAt)
                                return@withContext Result.success(current.copy(fromCache = true))
                            }
                            // 没有本地快照却回了 304：换下一个源（这次不带 ETag）。
                        }

                        is Fetch.Body -> {
                            val catalog = StoreIndexCodec.decode(outcome.text, installed)
                            writeCache(context, outcome.text, source, outcome.etag)
                            return@withContext Result.success(
                                Snapshot(
                                    catalog = catalog,
                                    fetchedAt = System.currentTimeMillis(),
                                    sourceUrl = source,
                                    fromCache = false,
                                )
                            )
                        }
                    }
                } catch (failure: StoreFailure) {
                    if (failure is StoreFailure.Parse) {
                        // 结构错是"这个源的内容不能用"，继续试下一个（镜像可能还没同步）。
                        lastCode = 0
                        continue
                    }
                    throw failure
                } catch (e: Exception) {
                    lastCode = (e as? HttpStatusException)?.code ?: 0
                }
            }

            if (snapshot != null) {
                return@withContext Result.success(snapshot)
            }
            Result.failure(StoreFailure.Unreachable(attempts, lastCode))
        }

    /**
     * 本地已装：`themeId → versionCode`。
     *
     * 故意**不是** suspend：它会被 [cached] 这类同步读缓存的函数调用，而那两个函数的
     * 契约就是"调用方已经在 IO 线程上"。里面再套一层 withContext 只会白加一次调度。
     */
    private fun installedMap(context: Context): Map<String, Int> = runCatching {
        ThemeRepository.list(context).associate { it.spec.id to it.spec.versionCode }
    }.getOrDefault(emptyMap())

    /** 只更新快照时间戳（304 之后让 UI 的"上次更新"跟着走）。 */
    private fun touchMeta(context: Context, previous: Long) {
        val file = metaFile(context)
        runCatching {
            val meta = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            meta.put("fetchedAt", maxOf(previous, System.currentTimeMillis()))
            file.writeText(meta.toString())
        }
    }

    private fun ifNoneMatch(context: Context, source: String): String? = runCatching {
        val meta = JSONObject(metaFile(context).readText())
        meta.optJSONObject("etags")?.optString(source, "")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private sealed interface Fetch {
        data object NotModified : Fetch
        class Body(val text: String, val etag: String?) : Fetch
    }

    private fun fetch(
        context: Context,
        url: String,
        etag: String?,
        force: Boolean,
    ): Fetch {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)
        if (force) builder.header("Cache-Control", "no-cache")
        ksuApp.okhttpClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 304) return Fetch.NotModified
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val declared = response.body.contentLength()
            if (declared > MAX_INDEX_BYTES) {
                throw StoreFailure.Parse("index too large: $declared")
            }
            val text = response.body.string()
            if (text.length > MAX_INDEX_BYTES) {
                throw StoreFailure.Parse("index too large: ${text.length}")
            }
            return Fetch.Body(text, response.header("ETag"))
        }
    }

    /** 下载 + 校验 + 装进本地主题仓库。失败一律是类型化 [StoreFailure]。 */
    suspend fun downloadAndInstall(
        context: Context,
        theme: StoreTheme,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result<ThemeRepository.Entry> = withContext(Dispatchers.IO) {
        if (theme.downloadUrl.isEmpty()) {
            return@withContext Result.failure(StoreFailure.Parse("theme has no download url"))
        }
        val tmp = File(context.cacheDir, "theme-${theme.themeId}-${theme.versionCode}.xectheme")
        val candidates = StoreMirrors.candidates(theme.downloadUrl)
        var corrupted: StoreFailure? = null
        try {
            for (url in candidates) {
                if (isCancelled()) return@withContext Result.failure(StoreFailure.Cancelled())
                try {
                    if (tmp.exists()) tmp.delete()
                    me.weishu.kernelsu.ui.screen.remote.RemoteDownloader.downloadTo(
                        url,
                        tmp,
                    ) { read, total ->
                        if (isCancelled()) throw CancellationSignal()
                        onProgress(read, total)
                    }
                    if (theme.sha256.isNotEmpty()) {
                        val actual = sha256Of(tmp)
                        if (actual == null || !actual.equals(theme.sha256, ignoreCase = true)) {
                            corrupted = StoreFailure.Corrupted("sha256 mismatch")
                            continue
                        }
                    }
                    val entry = ThemeRepository.install(
                        context,
                        tmp.readBytes(),
                        ThemeRepository.Origin.STORE,
                    )
                    return@withContext Result.success(entry)
                } catch (signal: CancellationSignal) {
                    return@withContext Result.failure(StoreFailure.Cancelled())
                } catch (e: Exception) {
                    corrupted = corrupted ?: StoreFailure.Unknown(e.message ?: e::class.java.simpleName)
                }
            }
            val failure: StoreFailure = corrupted ?: StoreFailure.Unreachable(candidates.size, 0)
            Result.failure(failure)
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** 取消信号（用异常打断阻塞下载，比轮询文件大小可靠）。 */
    private class CancellationSignal : RuntimeException()

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")
}

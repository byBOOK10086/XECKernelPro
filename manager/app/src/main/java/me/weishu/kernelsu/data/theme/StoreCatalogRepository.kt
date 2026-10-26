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
 * 鍟嗗煄绱㈠紩浠撳簱锛?*缃戠粶锛堝闀滃儚 + ETag锛夆啋 瑙ｆ瀽 鈫?纾佺洏蹇収**銆? *
 * 涓夋潯纭害鏉燂細
 *  1. **棣栧睆涓嶈绛夌綉缁?*锛歔cached] / [load] 鍏堢粰纾佺洏蹇収锛屽晢搴?0ms 灏辫兘鐢诲嚭鏉ワ紱
 *     蹇収鏂伴矞锛圼AUTO_REFRESH_MS] 鍐咃級鏃跺共鑴嗕笉鍙戣姹傘€? *  2. **蹇収蹇呴』鍘熷瓙钀藉湴**锛氬厛鍐?`themes.json.tmp` 鍐?`renameTo`锛岃繘绋嬭鏉€涔熶笉浼氱暀涓? *     鍗婃埅 JSON 鎶婁笅娆″惎鍔ㄥ甫娌熼噷锛堟ā鍧椾粨搴撻偅杈硅俯杩囦竴娆★級銆? *  3. **澶辫触涔熷敖閲忔湁鍐呭**锛氱綉缁滃叏鎸備絾纾佺洏鏈夋棫蹇収鏃惰繑鍥炴棫蹇収锛圼Snapshot.fromCache]锛夛紝
 *     鍙湁杩炲揩鐓ч兘娌℃湁鎵嶆妸澶辫触鎶涚粰 UI銆? *
 * 缂撳瓨鐩綍锛歚filesDir/theme-store/`锛坄themes.json` + `meta.json`锛夛紝涓庝富棰樺寘鐩綍鍒嗗紑锛? * 鍗歌浇/娓呮暟鎹椂涓€璧疯蛋锛屼笉浼氱暀鍏叡鐩綍娈嬬暀銆? */
object StoreCatalogRepository {

    /** 蹇収瓒呰繃杩欎釜骞撮緞灏变富鍔ㄨ仈缃戝埛鏂帮紙鐢ㄦ埛鐐瑰埛鏂版棤鏉′欢璧扮綉缁滐級銆?*/
    const val AUTO_REFRESH_MS: Long = 30L * 60 * 1000

    /** 绱㈠紩浣撶Н涓婇檺锛氭甯哥储寮曞嚑鍗?KB锛岀粰鍒?4MB 宸茬粡寰堝鏉俱€?*/
    private const val MAX_INDEX_BYTES: Long = 4L * 1024 * 1024

    private const val DIR_NAME = "theme-store"
    private const val FILE_INDEX = "themes.json"
    private const val FILE_META = "meta.json"
    private const val USER_AGENT = "XECKernelPro-Manager"

    /** 涓€娆℃垚鍔熷姞杞界殑缁撴灉銆俒fromCache] = true 琛ㄧず杩欐娌℃湁鐪熺殑鑱旂綉鎴愬姛锛堢敤鐨勬槸纾佺洏蹇収锛夈€?*/
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

    // ------------------------------------------------------------------ 璇荤紦瀛?
    /** 鍚屾璇荤鐩樺揩鐓э紙璋冪敤鏂硅礋璐ｆ斁鍒?IO 绾跨▼锛夈€傚潖蹇収鐩存帴鍒犳帀锛屽埆璁╁畠涓€鐩存嫋鍚庤吙銆?*/
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

    /** 缂撳瓨鐨勫勾榫勶紙姣锛夛紱娌℃湁缂撳瓨杩斿洖 [Long.MAX_VALUE]銆?*/
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
                // rename 鍦ㄦ瀬灏戞暟鏂囦欢绯荤粺涓婁細澶辫触锛堝凡鏈夊悓鍚嶆枃浠讹級锛岄€€鍖栨垚瑕嗙洊鍐欍€?                target.writeText(body)
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

    // ------------------------------------------------------------- 鑱旂綉 + 瑙ｆ瀽

    /**
     * 鍔犺浇绱㈠紩銆?     *
     * @param force true = 鐢ㄦ埛涓诲姩鍒锋柊锛氳烦杩囨柊椴滃害鍒ゆ柇锛岀洿鎺ヨ仈缃戯紙浠嶄細鍦ㄥけ璐ユ椂閫€鍥炴棫蹇収锛夈€?     */
    suspend fun load(context: Context, force: Boolean = false): Result<Snapshot> = withContext(Dispatchers.IO) {
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
                        // 娌℃湁鏈湴蹇収鍗村洖浜?304锛氭崲涓嬩竴涓簮锛堣繖娆′笉甯?ETag锛夈€?                    }

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
                    // 缁撴瀯閿欐槸"杩欎釜婧愮殑鍐呭涓嶈兘鐢?锛岃涓嬫潵缁х画璇曚笅涓€涓紙闀滃儚鍙兘杩樻病鍚屾锛夈€?                    lastCode = 0
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
     * 鏈湴宸茶锛歚themeId 鈫?versionCode`銆?     *
     * 鏁呮剰**涓嶆槸** suspend锛氬畠浼氳 [cached] 杩欑被鍚屾璇荤紦瀛樼殑鍑芥暟璋冪敤锛岃€岄偅涓や釜鍑芥暟鐨?     * 濂戠害灏辨槸"璋冪敤鏂瑰凡缁忓湪 IO 绾跨▼涓?銆傞噷闈㈠啀濂椾竴灞?withContext 鍙細鐧藉姞涓€娆¤皟搴︺€?     */
    private fun installedMap(context: Context): Map<String, Int> = runCatching {
        ThemeRepository.list(context).associate { it.spec.id to it.spec.versionCode }
    }.getOrDefault(emptyMap())

    /** 鍙洿鏂板揩鐓ф椂闂存埑锛?04 涔嬪悗璁?UI 鐨?涓婃鏇存柊"璺熺潃璧帮級銆?*/
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

    /** 涓嬭浇 + 鏍￠獙 + 瑁呰繘鏈湴涓婚浠撳簱銆傚け璐ヤ竴寰嬫槸绫诲瀷鍖?[StoreFailure]銆?*/
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
                    me.weishu.kernelsu.ui.screen.remote.RemoteDownloader.downloadTo(url, tmp) { read, total ->
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
                    val entry = ThemeRepository.install(context, tmp.readBytes(), ThemeRepository.Origin.STORE)
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

    /** 鍙栨秷淇″彿锛堢敤寮傚父鎵撴柇闃诲涓嬭浇锛屾瘮杞鏂囦欢澶у皬鍙潬锛夈€?*/
    private class CancellationSignal : RuntimeException()

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")
}

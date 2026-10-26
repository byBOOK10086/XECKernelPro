package me.weishu.kernelsu.data.theme

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 索引解码器：JSON → [StoreCatalog]。
 *
 * 口径（和模块仓库那边一致，都是被真实事故教出来的）：
 *  * **三种形状都认**：v1 裸数组 `[…]`、v2 `{"themes":[…]}`、v3 带 `schema/banners/sections`
 *    的对象；`themes` 既可以是数组，也可以是 `{id: {…}}` 这种 map（运营手写时常这么写）。
 *  * **字段缺失一律取默认值**，不抛异常 —— 老管理器装新索引、新管理器装老索引都必须能用。
 *  * **结构错才失败**：整页不是 JSON、`themes` 不是容器、或者容器里一条都解析不出来。
 *  * 文案字段支持**本地化对象**：`"name": {"zh": "紫罗兰", "en": "Violet"}`；系统语言是中文
 *    就取 `zh`，否则取 `en`，都没有就取第一个字符串值。
 *
 * 解析完不做任何排序/过滤：那是 [StoreQuery] 的事，这样缓存里的顺序和索引一致。
 */
object StoreIndexCodec {

    /** 本管理器认识的最新 schema。更高的 schema 也照旧尽力解析（未知字段忽略）。 */
    const val SCHEMA_LATEST = 3

    private const val CHARSET_START_OBJECT = 0x7B.toChar()
    private const val CHARSET_START_ARRAY = 0x5B.toChar()

    /**
     * @param installed 本地已装：`themeId → versionCode`（用来标"已安装/可更新"）。
     * @throws StoreFailure.Parse 结构错到无法使用时抛出。
     */
    fun decode(body: String, installed: Map<String, Int> = emptyMap()): StoreCatalog {
        val trimmed = body.trimStart()
        if (trimmed.isEmpty()) throw StoreFailure.Parse("empty body")
        return when (trimmed.first()) {
            CHARSET_START_ARRAY -> {
                val array = runCatching { JSONArray(trimmed) }
                    .getOrElse { throw StoreFailure.Parse("not a json array") }
                StoreCatalog(schema = 1, themes = parseAll(array, installed))
            }

            CHARSET_START_OBJECT -> {
                val root = runCatching { JSONObject(trimmed) }
                    .getOrElse { throw StoreFailure.Parse("not a json object") }
                parseRoot(root, installed)
            }

            else -> throw StoreFailure.Parse("unknown payload")
        }
    }

    private fun parseRoot(root: JSONObject, installed: Map<String, Int>): StoreCatalog {
        val themes = when {
            root.has("themes") && !root.isNull("themes") -> parseThemesContainer(root, installed)
            // 有些索引直接把主题挂在顶层：{ "xecpro-violet": {...} }
            root.keys().asSequence().any { root.opt(it) is JSONObject } && !root.has("schema") ->
                parseThemeMap(root, installed)
            else -> throw StoreFailure.Parse("no themes container")
        }
        val banners = root.optJSONArray("banners").let { arr ->
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::parseBanner)
            }
        }
        val sections = root.optJSONArray("sections").let { arr ->
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::parseSection)
            }
        }
        return StoreCatalog(
            schema = root.optInt("schema", SCHEMA_LATEST),
            generatedAt = text(root, "generatedAt"),
            themes = themes,
            banners = banners,
            sections = sections,
        )
    }

    private fun parseThemesContainer(root: JSONObject, installed: Map<String, Int>): List<StoreTheme> {
        root.optJSONArray("themes")?.let { return parseAll(it, installed) }
        root.optJSONObject("themes")?.let { return parseThemeMap(it, installed) }
        throw StoreFailure.Parse("themes is not a container")
    }

    private fun parseAll(array: JSONArray, installed: Map<String, Int>): List<StoreTheme> {
        if (array.length() == 0) return emptyList()
        val out = ArrayList<StoreTheme>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseTheme(obj, installed)?.let { out += it }
        }
        // 有内容但一条都认不出来 = 索引格式变了，这是失败而不是"空商店"。
        if (out.isEmpty()) throw StoreFailure.Parse("${array.length()} entries, none valid")
        return out
    }

    private fun parseThemeMap(map: JSONObject, installed: Map<String, Int>): List<StoreTheme> {
        val out = ArrayList<StoreTheme>(map.length())
        for (key in map.keys()) {
            val obj = map.optJSONObject(key) ?: continue
            if (!obj.has("themeId") && !obj.has("id")) obj.put("themeId", key)
            parseTheme(obj, installed)?.let { out += it }
        }
        if (out.isEmpty() && map.length() > 0) throw StoreFailure.Parse("theme map has no valid entry")
        return out
    }

    /** 单条主题；`themeId` 为空视为坏数据（本地目录名要用它，不能放行）。 */
    fun parseTheme(obj: JSONObject, installed: Map<String, Int> = emptyMap()): StoreTheme? {
        val id = text(obj, "themeId").ifEmpty { text(obj, "id") }.trim()
        if (id.isEmpty()) return null
        val colors = obj.optJSONObject("colors")
        val previews = strings(obj.optJSONArray("previews"))
        val screenshots = strings(obj.optJSONArray("screenshots"))
        val types = strings(obj.optJSONArray("types"))
        val elements = strings(obj.optJSONArray("elements")).ifEmpty { deriveElements(types, colors) }
        val previewUrl = text(obj, "previewUrl").ifEmpty { text(obj, "preview") }
            .ifEmpty { previews.firstOrNull().orEmpty() }
            .ifEmpty { screenshots.firstOrNull().orEmpty() }
        val category = text(obj, "category").ifEmpty { deriveCategory(elements) }
        val versionCode = obj.optInt("versionCode", 0)
        val rating = obj.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it }
        return StoreTheme(
            themeId = id,
            name = text(obj, "name").ifEmpty { id },
            author = text(obj, "author"),
            version = text(obj, "version"),
            versionCode = versionCode,
            summary = text(obj, "summary").ifEmpty { text(obj, "description") },
            description = text(obj, "description"),
            size = obj.optLong("size", 0L).coerceAtLeast(0L),
            sha256 = text(obj, "sha256"),
            downloadUrl = text(obj, "downloadUrl").ifEmpty { text(obj, "download") },
            previewUrl = previewUrl,
            updatedAt = text(obj, "updatedAt").ifEmpty { text(obj, "createdAt") },
            accentHex = colors?.let { text(it, "accent") }.orEmpty(),
            backdropDarkHex = colors?.let { text(it, "backdropDark") }.orEmpty(),
            backdropLightHex = colors?.let { text(it, "backdropLight") }.orEmpty(),
            featured = obj.optBoolean("featured", false),
            types = types,
            previews = previews,
            installedVersionCode = installed[id] ?: 0,
            categoryRaw = category,
            elements = elements,
            tags = strings(obj.optJSONArray("tags")),
            screenshots = screenshots,
            downloads = obj.optLong("downloads", 0L).coerceAtLeast(0L),
            rating = rating.coerceIn(0.0, 5.0).toFloat(),
            ratingCount = obj.optInt("ratingCount", 0).coerceAtLeast(0),
            priceCents = obj.optInt("priceCents", 0).coerceAtLeast(0),
            changelog = text(obj, "changelog"),
            minManagerVersion = obj.optInt("minManagerVersion", 0).coerceAtLeast(0),
            publishedAt = text(obj, "publishedAt").ifEmpty { text(obj, "createdAt") },
            bannerUrl = text(obj, "bannerUrl"),
        )
    }

    private fun parseBanner(obj: JSONObject): StoreBanner? {
        val image = text(obj, "imageUrl").ifEmpty { text(obj, "image") }.ifEmpty { text(obj, "url") }
        val themeId = text(obj, "themeId").ifEmpty { text(obj, "theme") }
        if (image.isEmpty() && themeId.isEmpty()) return null
        return StoreBanner(
            themeId = themeId,
            imageUrl = image,
            title = text(obj, "title"),
            subtitle = text(obj, "subtitle"),
        )
    }

    private fun parseSection(obj: JSONObject): StoreSection? {
        val id = text(obj, "id").ifEmpty { text(obj, "sectionId") }.trim()
        if (id.isEmpty()) return null
        val ids = strings(obj.optJSONArray("themeIds")).ifEmpty { strings(obj.optJSONArray("themes")) }
        if (ids.isEmpty()) return null
        return StoreSection(
            id = id,
            title = text(obj, "title").ifEmpty { id },
            themeIds = ids,
        )
    }

    /** 老索引只有 `types`：推回元素列表，保证「元素」筛选不至于空着。 */
    private fun deriveElements(types: List<String>, colors: JSONObject?): List<String> {
        val out = LinkedHashSet<String>(3)
        if (types.contains(StoreTheme.TYPE_MODE_BADGE)) out += Element.MODE_BADGE
        if (types.contains(StoreTheme.TYPE_COLORS) || colors != null) out += Element.COLORS
        return out.toList()
    }

    private fun deriveCategory(elements: List<String>): String = when {
        elements.size >= 2 -> StoreTheme.CATEGORY_THEME
        elements.contains(Element.MODE_BADGE) -> StoreTheme.CATEGORY_BADGE
        elements.contains(Element.FONT) -> StoreTheme.CATEGORY_FONT
        elements.contains(Element.WALLPAPER) -> StoreTheme.CATEGORY_WALLPAPER
        else -> StoreTheme.CATEGORY_COLORS
    }

    /** 取一组字符串（丢掉空白项）。 */
    private fun strings(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            val s = when (value) {
                is String -> value
                is JSONObject -> pickLocalized(value)
                else -> ""
            }.trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /**
     * 取一个文案字段：支持纯字符串，也支持 `{"zh": "...", "en": "..."}`。
     *
     * 这台设备是中文环境 → 中文优先；否则英文优先。两种都没有时取第一个字符串值，
     * 保证运营手滑写了别语言也不会变成空标题。
     */
    private fun text(obj: JSONObject, key: String): String {
        val value = obj.opt(key) ?: return ""
        return when (value) {
            is String -> value.trim()
            is JSONObject -> pickLocalized(value)
            else -> ""
        }
    }

    private fun pickLocalized(obj: JSONObject): String {
        val chinese = Locale.getDefault().language == "zh"
        val order = if (chinese) ZH_KEYS else EN_KEYS
        for (key in order) {
            val v = obj.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.optString(keys.next(), "").trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private val ZH_KEYS = listOf("zh", "zh-CN", "zh_CN", "zh-rCN", "cn", "zh-Hans")
    private val EN_KEYS = listOf("en", "en-US", "en_US", "default")
}

package me.weishu.kernelsu.data.theme

import android.graphics.Color as AndroidColor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 主题包（`.xectheme`）的读写。
 *
 * 容器就是一个 zip：根目录放 `theme.json`，可选放背景图与预览图。
 *
 * ```
 * theme.json
 * background-dark.jpg
 * background-light.jpg
 * block-home-status.jpg
 * preview.jpg
 * ```
 *
 * 为什么用 zip 而不是纯 JSON：
 *  1. 主题要带背景图，JSON 里塞 base64 会让包膨胀 33%，还得自己定分块规则；
 *  2. zip 的条目名天然是"路径"，可以像模块仓库那样把危险条目（`..`、绝对路径、
 *     可执行文件）在**读之前**就拒掉；
 *  3. 传输/落盘/上传都在现有链路里（OpenList 上传、jsDelivr 分发、sha256 校验）。
 *
 * JSON 一律用 `org.json`（零反射），跟模块仓库同一套口径 —— R8 开着的项目里
 * kotlinx.serialization 的多态/动态查表有被裁掉的风险，主题包又是**用户提供的输入**，
 * 解析失败必须能被我们捕获成人话错误，而不是崩在 ClassNotFound 上。
 *
 * 未知字段一律忽略、缺失字段一律取默认值：主题包是跨版本分发的，
 * 老包在新管理器上要能装，新包在旧管理器上也不该炸。
 */
object ThemeCodec {

    const val SCHEMA = 1
    const val MANIFEST = "theme.json"
    /** 单包上限（与主题仓库脚本一致；解压后按条目名逐个限长，防 zip 炸弹）。 */
    const val MAX_PACKAGE_BYTES = 24 * 1024 * 1024
    const val MAX_ENTRIES = 24
    const val MAX_ASSET_BYTES = 12 * 1024 * 1024

    private val ID_RE = Regex("^[a-zA-Z][a-zA-Z0-9._-]{1,63}$")
    private val HEX_RE = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    private val FORBIDDEN_SUFFIX = listOf(".sh", ".so", ".apk", ".dex", ".jar", ".bin", ".elf")

    /** `when` 的三个取值。字符串而不是枚举：未知取值要能被安静地忽略掉。 */
    const val BLOCK_BOTH = "both"
    const val BLOCK_DARK = "dark"
    const val BLOCK_LIGHT = "light"

    /**
     * 区块背景图在包里的条目名。
     *
     * 用 id 派生名字（而不是让主题作者自己起名）是为了让**包内声明与包内文件天然一致**：
     * `theme.json` 里写 `"image": "block-home-status.jpg"`，zip 里就必须有这个条目，
     * 安装时也写到主题目录里的同一个名字 —— 少一处手写映射，就少一处对不上。
     */
    fun blockAssetName(blockId: String): String = "block-" + blockId.replace('.', '-') + ".jpg"

    class InvalidTheme(message: String) : Exception(message)

    /**
     * 一个首页卡片的背景。
     *
     * 三样东西都是可选的，但至少要有一样（解析时两条都空就整条丢掉）：
     *  * [color] `#AARRGGBB`（**已归一**，跟颜色令牌同一档写法）；
     *  * [image] 包内条目名（= [blockAssetName]，安装后是主题目录里的文件名）；
     *  * [opacity] / [dim] / [mode] 只影响渲染，不影响"这条有没有内容"。
     *
     * 全部字段都带默认值：主题作者只写 `"color"` 也是合法的。
     */
    data class BlockSpec(
        val color: String? = null,
        val image: String? = null,
        /** 整体不透明度 0.0~1.0，默认 1.0。 */
        val opacity: Float = 1f,
        /** 压在图上的一层黑纱 0.0~1.0，默认 0.0（只调图，不用改图本身）。 */
        val dim: Float = 0f,
        /** 明暗档：见 [BLOCK_BOTH] / [BLOCK_DARK] / [BLOCK_LIGHT]。 */
        val mode: String = BLOCK_BOTH,
    )

    /** 一个主题：元数据 + 令牌覆盖 + 资源条目名（图片本体在 [ThemeAssets] 里）。 */
    data class Spec(
        val id: String,
        val name: String,
        val author: String = "",
        val version: String = "1.0",
        val versionCode: Int = 1,
        val summary: String = "",
        val description: String = "",
        val createdAt: String = "",
        /** 令牌名 -> `#RRGGBB` / `#AARRGGBB`；只写想改的，其余沿用默认。 */
        val dark: Map<String, String> = emptyMap(),
        val light: Map<String, String> = emptyMap(),
        /** 形状覆盖：sm/md/lg/xl/bar/pressRadius（dp）。 */
        val shapes: Map<String, Int> = emptyMap(),
        /** 玻璃参数：blur / refraction / tintAlpha / rimAlpha / specular（倍率或绝对值，见 XcGlass）。 */
        val glass: Map<String, Float> = emptyMap(),
        /** 模块页霓虹板（可选）：暗/亮两档的 25 个槽位，缺的沿用默认。 */
        val neonDark: Map<String, String> = emptyMap(),
        val neonLight: Map<String, String> = emptyMap(),
        val backgroundDark: String? = null,
        val backgroundLight: String? = null,
        /**
         * 卡片（区块）背景：区块 id -> 背景定义，只写用户真的动过的那些。
         *
         * 键空间与设计令牌刻意分开：区块是"首页某张卡片"这种**位置**语义，不是颜色语义，
         * 而且它同时带图、透明度与明暗档，塞不进任何一张既有的"名字 -> 字符串"表里。
         * 认不出的区块 id 一律保留但不渲染（前向兼容：新版本加区块，旧版本安静忽略）。
         */
        val blocks: Map<String, BlockSpec> = emptyMap(),
    ) {
        /** 商店卡片上的缩略色块用。 */
        val accentHex: String get() = dark["accent"] ?: light["accent"] ?: ""
        val backdropDarkHex: String get() = dark["backdrop"] ?: ""
        val backdropLightHex: String get() = light["backdrop"] ?: ""
    }

    /** 包里的图片资源（条目名 -> 字节）。 */
    data class Assets(
        val files: Map<String, ByteArray> = emptyMap(),
    ) {
        fun backgroundDark(): ByteArray? = files.entries
            .firstOrNull { it.key.equals("background-dark.jpg", true) || it.key.equals("background-dark.png", true) }
            ?.value

        fun backgroundLight(): ByteArray? = files.entries
            .firstOrNull { it.key.equals("background-light.jpg", true) || it.key.equals("background-light.png", true) }
            ?.value

        fun preview(): ByteArray? = files.entries
            .firstOrNull { it.key.lowercase().startsWith("preview.") }?.value

        /** 按 spec 里声明的名字取图（主题编辑器导出的包就是这种形态）。 */
        fun byName(name: String?): ByteArray? {
            if (name.isNullOrEmpty()) return null
            return files[name] ?: files.entries.firstOrNull { it.key.equals(name, true) }?.value
        }

        /**
         * 取某个区块的图。
         *
         * 认两种写法：spec 里声明的 `image`（主题作者的写法）与**约定名**
         * [blockAssetName]（由区块 id 推出，我们编辑器导出的写法）。后者是必须的：编辑器保存时
         * 把图写进主题目录就算完事，不保证 spec 已经改过 `image`；只认声明名会让
         * "刚选的图"在保存那一刻丢掉。
         */
        fun byBlock(blockId: String, declared: String?): ByteArray? =
            byName(declared) ?: byName(blockAssetName(blockId))
    }

    data class Package(val spec: Spec, val assets: Assets)

    // ---------------------------------------------------------------- 解析

    /** 解析一个 `.xectheme`。任何不合规都抛 [InvalidTheme]，`message` 直接能给人看。 */
    fun read(bytes: ByteArray): Package {
        if (bytes.isEmpty()) throw InvalidTheme("主题包是空的")
        if (bytes.size > MAX_PACKAGE_BYTES) {
            throw InvalidTheme("主题包太大：${bytes.size / 1024 / 1024} MB（上限 24 MB）")
        }
        val files = LinkedHashMap<String, ByteArray>()
        var manifest: String? = null
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count += 1
                if (count > MAX_ENTRIES) throw InvalidTheme("主题包条目太多（上限 $MAX_ENTRIES）")
                val name = entry.name
                if (name.startsWith("/") || name.contains("..") || name.contains('\\')) {
                    throw InvalidTheme("主题包里有非法条目名：$name")
                }
                val lower = name.lowercase()
                if (FORBIDDEN_SUFFIX.any { lower.endsWith(it) }) {
                    throw InvalidTheme("主题包里不允许放可执行文件：$name")
                }
                val data = zip.readBytesCapped(MAX_ASSET_BYTES)
                if (name == MANIFEST) {
                    manifest = data.toString(Charsets.UTF_8)
                } else {
                    files[name] = data
                }
            }
        }
        val text = manifest ?: throw InvalidTheme("主题包里没有 $MANIFEST")
        return Package(parseSpec(text), Assets(files))
    }

    private fun ZipInputStream.readBytesCapped(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw InvalidTheme("主题包里有单个条目超过 ${limit / 1024 / 1024} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun parseSpec(text: String): Spec {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw InvalidTheme("theme.json 不是合法 JSON：${e.message}")
        }
        if (json.optInt("schema", 0) != SCHEMA) {
            throw InvalidTheme("主题包的 schema 不是 $SCHEMA（这个包可能是更新版本的管理器导出的）")
        }
        val id = json.optString("id", "").trim()
        if (!ID_RE.matches(id)) {
            throw InvalidTheme("主题 id 不合法：\"$id\"（要求字母开头，只含字母数字 . _ -，2~64 位）")
        }
        val name = json.optString("name", "").trim()
        if (name.isEmpty()) throw InvalidTheme("主题缺少 name")
        val version = json.optString("version", "").trim().ifEmpty { "1.0" }
        val versionCode = json.optInt("versionCode", 0).takeIf { it > 0 }
            ?: throw InvalidTheme("主题缺少合法的 versionCode")

        val colors = json.optJSONObject("colors")
        val dark = colorMap(colors?.optJSONObject("dark"), "colors.dark")
        val light = colorMap(colors?.optJSONObject("light"), "colors.light")

        val shapes = IntMap()
        json.optJSONObject("shapes")?.let { obj ->
            obj.keys().forEach { key ->
                val value = obj.optInt(key, -1)
                if (value in 0..200) shapes[key] = value
            }
        }
        val glass = FloatMap()
        json.optJSONObject("glass")?.let { obj ->
            obj.keys().forEach { key ->
                val value = obj.optDouble(key, -1.0)
                if (value in 0.0..8.0) glass[key] = value.toFloat()
            }
        }
        val neon = json.optJSONObject("neon")
        val neonDark = colorMap(neon?.optJSONObject("dark"), "neon.dark")
        val neonLight = colorMap(neon?.optJSONObject("light"), "neon.light")

        val bg = json.optJSONObject("background")
        val backgroundDark = bg?.optString("dark", "")?.takeIf { it.isNotEmpty() }
        val backgroundLight = bg?.optString("light", "")?.takeIf { it.isNotEmpty() }

        val blocks = blockMap(json.optJSONObject("blocks"))

        return Spec(
            id = id,
            name = name,
            author = json.optString("author", "").trim(),
            version = version,
            versionCode = versionCode,
            summary = json.optString("summary", "").trim(),
            description = json.optString("description", "").trim(),
            createdAt = json.optString("createdAt", "").trim(),
            dark = dark,
            light = light,
            shapes = shapes,
            glass = glass,
            neonDark = neonDark,
            neonLight = neonLight,
            backgroundDark = backgroundDark,
            backgroundLight = backgroundLight,
            blocks = blocks,
        )
    }

    /**
     * 解析 `blocks` 对象。
     *
     * 宽容度与格式的其它部分一致，但有一条**比别处更宽**：认不出的区块 id、认不出的字段、
     * 甚至名字不像区块 id 的键，全都安静丢掉而不是抛 [InvalidTheme]。理由是区块是"往界面上
     * 贴东西"，一个新版主题带 12 个区块、老管理器只认识 6 个时，老管理器必须能装能用，
     * 而不是报"主题包不合法"。
     */
    private fun blockMap(obj: JSONObject?): Map<String, BlockSpec> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, BlockSpec>()
        obj.keys().forEach { id ->
            val key = id.trim()
            if (!ID_RE.matches(key)) return@forEach
            val item = obj.optJSONObject(id) ?: return@forEach
            val color = normalizeHex(item.optString("color", ""))
            val image = item.optString("image", "").trim().takeIf { it.isNotEmpty() }
            // 既没颜色也没图 = 这条什么都没说，留着只会让编辑器多出一行空设置。
            if (color == null && image == null) return@forEach
            val opacity = item.optDouble("opacity", 1.0).takeIf { it.isFinite() }?.toFloat()
            val dim = item.optDouble("dim", 0.0).takeIf { it.isFinite() }?.toFloat()
            val mode = item.optString("when", BLOCK_BOTH).trim().lowercase()
            out[key] = BlockSpec(
                color = color,
                image = image,
                opacity = (opacity ?: 1f).coerceIn(0f, 1f),
                dim = (dim ?: 0f).coerceIn(0f, 1f),
                mode = if (mode == BLOCK_DARK || mode == BLOCK_LIGHT) mode else BLOCK_BOTH,
            )
        }
        return out
    }

    /** 单条颜色的归一：`#RRGGBB` / `#AARRGGBB` -> `#AARRGGBB`；不合格返回 null（不抛）。 */
    private fun normalizeHex(value: String): String? {
        val text = value.trim()
        if (!HEX_RE.matches(text)) return null
        return if (text.length == 7) "#FF" + text.substring(1) else text.uppercase()
    }

    private fun colorMap(obj: JSONObject?, where: String): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.keys().forEach { key ->
            val value = obj.optString(key, "").trim()
            if (value.isEmpty()) return@forEach
            // 统一成 #AARRGGBB 便于后面直接解析；不够 8 位就补满不透明的 FF。
            out[key] = normalizeHex(value)
                ?: throw InvalidTheme("$where.$key 不是 #RRGGBB / #AARRGGBB：\"$value\"")
        }
        return out
    }

    // ---------------------------------------------------------------- 序列化

    fun toJson(spec: Spec): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", spec.id)
        put("name", spec.name)
        put("author", spec.author)
        put("version", spec.version)
        put("versionCode", spec.versionCode)
        put("summary", spec.summary)
        put("description", spec.description)
        if (spec.createdAt.isNotEmpty()) put("createdAt", spec.createdAt)
        put("colors", JSONObject().apply {
            put("dark", JSONObject(spec.dark.toMap()))
            put("light", JSONObject(spec.light.toMap()))
        })
        if (spec.shapes.isNotEmpty()) put("shapes", JSONObject(spec.shapes.toMap()))
        if (spec.glass.isNotEmpty()) put("glass", JSONObject(spec.glass.toMap()))
        if (spec.neonDark.isNotEmpty() || spec.neonLight.isNotEmpty()) {
            put("neon", JSONObject().apply {
                put("dark", JSONObject(spec.neonDark.toMap()))
                put("light", JSONObject(spec.neonLight.toMap()))
            })
        }
        if (spec.backgroundDark != null || spec.backgroundLight != null) {
            put("background", JSONObject().apply {
                spec.backgroundDark?.let { put("dark", it) }
                spec.backgroundLight?.let { put("light", it) }
            })
        }
        // 只写用户动过的区块；连"既没颜色也没图"的空条目都不写进去（写了等于给主题
        // 加了一条什么都不做的配置，读的人只会以为是 bug）。
        val painted = spec.blocks.filterValues { it.color != null || it.image != null }
        if (painted.isNotEmpty()) {
            put("blocks", JSONObject().apply {
                painted.forEach { (id, block) ->
                    put(id, JSONObject().apply {
                        block.color?.let { put("color", it) }
                        block.image?.let { put("image", it) }
                        put("opacity", block.opacity.toDouble())
                        put("dim", block.dim.toDouble())
                        put("when", block.mode)
                    })
                }
            })
        }
    }.toString(2)

    /** 打一个 `.xectheme` 出来：`theme.json` + 声明的背景图 + 可选预览图。 */
    fun write(spec: Spec, backgrounds: Map<String, ByteArray>, preview: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(toJson(spec).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, data) in backgrounds) {
                if (data.isEmpty() || data.size > MAX_ASSET_BYTES) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            if (preview != null && preview.isNotEmpty() && preview.size <= 2 * 1024 * 1024) {
                zip.putNextEntry(ZipEntry("preview.jpg"))
                zip.write(preview)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 颜色

    /** `#AARRGGBB` -> Compose/Android 的 ARGB int；解析不了返回 null。 */
    fun parseColor(hex: String?): Int? {
        if (hex.isNullOrEmpty()) return null
        val text = if (hex.startsWith("#")) hex.substring(1) else hex
        val normalized = if (text.length == 6) "FF$text" else text
        if (normalized.length != 8) return null
        return try {
            AndroidColor.parseColor("#$normalized")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun IntMap() = LinkedHashMap<String, Int>()
    private fun FloatMap() = LinkedHashMap<String, Float>()
}

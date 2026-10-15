package me.weishu.kernelsu.data.theme

import android.content.Context
import androidx.core.content.edit
import me.weishu.kernelsu.data.theme.ThemeCodec.Spec
import java.io.File

/**
 * 本地主题仓库：主题包落盘、当前主题、导入导出。
 *
 * 布局（全部在 app 私有目录里，不需要任何存储权限）：
 *
 * ```
 * filesDir/themes/<id>/theme.json     解析后的 spec（真源，改它 = 改主题）
 * filesDir/themes/<id>/background-dark.jpg
 * filesDir/themes/<id>/background-light.jpg
 * filesDir/themes/<id>/preview.jpg
 * ```
 *
 * 当前主题记住 SharedPreferences 的 `theme_id`（复用 `settings` 这份，跟其它外观设置在一起），
 * **空串 = 恢复默认**。MainActivityViewModel 监听这个键，所以切换主题会让整棵 UI 重组一次。
 *
 * 背景图不另造机制：应用主题时把图交给 [me.weishu.kernelsu.ui.util.WallpaperStore]，
 * 并写 `wallpaper_light` / `wallpaper_dark`；应用前先把用户原来的那两个值存进
 * `theme_prev_wallpaper_*`，这样「恢复默认」能把用户自己选的背景还回去（绝不能调
 * WallpaperStore.clearAll()，那会把用户手选的背景一起删掉）。
 */
object ThemeRepository {

    const val PREFS = "settings"
    const val KEY_ACTIVE = "theme_id"
    const val KEY_PREV_WALLPAPER_LIGHT = "theme_prev_wallpaper_light"
    const val KEY_PREV_WALLPAPER_DARK = "theme_prev_wallpaper_dark"
    const val KEY_PREV_WALLPAPER_SAVED = "theme_prev_wallpaper_saved"

    enum class Origin { LOCAL, STORE, IMPORTED }

    data class Entry(
        val spec: Spec,
        val dir: File,
        val origin: Origin,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun themesDir(context: Context): File = File(context.filesDir, "themes")

    fun themeDir(context: Context, id: String): File = File(themesDir(context), id)

    /** 当前生效的主题 id；null = 默认外观。 */
    fun activeId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, "")?.takeIf { it.isNotEmpty() }

    fun setActiveId(context: Context, id: String?) {
        prefs(context).edit { putString(KEY_ACTIVE, id.orEmpty()) }
    }

    /** 扫描本地主题（跳过坏包：一个坏目录不该让整个列表打不开）。 */
    fun list(context: Context): List<Entry> {
        val dir = themesDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
            .sortedBy { it.spec.name.lowercase() }
    }

    fun find(context: Context, id: String): Entry? = runCatching { load(themeDir(context, id)) }.getOrNull()

    private fun load(dir: File): Entry {
        val manifest = File(dir, ThemeCodec.MANIFEST)
        if (!manifest.isFile) throw ThemeCodec.InvalidTheme("${dir.name} 里没有 ${ThemeCodec.MANIFEST}")
        val spec = ThemeCodec.parseSpec(manifest.readText(Charsets.UTF_8))
        val origin = when {
            File(dir, ".store").isFile -> Origin.STORE
            File(dir, ".imported").isFile -> Origin.IMPORTED
            else -> Origin.LOCAL
        }
        return Entry(spec, dir, origin)
    }

    /** 把一个 `.xectheme` 装进本地仓库；返回落地后的条目。 */
    fun install(context: Context, bytes: ByteArray, origin: Origin): Entry {
        val pkg = ThemeCodec.read(bytes)
        return install(context, pkg.spec, pkg.assets, origin)
    }

    fun install(
        context: Context,
        spec: Spec,
        assets: ThemeCodec.Assets = ThemeCodec.Assets(),
        origin: Origin = Origin.LOCAL,
    ): Entry {
        val dir = themeDir(context, spec.id)
        // 先写临时目录再整体替换：中途失败不会留下半个主题（旧的那份还在）。
        val staging = File(themesDir(context), ".staging-${spec.id}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw ThemeCodec.InvalidTheme("无法创建 ${staging.absolutePath}")
        try {
            File(staging, ThemeCodec.MANIFEST).writeText(ThemeCodec.toJson(spec), Charsets.UTF_8)
            val darkName = writeAsset(staging, "background-dark.jpg", assets.byName(spec.backgroundDark))
            val lightName = writeAsset(staging, "background-light.jpg", assets.byName(spec.backgroundLight))
            writeAsset(staging, "preview.jpg", assets.preview())
            val normalized = spec.copy(
                backgroundDark = darkName ?: spec.backgroundDark?.takeIf { assets.byName(it) != null },
                backgroundLight = lightName ?: spec.backgroundLight?.takeIf { assets.byName(it) != null },
            )
            File(staging, ThemeCodec.MANIFEST).writeText(ThemeCodec.toJson(normalized), Charsets.UTF_8)
            when (origin) {
                Origin.STORE -> File(staging, ".store").writeText("1")
                Origin.IMPORTED -> File(staging, ".imported").writeText("1")
                Origin.LOCAL -> Unit
            }
            if (dir.exists() && !dir.deleteRecursively()) {
                throw ThemeCodec.InvalidTheme("无法替换已存在的主题目录 ${dir.absolutePath}")
            }
            if (!staging.renameTo(dir)) {
                staging.copyRecursively(dir, overwrite = true)
                staging.deleteRecursively()
            }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return load(dir)
    }

    private fun writeAsset(dir: File, name: String, data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        if (data.size > ThemeCodec.MAX_ASSET_BYTES) return null
        File(dir, name).writeBytes(data)
        return name
    }

    fun remove(context: Context, id: String): Boolean {
        if (activeId(context) == id) setActiveId(context, null)
        val dir = themeDir(context, id)
        if (!dir.exists()) return false
        // 只删自己的目录，绝不碰 WallpaperStore（用户可能把主题背景设成了当前壁纸 —— 那一份
        // 已经在 wallpapers/ 里复制过，删主题不该顺手把壁纸删掉）。
        return dir.deleteRecursively()
    }

    /** 导出成 `.xectheme`（含背景图与预览图）。 */
    fun export(context: Context, id: String): ByteArray {
        val entry = find(context, id) ?: throw ThemeCodec.InvalidTheme("主题 $id 不在本地")
        val backgrounds = LinkedHashMap<String, ByteArray>()
        val spec = entry.spec
        spec.backgroundDark?.let { name ->
            File(entry.dir, name).takeIf { it.isFile }?.let { backgrounds[name] = it.readBytes() }
        }
        spec.backgroundLight?.let { name ->
            File(entry.dir, name).takeIf { it.isFile }?.let { backgrounds[name] = it.readBytes() }
        }
        val preview = File(entry.dir, "preview.jpg").takeIf { it.isFile }?.readBytes()
        return ThemeCodec.write(spec, backgrounds, preview)
    }

    fun backgroundDark(context: Context, id: String): File? =
        find(context, id)?.let { entry -> entry.spec.backgroundDark?.let { File(entry.dir, it) } }

    fun backgroundLight(context: Context, id: String): File? =
        find(context, id)?.let { entry -> entry.spec.backgroundLight?.let { File(entry.dir, it) } }

    fun previewFile(context: Context, id: String): File? =
        find(context, id)?.let { File(it.dir, "preview.jpg").takeIf { f -> f.isFile } }

    // ------------------------------------------------------------ 背景图接力

    /** 应用主题前记住用户自己的背景，供「恢复默认」还原。 */
    fun rememberUserWallpaper(context: Context, light: String?, dark: String?) {
        val p = prefs(context)
        if (p.getBoolean(KEY_PREV_WALLPAPER_SAVED, false)) return
        p.edit {
            putString(KEY_PREV_WALLPAPER_LIGHT, light.orEmpty())
            putString(KEY_PREV_WALLPAPER_DARK, dark.orEmpty())
            putBoolean(KEY_PREV_WALLPAPER_SAVED, true)
        }
    }

    /** 取出（并消费）之前记住的用户背景。 */
    fun takeUserWallpaper(context: Context): Pair<String?, String?>? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_PREV_WALLPAPER_SAVED, false)) return null
        val light = p.getString(KEY_PREV_WALLPAPER_LIGHT, "").orEmpty().takeIf { it.isNotEmpty() }
        val dark = p.getString(KEY_PREV_WALLPAPER_DARK, "").orEmpty().takeIf { it.isNotEmpty() }
        p.edit {
            remove(KEY_PREV_WALLPAPER_LIGHT)
            remove(KEY_PREV_WALLPAPER_DARK)
            putBoolean(KEY_PREV_WALLPAPER_SAVED, false)
        }
        return light to dark
    }
}

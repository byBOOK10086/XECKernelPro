package me.weishu.kernelsu.data.theme

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.screen.home.ModeBadgeStyle
import me.weishu.kernelsu.ui.screen.home.ModeBadgeStyleStore
import me.weishu.kernelsu.ui.util.WallpaperStore
import org.json.JSONObject
import java.io.File

/**
 * 应用 / 恢复主题。
 *
 * 应用一套主题做三件事：
 *  1. 记下 `theme_id`（`MainActivityViewModel` 监听它 → 整棵 UI 用新令牌重组一次）；
 *  2. 如果主题带背景图，把图交给 [WallpaperStore] 落盘并写 `wallpaper_light/dark`。
 *     背景走的是**现成**那条链路（根层壁纸就是全应用玻璃的折射源），所以不新增任何机制；
 *  3. schema 2 的主题可以带**模式牌元素**（root 方式显示的前后缀/底图/字体/颜色）：
 *     资产就在主题目录里，直接以文件路径喂给 [ModeBadgeStyleStore]——零新管线。
 *     关键词（LKM/GKI 等）不在包里，也不在这条链路上。
 *
 * 两个必须守住的细节（都是审计里点出来的坑）：
 *  * **槽位前缀带主题 id**：`WallpaperStore.prune()` 是按 `"$slot-"` 前缀删旧文件的，
 *    如果主题直接用 `light`/`dark` 当槽位，应用主题会把用户自己选的那张背景删掉；
 *  * **「恢复默认」要把用户原来的东西还回去**：应用前先把当时的 `wallpaper_light/dark`
 *    与用户自己的模式牌样式存起来，恢复时原样写回，并且只删主题自己铺出去的那两个文件
 *    （绝不 `clearAll()`）。
 */
object ThemeApplier {

    private const val KEY_PREV_MODE_BADGE = "theme_prev_mode_badge"
    private const val KEY_PREV_MODE_BADGE_SAVED = "theme_prev_mode_badge_saved"

    private fun slotFor(themeId: String, light: Boolean): String =
        "theme-$themeId-${if (light) WallpaperStore.LIGHT else WallpaperStore.DARK}"

    suspend fun apply(
        context: Context,
        id: String,
        settings: SettingsRepository = SettingsRepositoryImpl(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entry = ThemeRepository.find(context, id)
                ?: throw ThemeCodec.InvalidTheme("主题 $id 不在本地")
            // 只在"当前没有主题"时记一次用户背景，避免主题 A → 主题 B 时把 A 的背景当成用户的。
            if (ThemeRepository.activeId(context) == null) {
                ThemeRepository.rememberUserWallpaper(context, settings.wallpaperLight, settings.wallpaperDark)
            }
            ThemeRepository.backgroundDark(context, id)?.let { file ->
                settings.wallpaperDark = WallpaperStore.importFile(context, file, slotFor(id, light = false))
            }
            ThemeRepository.backgroundLight(context, id)?.let { file ->
                settings.wallpaperLight = WallpaperStore.importFile(context, file, slotFor(id, light = true))
            }
            // 换主题 = 先回收上一套的模式牌影响（回到用户自己的样式），再铺新的。
            // 新主题不带模式牌时，这一步就是全部——上一套的牌不会残留。
            revertAppliedModeBadge(context)
            entry.spec.modeBadge?.let { applyModeBadge(context, entry, it) }
            ThemeRepository.setActiveId(context, id)
        }
    }

    suspend fun restoreDefault(
        context: Context,
        settings: SettingsRepository = SettingsRepositoryImpl(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val previous = ThemeRepository.activeId(context)
            ThemeRepository.setActiveId(context, null)
            val remembered = ThemeRepository.takeUserWallpaper(context)
            if (remembered != null) {
                settings.wallpaperLight = remembered.first.orEmpty()
                settings.wallpaperDark = remembered.second.orEmpty()
            }
            restoreDefaultBadgeRevert(context)
            // 只清掉上一套主题铺出去的那两张（文件名前缀唯一），用户自己的背景不动。
            if (previous != null) {
                WallpaperStore.discardSlot(context, slotFor(previous, light = true))
                WallpaperStore.discardSlot(context, slotFor(previous, light = false))
            }
        }
    }

    // ---- 模式牌元素（schema 2） ------------------------------------------------

    /**
     * 回收"主题铺出去"的模式牌影响：有记忆的用户样式就还回去，没有就回到全默认。
     *
     * 三个调用方共用这一条路径：恢复默认（[restoreDefault]）、换到不带模式牌元素的主题
     * （[apply] 的前置步骤）、卸载正在生效的主题（[ThemeRepository.remove]）。
     * 幂等：没有"已记忆"标记时是空操作。
     */
    fun revertAppliedModeBadge(context: Context) {
        val prefs = context.getSharedPreferences(ThemeRepository.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PREV_MODE_BADGE_SAVED, false)) return
        val saved = prefs.getString(KEY_PREV_MODE_BADGE, null)?.let { modeBadgeFromJson(it) }
        prefs.edit()
            .putBoolean(KEY_PREV_MODE_BADGE_SAVED, false)
            .remove(KEY_PREV_MODE_BADGE)
            .apply()
        ModeBadgeStyleStore.save(context, saved ?: ModeBadgeStyle())
    }

    private fun restoreDefaultBadgeRevert(context: Context) = revertAppliedModeBadge(context)

    private fun applyModeBadge(
        context: Context,
        entry: ThemeRepository.Entry,
        mb: ThemeCodec.ModeBadgeSpec,
    ) {
        val prefs = context.getSharedPreferences(ThemeRepository.PREFS, Context.MODE_PRIVATE)
        // 第一次把带模式牌的主题铺上时，记下用户自己的样式；恢复默认时原样还回去。
        if (!prefs.getBoolean(KEY_PREV_MODE_BADGE_SAVED, false)) {
            prefs.edit()
                .putString(KEY_PREV_MODE_BADGE, modeBadgeToJson(ModeBadgeStyleStore.load(context)))
                .putBoolean(KEY_PREV_MODE_BADGE_SAVED, true)
                .apply()
        }
        // 底图/字体资产就住在主题目录里（ThemeRepository 落盘的形态），直接引用路径。
        val bgUri = mb.image?.let { name ->
            File(entry.dir, name).takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
        }
        val fontPath = mb.font?.let { name ->
            File(entry.dir, name).takeIf { it.isFile }?.path
        }
        ModeBadgeStyleStore.save(
            context,
            ModeBadgeStyle(
                prefix = mb.prefix,
                suffix = mb.suffix,
                bgUri = bgUri,
                fontPath = fontPath,
                textColor = mb.textColor?.let { ThemeCodec.parseColor(it) },
                dim = mb.dim,
                blur = mb.blur,
            ),
        )
    }

    private fun modeBadgeToJson(style: ModeBadgeStyle): String = JSONObject().apply {
        put("prefix", style.prefix)
        put("suffix", style.suffix)
        style.bgUri?.let { put("bgUri", it) }
        style.fontPath?.let { put("fontPath", it) }
        style.textColor?.let { put("textColor", it) }
    }.toString()

    private fun modeBadgeFromJson(text: String): ModeBadgeStyle? = runCatching {
        val o = JSONObject(text)
        ModeBadgeStyle(
            prefix = o.optString("prefix", ""),
            suffix = o.optString("suffix", ""),
            bgUri = o.optString("bgUri", "").takeIf { it.isNotEmpty() },
            fontPath = o.optString("fontPath", "").takeIf { it.isNotEmpty() },
            textColor = if (o.has("textColor")) o.getInt("textColor") else null,
        )
    }.getOrNull()
}

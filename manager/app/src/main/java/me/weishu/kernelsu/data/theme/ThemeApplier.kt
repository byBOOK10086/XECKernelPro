package me.weishu.kernelsu.data.theme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.util.WallpaperStore

/**
 * 应用 / 恢复主题。
 *
 * 应用一套主题做两件事：
 *  1. 记下 `theme_id`（`MainActivityViewModel` 监听它 → 整棵 UI 用新令牌重组一次）；
 *  2. 如果主题带背景图，把图交给 [WallpaperStore] 落盘并写 `wallpaper_light/dark`。
 *     背景走的是**现成**那条链路（根层壁纸就是全应用玻璃的折射源），所以不新增任何机制。
 *
 * 两个必须守住的细节（都是审计里点出来的坑）：
 *  * **槽位前缀带主题 id**：`WallpaperStore.prune()` 是按 `"$slot-"` 前缀删旧文件的，
 *    如果主题直接用 `light`/`dark` 当槽位，应用主题会把用户自己选的那张背景删掉；
 *  * **「恢复默认」要把用户原来的背景还回去**：应用前先把当时的 `wallpaper_light/dark`
 *    存起来，恢复时原样写回，并且只删主题自己铺出去的那两个文件（绝不 `clearAll()`）。
 */
object ThemeApplier {

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
            // 只清掉上一套主题铺出去的那两张（文件名前缀唯一），用户自己的背景不动。
            if (previous != null) {
                WallpaperStore.discardSlot(context, slotFor(previous, light = true))
                WallpaperStore.discardSlot(context, slotFor(previous, light = false))
            }
        }
    }
}

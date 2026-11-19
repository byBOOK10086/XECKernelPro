package me.weishu.kernelsu.data.theme

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageDecoder
import android.os.Looper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.ui.screen.home.ModeBadgeStyle
import me.weishu.kernelsu.ui.screen.home.ModeBadgeStyleStore
import me.weishu.kernelsu.ui.util.WallpaperStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HostImageDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSwitchTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(ThemeRepository.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("wallpaper_light", WallpaperStore.SOLID_WHITE)
            .putString("wallpaper_dark", WallpaperStore.SOLID_BLACK).commit()
        ThemeResolver.invalidate()
    }

    private fun wallpapers() = prefs.getString("wallpaper_light", "") to
        prefs.getString("wallpaper_dark", "")

    private fun png(color: Int): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) image.setRGB(x, y, color)
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "png", out))
            out.toByteArray()
        }
    }

    private suspend fun install(id: String, light: Boolean, dark: Boolean, version: Int = 1) {
        val spec = ThemeCodec.Spec(id = id, name = id, versionCode = version,
            backgroundLight = if (light) "light.png" else null,
            backgroundDark = if (dark) "dark.png" else null)
        ThemeRepository.install(context, spec, ThemeCodec.Assets(mapOf(
            "light.png" to png(if (version == 1) 0xff0000 else 0x00ff00),
            "dark.png" to png(0x0000ff))))
    }

    @Test fun missingVariantsRestoreTheUserBackgroundAndDefaultRestoresBoth() = runBlocking {
        install("theme-a", true, true)
        install("theme-b", false, true)
        install("theme-c", true, false)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        val old = wallpapers()
        ThemeApplier.apply(context, "theme-b").getOrThrow()
        assertEquals(WallpaperStore.SOLID_WHITE, wallpapers().first)
        assertNotEquals(old.second, wallpapers().second)
        ThemeApplier.apply(context, "theme-c").getOrThrow()
        assertEquals(WallpaperStore.SOLID_BLACK, wallpapers().second)
        ThemeApplier.restoreDefault(context).getOrThrow()
        assertEquals(WallpaperStore.SOLID_WHITE to WallpaperStore.SOLID_BLACK, wallpapers())
        assertNull(ThemeRepository.rememberedUserWallpaper(context))
    }

    @Test fun badSecondImageDoesNotPartiallyApplyOrDeleteTheCurrentWallpaper() = runBlocking {
        install("theme-a", true, true)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        val before = prefs.all.toMap()
        val files = WallpaperStore.dir(context).list()!!.toSet()
        val current = ThemeRepository.find(context, "theme-a")!!
        File(current.dir, current.spec.backgroundDark!!).writeText("broken image")
        assertTrue(ThemeApplier.apply(context, "theme-a").isFailure)
        assertEquals(before, prefs.all)
        assertEquals(files, WallpaperStore.dir(context).list()!!.toSet())
        wallpapers().toList().forEach { assertTrue(WallpaperStore.exists(context, it!!)) }
    }

    @Test fun removingActiveThemeRestoresTheOriginalAppearance() = runBlocking {
        install("theme-a", true, true)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        assertTrue(ThemeRepository.remove(context, "theme-a"))
        assertNull(ThemeRepository.activeId(context))
        assertNull(ThemeResolver.active(context))
        assertEquals(WallpaperStore.SOLID_WHITE to WallpaperStore.SOLID_BLACK, wallpapers())
        assertTrue(WallpaperStore.dir(context).list()!!.isEmpty())
    }

    @Test fun updatingTheActiveIdRefreshesResourcesAndResolvedTokens() = runBlocking {
        install("theme-a", true, true)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        val previousWallpaper = wallpapers().first
        assertEquals(1, ThemeResolver.active(context)!!.versionCode)
        val revision = ThemeRepository.revision(context)
        install("theme-a", true, false, version = 2)
        assertEquals(2, ThemeResolver.active(context)!!.versionCode)
        assertTrue(ThemeRepository.revision(context) > revision)
        assertNotEquals(previousWallpaper, wallpapers().first)
        assertEquals(WallpaperStore.SOLID_BLACK, wallpapers().second)
    }

    @Test fun brokenActiveUpdateRollsBackTheInstalledPackage() = runBlocking {
        install("theme-a", true, true)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        val before = prefs.all.toMap()
        val old = ThemeRepository.find(context, "theme-a")!!
        val result = runCatching {
            ThemeRepository.install(context, old.spec.copy(versionCode = 2), ThemeCodec.Assets(mapOf(
                old.spec.backgroundLight!! to png(0xff0000),
                old.spec.backgroundDark!! to byteArrayOf(1, 2, 3))))
        }
        assertTrue(result.isFailure)
        assertEquals(before, prefs.all)
        assertEquals(1, ThemeRepository.find(context, "theme-a")!!.spec.versionCode)
    }

    @Test fun concurrentSwitchesPublishOneCompleteTheme() = runBlocking {
        install("theme-a", true, false)
        install("theme-b", false, true)
        listOf(async { ThemeApplier.apply(context, "theme-a").getOrThrow() },
            async { ThemeApplier.apply(context, "theme-b").getOrThrow() }).awaitAll()
        when (ThemeRepository.activeId(context)) {
            "theme-a" -> {
                assertTrue(wallpapers().first!!.startsWith("theme-theme-a-"))
                assertEquals(WallpaperStore.SOLID_BLACK, wallpapers().second)
            }
            "theme-b" -> {
                assertEquals(WallpaperStore.SOLID_WHITE, wallpapers().first)
                assertTrue(wallpapers().second!!.startsWith("theme-theme-b-"))
            }
            else -> fail("No complete theme published")
        }
    }

    @Test fun listenersAlwaysSeeTheNewThemeWithBothNewWallpapers() = runBlocking {
        install("theme-a", true, true)
        shadowOf(Looper.getMainLooper()).idle()
        val observed = mutableListOf<Triple<String?, String?, String?>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(ThemeRepository.KEY_ACTIVE, "wallpaper_light", "wallpaper_dark")) {
                observed += Triple(ThemeRepository.activeId(context), wallpapers().first, wallpapers().second)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        ThemeApplier.apply(context, "theme-a").getOrThrow()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertTrue(observed.isNotEmpty())
        observed.forEach { (id, light, dark) ->
            assertEquals("theme-a", id)
            assertTrue(light!!.startsWith("theme-theme-a-"))
            assertTrue(dark!!.startsWith("theme-theme-a-"))
        }
    }

    @Test fun badgeAssetsSurviveInstallAndCustomStyleSurvivesSwitching() = runBlocking {
        val original = ModeBadgeStyle(prefix = "mine", dim = .65f, blur = 9f)
        ModeBadgeStyleStore.save(context, original)
        val entry = ThemeRepository.install(context, ThemeCodec.Spec(id = "badge-a", name = "Badge",
            modeBadge = ThemeCodec.ModeBadgeSpec(prefix = "new", image = "badge.png",
                font = "badge.ttf", dim = .4f, blur = 5f)),
            ThemeCodec.Assets(mapOf("badge.png" to png(0x00ff00), "badge.ttf" to byteArrayOf(1, 2))))
        assertTrue(File(entry.dir, entry.spec.modeBadge!!.image!!).isFile)
        assertTrue(File(entry.dir, entry.spec.modeBadge.font!!).isFile)
        ThemeCodec.read(ThemeRepository.export(context, "badge-a"))
        ThemeApplier.apply(context, "badge-a").getOrThrow()
        assertEquals(.4f, ModeBadgeStyleStore.load(context).dim)
        assertEquals(5f, ModeBadgeStyleStore.load(context).blur)
        install("theme-b", false, false)
        ThemeApplier.apply(context, "theme-b").getOrThrow()
        assertEquals(original, ModeBadgeStyleStore.load(context))
    }
}

/** Robolectric's native API 35 decoder cannot open host file descriptors on Windows.
 * Feed the same bytes through its supported buffer source; decoding and encoding stay real. */
@Implements(value = ImageDecoder::class, callNativeMethodsByDefault = true)
class HostImageDecoder : ShadowNativeImageDecoder() {
    companion object {
        @JvmStatic
        @Implementation
        fun createSource(file: File): ImageDecoder.Source =
            ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))
    }
}

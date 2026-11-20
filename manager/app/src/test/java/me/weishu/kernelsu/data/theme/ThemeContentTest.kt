package me.weishu.kernelsu.data.theme

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemeContentTest {
    private lateinit var context: Context
    private fun font() = javaClass.getResourceAsStream("/theme-test.ttf")!!.use { it.readBytes() }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ThemeResolver.invalidate()
        ThemeTextRuntime.refresh(context)
    }

    @Test fun fontAndTextRoundTripThroughUploadPackage() = runBlocking {
        val spec = ThemeCodec.Spec("content-test", "Content", globalFont = "font.otf",
            textOverrides = mapOf("home_brand_title" to "My phone", "confirm" to "Go"))
        ThemeRepository.install(context, spec, ThemeCodec.Assets(mapOf("font.otf" to font())))
        val exported = ThemeRepository.export(context, spec.id)
        val pkg = ThemeCodec.read(exported)
        assertEquals(spec.textOverrides, pkg.spec.textOverrides)
        assertArrayEquals(font(), pkg.assets.byName(pkg.spec.globalFont))
        assertNotNull(pkg.assets.preview())
        ThemeRepository.install(context, pkg.spec.copy(id = "content-copy"), pkg.assets)
        assertEquals(spec.textOverrides, ThemeRepository.find(context, "content-copy")!!.spec.textOverrides)
    }

    @Test fun switchAndSameIdEditReplaceTextAndFontCompletely() = runBlocking {
        val spec = ThemeCodec.Spec("content-switch", "Content", globalFont = ThemeFont.ASSET,
            textOverrides = mapOf("confirm" to "First"))
        ThemeRepository.install(context, spec, ThemeCodec.Assets(mapOf(ThemeFont.ASSET to font())))
        ThemeApplier.apply(context, spec.id).getOrThrow()
        assertNotNull(ThemeResolver.active(context)!!.fontFamily)
        val live = ThemeTextResources(context.resources, live = true)
        assertEquals("First", live.getString(R.string.confirm))
        ThemeRepository.install(context, spec.copy(globalFont = null, textOverrides = mapOf("confirm" to "Second")))
        assertNull(ThemeResolver.active(context)!!.fontFamily)
        assertEquals("Second", live.getString(R.string.confirm))
        ThemeRepository.install(context, ThemeCodec.Spec("plain-theme", "Plain"))
        ThemeApplier.apply(context, "plain-theme").getOrThrow()
        assertEquals(context.getString(R.string.confirm), live.getString(R.string.confirm))
        assertTrue(ThemeResolver.active(context)!!.textOverrides.isEmpty())
        ThemeApplier.restoreDefault(context).getOrThrow()
        assertNull(ThemeResolver.active(context))
        assertTrue(ThemeTextRuntime.replacements.isEmpty())
    }

    @Test fun malformedFontCannotReplaceAnInstalledTheme() = runBlocking {
        val spec = ThemeCodec.Spec("bad-font-test", "Original")
        ThemeRepository.install(context, spec)
        val failure = runCatching {
            ThemeRepository.install(context, spec.copy(name = "Broken", globalFont = "broken.ttf"),
                ThemeCodec.Assets(mapOf("broken.ttf" to ByteArray(100))))
        }
        assertTrue(failure.isFailure)
        assertEquals("Original", ThemeRepository.find(context, spec.id)!!.spec.name)
    }

    @Test fun missingFontAndEscapingPathsAreRejected() {
        val spec = ThemeCodec.Spec("missing-font", "Missing", globalFont = "font.ttf")
        assertTrue(runCatching { ThemeCodec.read(ThemeCodec.write(spec, emptyMap(), null)) }.isFailure)
        assertTrue(runCatching { ThemeCodec.parseSpec(ThemeCodec.toJson(spec.copy(globalFont = "../font.ttf"))) }.isFailure)
    }

    @Test fun placeholdersMustRetainTypesAndArgumentIndexes() {
        assertTrue(ThemeText.compatible("%1\$s has %2\$d", "%2\$d — %1\$s"))
        assertFalse(ThemeText.compatible("%1\$s", "%1\$d"))
        assertFalse(ThemeText.compatible("%d %s", "%s %d"))
        assertFalse(ThemeText.compatible("%1\$s", "missing"))
        assertFalse(ThemeText.compatible("%1\$s", "%1\$s %q"))
        assertFalse(ThemeText.compatible("Label", "%s"))
    }

    @Test fun formattedResourcesRenderAndLocaleMismatchFallsBack() {
        val source = context.resources
        val id = R.string.theme_editor_text_edit
        val resources = ThemeTextResources(source, mapOf("theme_editor_text_edit" to "Changed %1\$d"))
        assertEquals("Changed 3", resources.getString(id, 3))
        val invalid = ThemeTextResources(source, mapOf("theme_editor_text_edit" to "Wrong %1\$s"))
        assertEquals(source.getString(id, 3), invalid.getString(id, 3))
    }

    @Test fun fontHeaderChecksTableBoundsAndReadLimit() {
        val bytes = font()
        ThemeFont.validateHeader(bytes)
        bytes[20] = 0x7f
        assertTrue(runCatching { ThemeFont.validateHeader(bytes) }.isFailure)
        assertTrue(runCatching { ThemeFont.read(ByteArray(ThemeFont.MAX_BYTES + 1).inputStream()) }.isFailure)
    }

    @Test fun catalogContainsHomeAndButtonTextAndDefaultIgnoresOverrides() {
        val entries = ThemeText.catalog(context)
        assertTrue(entries.any { it.key == "home_brand_title" })
        assertTrue(entries.any { it.key == "confirm" })
        val source = context.resources
        val changed = ThemeTextResources(source, mapOf("confirm" to "Custom"))
        assertEquals(source, ThemeText.originalResources(changed))
    }
}

package me.weishu.kernelsu.data.theme

import android.content.Context
import android.content.res.Resources
import java.util.Locale
import org.json.JSONArray

/** Resource keys stay stable across locales; actual device values are never substituted. */
object ThemeText {
    const val MAX_ENTRIES = 2048
    const val MAX_LENGTH = 4096
    private val keyPattern = Regex("[a-z][a-z0-9_]{0,127}")
    private val format = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[tT]?[a-zA-Z%]")
    @Volatile private var cachedKeys: Set<String>? = null

    fun editableKeys(resources: Resources): Set<String> = cachedKeys ?: synchronized(this) {
        cachedKeys ?: JSONArray(resources.assets.open("theme-text-keys.json").bufferedReader().use { it.readText() })
            .let { json -> (0 until json.length()).map { json.getString(it) }.toSet() }
            .also { cachedKeys = it }
    }

    fun validateShape(values: Map<String, String>) {
        require(values.size <= MAX_ENTRIES) { "Too many text replacements (maximum $MAX_ENTRIES)" }
        values.forEach { (key, value) ->
            require(keyPattern.matches(key) && value.isNotBlank() && value.length <= MAX_LENGTH &&
                value.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Invalid text replacement: $key" }
        }
    }

    fun compatible(original: String, replacement: String): Boolean {
        val before = format.findAll(original).map { it.value }.toList()
        val after = format.findAll(replacement).map { it.value }.toList()
        // Preserve implicit argument order; explicit indexes may be reordered.
        val indexed = before.all { it == "%%" || it == "%n" || it.contains('$') }
        return (if (indexed) before.sorted() == after.sorted() else before == after) &&
            (before.isEmpty() || format.replace(replacement, "").none { it == '%' })
    }

    data class Entry(val key: String, val original: String)

    fun catalog(context: Context): List<Entry> {
        val resources = originalResources(context.resources)
        return editableKeys(resources).mapNotNull { key ->
            val id = resources.getIdentifier(key, "string", context.packageName)
            if (id == 0) null else Entry(key, resources.getString(id))
        }.sortedBy { it.original }
    }

    fun validate(context: Context, values: Map<String, String>) {
        validateShape(values)
        val resources = originalResources(context.resources)
        values.forEach { (key, replacement) ->
            val id = resources.getIdentifier(key, "string", context.packageName)
            // Unknown keys are kept for forward compatibility and ignored when rendering.
            if (id != 0) {
                require(key in editableKeys(resources)) { "Not an editable interface label: $key" }
                require(compatible(resources.getString(id), replacement)) {
                    "Keep the original placeholders in $key (for example %1\$s)"
                }
            }
        }
    }

    fun originalResources(resources: Resources): Resources =
        if (resources is ThemeTextResources) originalResources(resources.source) else resources
}

object ThemeTextRuntime {
    @Volatile var replacements: Map<String, String> = emptyMap()
        private set

    fun refresh(context: Context) = select(context, ThemeRepository.activeId(context))
    fun select(context: Context, id: String?) {
        replacements = id?.let { ThemeRepository.find(context, it)?.spec?.textOverrides }.orEmpty()
    }
}

/** A composition-scoped snapshot: switching to a theme without text overrides resets all strings. */
@Suppress("DEPRECATION")
class ThemeTextResources(val source: Resources, private val replacements: Map<String, String> = emptyMap(), private val live: Boolean = false) :
    Resources(source.assets, source.displayMetrics, source.configuration) {
    private fun replacement(id: Int): String? {
        val values = if (live) ThemeTextRuntime.replacements else replacements
        if (values.isEmpty()) return null
        val key = runCatching { source.getResourceEntryName(id) }.getOrNull() ?: return null
        if (key !in ThemeText.editableKeys(source)) return null
        return values[key]?.takeIf { ThemeText.compatible(source.getString(id), it) }
    }

    override fun getText(id: Int): CharSequence = replacement(id) ?: source.getText(id)
    override fun getText(id: Int, def: CharSequence?): CharSequence = replacement(id) ?: source.getText(id, def)
    override fun getString(id: Int): String = replacement(id) ?: source.getString(id)
    override fun getString(id: Int, vararg formatArgs: Any?): String {
        val text = replacement(id) ?: return source.getString(id, *formatArgs)
        return runCatching { String.format(configuration.locales[0] ?: Locale.getDefault(), text, *formatArgs) }
            .getOrElse { source.getString(id, *formatArgs) }
    }
}

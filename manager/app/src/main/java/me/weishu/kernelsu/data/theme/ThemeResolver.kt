package me.weishu.kernelsu.data.theme

import android.content.Context
import me.weishu.kernelsu.ui.design.token.XcGlass
import me.weishu.kernelsu.ui.design.token.XcNeonColors
import me.weishu.kernelsu.ui.design.token.XcShapesDefault
import me.weishu.kernelsu.ui.design.token.XcThemeData
import me.weishu.kernelsu.ui.design.token.withOverrides
import me.weishu.kernelsu.ui.design.token.xcColorsFor
import me.weishu.kernelsu.ui.design.token.xcNeonFor

/**
 * 主题（[ThemeCodec.Spec]）→ 设计令牌（[XcThemeData]）。
 *
 * 这里是"用户数据"与"设计系统"之间唯一的转换点：主题包里是字符串键值，令牌层要的是
 * `Color` / `Dp` / `Float`。放单独一个文件，是为了让两边都不知道对方的存在 ——
 * 令牌层不需要 import 主题包格式，主题包也不碰 Compose。
 *
 * 规则：
 *  * 深/浅两档各自解析（主题可以只给深色档，浅色档就沿用默认）；
 *  * AMOLED 档默认跟随深色档，主题若显式给了 `dark` 里的 backdrop/surface 就用它的；
 *  * 任何解析不了的颜色直接忽略（校验其实已经拦过，这里是第二道）。
 */
object ThemeResolver {

    fun resolve(spec: ThemeCodec.Spec): XcThemeData {
        val darkOverrides = parse(spec.dark)
        val lightOverrides = parse(spec.light)
        val dark = xcColorsFor(true).withOverrides(darkOverrides)
        val light = xcColorsFor(false).withOverrides(lightOverrides)
        // AMOLED 档：先取内置 AMOLED，再叠主题的深色覆盖 —— 纯黑底上如果主题没管，
        // 就还是纯黑（否则"AMOLED 开关"会莫名其妙失效）。
        val amoled = xcColorsFor(true, true).withOverrides(darkOverrides)
        val neonDark = if (spec.neonDark.isEmpty()) {
            null
        } else {
            xcNeonFor(true).withOverrides(parse(spec.neonDark))
        }
        val neonLight = if (spec.neonLight.isEmpty()) {
            null
        } else {
            xcNeonFor(false).withOverrides(parse(spec.neonLight))
        }
        return XcThemeData(
            id = spec.id,
            name = spec.name,
            author = spec.author,
            version = spec.version,
            versionCode = spec.versionCode,
            dark = dark,
            light = light,
            amoled = amoled,
            shapes = XcShapesDefault.withOverrides(spec.shapes),
            glass = XcGlass.Default.withOverrides(spec.glass),
            neonDark = neonDark,
            neonLight = neonLight,
            backgroundDark = spec.backgroundDark,
            backgroundLight = spec.backgroundLight,
        )
    }

    /**
     * 当前生效的主题（没装或读不出来就返回 null = 默认外观）。
     *
     * 带一层"同一个 id 不重复读盘"的缓存：调用方是 `MainActivityViewModel` 的
     * SharedPreferences 监听回调（任何外观设置一改就会走一遍），主题文件虽然只有几 KB，
     * 但那是**主线程上的磁盘 IO**，不该在滑一下开关时发生。
     */
    fun active(context: Context): XcThemeData? {
        val id = ThemeRepository.activeId(context) ?: run {
            cachedId = null
            cached = null
            return null
        }
        if (id == cachedId) return cached
        val entry = ThemeRepository.find(context, id) ?: run {
            cachedId = null
            cached = null
            return null
        }
        val resolved = runCatching { resolve(entry.spec) }.getOrNull()
        cachedId = id
        cached = resolved
        return resolved
    }

    @Volatile
    private var cachedId: String? = null

    @Volatile
    private var cached: XcThemeData? = null

    /** 主题内容变了（编辑保存/重新安装）时清缓存，下一次读取重新解析。 */
    fun invalidate() {
        cachedId = null
        cached = null
    }

    private fun parse(overrides: Map<String, String>): Map<String, Int> {
        if (overrides.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>(overrides.size)
        for ((key, value) in overrides) {
            ThemeCodec.parseColor(value)?.let { out[key] = it }
        }
        return out
    }

    /** 给编辑器/预览用：拿一份默认的霓虹板（`XcNeon` 的取值入口是 internal 的档位函数）。 */
    fun defaultNeon(isDark: Boolean): XcNeonColors = xcNeonFor(isDark)
}

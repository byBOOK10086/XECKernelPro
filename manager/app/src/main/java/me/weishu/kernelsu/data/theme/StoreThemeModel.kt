package me.weishu.kernelsu.data.theme

import androidx.compose.runtime.Immutable

/**
 * 商城里的一条主题（索引模型）。
 *
 * 这个类是整个商店的**唯一数据契约**：Kotlin 侧只在这里描述字段，索引侧
 * `_scratch/themes_repo/scripts/build_index.py` 负责生成。两边字段名逐字对应，
 * `_scratch/check_theme_contract.py` 会把这份文件里的 `val xxx:` 抓出来和脚本侧的
 * 字段表比对 —— 改字段必须两边一起改，否则守卫直接红。
 *
 * 版本演进（全部**向后兼容**：缺字段取默认值，老索引在老管理器上照旧能用）：
 *  * schema 1：`themeId/name/author/version/versionCode/description/summary/size/sha256/
 *    downloadUrl/previewUrl/colors/updatedAt/createdAt`
 *  * schema 2：`featured/types/previews`
 *  * schema 3：`category/elements/tags/screenshots/downloads/rating/ratingCount/priceCents/
 *    changelog/minManagerVersion/publishedAt/bannerUrl`
 */
@Immutable
data class StoreTheme(
    val themeId: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val summary: String = "",
    val description: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val downloadUrl: String = "",
    val previewUrl: String = "",
    val updatedAt: String = "",
    val accentHex: String = "",
    val backdropDarkHex: String = "",
    val backdropLightHex: String = "",
    /** 索引 v2：精选横幅（带预览图的主题才会被横幅挑中）。 */
    val featured: Boolean = false,
    /** 索引 v2：元素类型（"colors"=配色 / "modeBadge"=模式牌），老字段，保留兼容。 */
    val types: List<String> = emptyList(),
    /** 索引 v2：多张预览图（详情页用；横幅/卡片取第一张）。 */
    val previews: List<String> = emptyList(),
    /** 本地是否已经装了这个版本。 */
    val installedVersionCode: Int = 0,
    // ---------------------------------------------------------------- schema 3
    /**
     * 索引 v3 的分类原文。
     *
     * 单独留一个 `raw` 字段是因为 [category] 是**计算属性**（老索引没有 category 时要
     * 从 types 推出来），数据类里再放一个同名字段会让智能转换和构造调用都变糊。
     */
    val categoryRaw: String = "",
    /** 索引 v3：这个主题包里实际带了哪些元素（见 [Element]）。 */
    val elements: List<String> = emptyList(),
    /** 索引 v3：展示用标签，例如「暗色」「国风」。 */
    val tags: List<String> = emptyList(),
    /** 索引 v3：详情页的大图列表（与 [previews] 合并后去重才是全部图）。 */
    val screenshots: List<String> = emptyList(),
    /** 索引 v3：累计下载量（仓库侧统计；没有就是 0，界面不画假数据）。 */
    val downloads: Long = 0L,
    /** 索引 v3：平均评分 0~5；0 表示还没人评过（界面显示「暂无评分」而不是 0 分）。 */
    val rating: Float = 0f,
    /** 索引 v3：评分人数。 */
    val ratingCount: Int = 0,
    /** 索引 v3：价格（分）；0 = 免费。 */
    val priceCents: Int = 0,
    /** 索引 v3：这一版的更新说明。 */
    val changelog: String = "",
    /** 索引 v3：要求的最低管理器 versionCode（低了就只展示不给装）。 */
    val minManagerVersion: Int = 0,
    /** 索引 v3：首次上架时间（ISO-8601）。 */
    val publishedAt: String = "",
    /** 索引 v3：横幅专用大图（没有就用 [previewUrl]）。 */
    val bannerUrl: String = "",
) {
    val installed: Boolean get() = installedVersionCode > 0

    val updatable: Boolean get() = installed && versionCode > installedVersionCode

    val isFree: Boolean get() = priceCents <= 0

    /** 所有可展示的图（预览 + 截屏 + 单图 + 横幅），去重后保持顺序。 */
    val allImages: List<String>
        get() {
            val out = LinkedHashSet<String>(8)
            if (bannerUrl.isNotEmpty()) out += bannerUrl
            if (previewUrl.isNotEmpty()) out += previewUrl
            previews.forEach { if (it.isNotEmpty()) out += it }
            screenshots.forEach { if (it.isNotEmpty()) out += it }
            return out.toList()
        }

    /** 卡片/横幅优先用第一张能看的图。 */
    val coverUrl: String
        get() = allImages.firstOrNull().orEmpty()

    /**
     * 商店分类。
     *
     * 老索引（v1/v2）没有 category 字段：按 types 推 —— 带模式牌算「模式牌」，
     * 其余算「配色」。新索引（v3）写了什么就是什么，写错了也只是分类不准，不影响安装。
     */
    val category: String
        get() = categoryRaw.ifEmpty {
            when {
                elements.contains(Element.MODE_BADGE) || types.contains(TYPE_MODE_BADGE) -> CATEGORY_BADGE
                elements.contains(Element.FONT) -> CATEGORY_FONT
                elements.contains(Element.WALLPAPER) -> CATEGORY_WALLPAPER
                else -> CATEGORY_COLORS
            }
        }

    /** 这个主题实际上带了哪些元素（新旧索引合流后的结果）。 */
    val effectiveElements: List<String>
        get() {
            val out = LinkedHashSet<String>(4)
            elements.forEach { out += it }
            if (types.contains(TYPE_MODE_BADGE)) out += Element.MODE_BADGE
            if (types.contains(TYPE_COLORS)) out += Element.COLORS
            if (accentHex.isNotEmpty() || backdropDarkHex.isNotEmpty()) out += Element.COLORS
            return out.toList()
        }

    /** 管理器版本太低装不了（只在索引明确写了 minManagerVersion 时才拦）。 */
    fun tooOldFor(managerVersionCode: Int): Boolean =
        minManagerVersion > 0 && managerVersionCode in 1 until minManagerVersion

    companion object {
        const val TYPE_COLORS = "colors"
        const val TYPE_MODE_BADGE = "modeBadge"

        const val CATEGORY_THEME = "theme"
        const val CATEGORY_COLORS = "colors"
        const val CATEGORY_BADGE = "badge"
        const val CATEGORY_FONT = "font"
        const val CATEGORY_WALLPAPER = "wallpaper"

        /** 索引里允许出现的分类（多出来的值会被当成「其它」忽略，不报错）。 */
        val CATEGORIES = listOf(
            CATEGORY_THEME,
            CATEGORY_COLORS,
            CATEGORY_BADGE,
            CATEGORY_FONT,
            CATEGORY_WALLPAPER,
        )
    }
}

/** 主题包里能带的东西。字符串值和索引里的 `elements` 一致。 */
object Element {
    const val COLORS = "colors"
    const val MODE_BADGE = "modeBadge"
    const val WALLPAPER = "wallpaper"
    const val FONT = "font"

    val ALL = listOf(COLORS, MODE_BADGE, WALLPAPER, FONT)
}

package me.weishu.kernelsu.data.theme

import androidx.compose.runtime.Immutable

/**
 * 商城索引里的一个横幅位。
 *
 * 索引 v3 的 `banners` 可以指向某个主题（[themeId]），也可以是纯运营图（只给
 * [imageUrl] + [title]，点了不跳转）。旧的 v1/v2 索引没有 banners —— 那时由
 * [StoreCatalog.resolvedBanners] 用 featured 主题现场合成，界面代码只看合成结果。
 */
@Immutable
data class StoreBanner(
    val themeId: String = "",
    val imageUrl: String = "",
    val title: String = "",
    val subtitle: String = "",
) {
    val hasTarget: Boolean get() = themeId.isNotEmpty()
}

/**
 * 商城首页的一个分区（例如「本周热门」「新品上架」「编辑精选」）。
 *
 * [themeIds] 里的 id 在索引里找不到时**安静跳过**：运营改索引、用户还拿着旧缓存
 * 都会出现这种半截数据，这种时候少一张卡比整页报错好。
 */
@Immutable
data class StoreSection(
    val id: String,
    val title: String,
    val themeIds: List<String> = emptyList(),
)

/**
 * 一次成功拉取拿到的完整索引。
 *
 * 解析层（[StoreIndexCodec]）只负责把 JSON 变成这个对象；网络、镜像、缓存、ETag
 * 都归 [StoreCatalogRepository]。UI 侧只读这个对象，不碰 JSON。
 */
@Immutable
data class StoreCatalog(
    val schema: Int = 1,
    val generatedAt: String = "",
    val themes: List<StoreTheme> = emptyList(),
    val banners: List<StoreBanner> = emptyList(),
    val sections: List<StoreSection> = emptyList(),
) {
    /** id → 主题。用 lazy 而不是每次现算：首页/详情/相关推荐都会反复查。 */
    private val index: Map<String, StoreTheme> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        themes.associateBy { it.themeId }
    }

    val isEmpty: Boolean get() = themes.isEmpty()

    fun byId(themeId: String): StoreTheme? = index[themeId]

    /** 按 id 把一个分区翻译成主题列表（顺序按索引，找不到的丢掉）。 */
    fun themesOf(section: StoreSection): List<StoreTheme> =
        section.themeIds.mapNotNull { index[it] }

    /** 所有非空分区（原顺序）。 */
    fun filledSections(): List<Pair<StoreSection, List<StoreTheme>>> =
        sections.map { it to themesOf(it) }.filter { it.second.isNotEmpty() }

    /** 精选：显式 featured 且至少有一张能画的图。 */
    val featured: List<StoreTheme>
        get() = themes.filter { it.featured && it.allImages.isNotEmpty() }

    /**
     * 横幅位的最终形态：索引写了 banners 就用它（按索引顺序），没写就用 featured 现场合成。
     *
     * 返回的 Pair 里第二项为 null 表示"纯运营图，点了不跳详情"。
     */
    fun resolvedBanners(): List<Pair<StoreBanner, StoreTheme?>> {
        val explicit = banners.map { banner ->
            val target = banner.themeId.takeIf { it.isNotEmpty() }?.let { index[it] }
            val image = banner.imageUrl.ifEmpty { target?.coverUrl.orEmpty() }
            val normalized = if (banner.imageUrl.isEmpty() && image.isNotEmpty()) {
                banner.copy(imageUrl = image)
            } else {
                banner
            }
            normalized to target
        }.filter { it.first.imageUrl.isNotEmpty() }
        if (explicit.isNotEmpty()) return explicit
        return featured.take(MAX_SYNTHETIC_BANNERS).map { theme ->
            StoreBanner(
                themeId = theme.themeId,
                imageUrl = theme.coverUrl,
                title = theme.name,
                subtitle = theme.author,
            ) to theme
        }
    }

    /** 不带重复的标签表，按出现次数从多到少（分类页的筛选 chips 用它）。 */
    fun tagCloud(): List<String> = themes
        .flatMap { it.tags }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    /** 实际出现过的分类（按 [StoreTheme.CATEGORIES] 的固定顺序，不出现就不列）。 */
    fun presentCategories(): List<String> {
        val present = themes.map { it.category }.toSet()
        return StoreTheme.CATEGORIES.filter { present.contains(it) }
    }

    companion object {
        /** 老索引没有 banners 时最多合成几个。 */
        private const val MAX_SYNTHETIC_BANNERS = 5

        val EMPTY = StoreCatalog()
    }
}

/**
 * 把「本地已装版本」重新贴回索引模型。
 *
 * 索引本身不知道用户装了什么：`installedVersionCode` 是管理器在解析后注入的。
 * 下载/安装/删除之后必须重新注入一次，否则卡片上的「已安装/可更新」会停在旧状态。
 */
fun StoreCatalog.withInstalled(installed: Map<String, Int>): StoreCatalog = copy(
    themes = themes.map { theme ->
        theme.copy(installedVersionCode = installed[theme.themeId] ?: 0)
    },
)

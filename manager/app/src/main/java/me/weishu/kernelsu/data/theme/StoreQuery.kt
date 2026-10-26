package me.weishu.kernelsu.data.theme

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 商店排序方式。全部基于**索引里的真实字段**，没有的字段不参与排序（不画假榜）。 */
enum class StoreSort {
    /** 推荐：精选优先 → 下载量 → 更新时间。 */
    RECOMMENDED,
    /** 最新：更新时间倒序。 */
    LATEST,
    /** 热门：下载量倒序。 */
    HOT,
    /** 好评：评分倒序（评分人数做次级键）。 */
    RATING,
}

/** 分类页/搜索结果页的筛选条件。[category] 为 [CATEGORY_ALL] 表示不筛分类。 */
data class StoreFilter(
    val category: String = CATEGORY_ALL,
    val element: String = "",
    val tag: String = "",
    val query: String = "",
) {
    val active: Boolean
        get() = category != CATEGORY_ALL || element.isNotEmpty() || tag.isNotEmpty() || query.isNotBlank()

    companion object {
        const val CATEGORY_ALL = "all"
        val NONE = StoreFilter()
    }
}

/**
 * 查询引擎：筛选 → 搜索打分 → 排序。
 *
 * 纯函数、无 Android 依赖，方便直接在 `check_theme_contract.py` 之外的地方做推理验证；
 * UI 每次状态变化都调它（集合几十到几百条，开销可以忽略，不值得上分页索引）。
 */
object StoreQuery {

    /** 名字完全相等的加权，比"包含"高一个量级，保证搜"紫罗兰"时正主排第一。 */
    private const val SCORE_NAME_EXACT = 1000
    private const val SCORE_NAME_PREFIX = 800
    private const val SCORE_NAME_CONTAINS = 600
    private const val SCORE_AUTHOR_CONTAINS = 400
    private const val SCORE_ID_CONTAINS = 350
    private const val SCORE_TAG_EXACT = 320
    private const val SCORE_TAG_CONTAINS = 260
    private const val SCORE_SUMMARY_CONTAINS = 200
    private const val SCORE_DESCRIPTION_CONTAINS = 100

    fun apply(items: List<StoreTheme>, sort: StoreSort, filter: StoreFilter): List<StoreTheme> {
        val filtered = items.asSequence()
            .filter { filter.category == StoreFilter.CATEGORY_ALL || it.category == filter.category }
            .filter { filter.element.isEmpty() || it.effectiveElements.contains(filter.element) }
            .filter { filter.tag.isEmpty() || it.tags.contains(filter.tag) }
            .filter { filter.query.isBlank() || score(it, filter.query) > 0 }
            .toList()
        return when (sort) {
            StoreSort.RECOMMENDED -> filtered.sortedWith(
                compareByDescending<StoreTheme> { it.featured }
                    .thenByDescending { it.downloads }
                    .thenByDescending { timeOf(it) }
                    .thenBy { it.name }
            )

            StoreSort.LATEST -> filtered.sortedWith(
                compareByDescending<StoreTheme> { timeOf(it) }.thenBy { it.name }
            )

            StoreSort.HOT -> filtered.sortedWith(
                compareByDescending<StoreTheme> { it.downloads }
                    .thenByDescending { it.rating }
                    .thenByDescending { timeOf(it) }
            )

            StoreSort.RATING -> filtered.sortedWith(
                compareByDescending<StoreTheme> { it.rating }
                    .thenByDescending { it.ratingCount }
                    .thenByDescending { it.downloads }
            )
        }
    }

    /**
     * 搜索：先按 [StoreQuery.score] 命中过滤，再按分数排序。
     *
     * @param limit 0 = 不限。
     */
    fun search(items: List<StoreTheme>, query: String, limit: Int = 0): List<StoreTheme> {
        if (query.isBlank()) return emptyList()
        val scored = items.asSequence()
            .map { it to score(it, query) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<StoreTheme, Int>> { it.second }
                    .thenByDescending { it.first.downloads }
                    .thenBy { it.first.name }
            )
            .map { it.first }
            .toList()
        return if (limit > 0) scored.take(limit) else scored
    }

    /** 相关度打分；0 = 不匹配。 */
    fun score(theme: StoreTheme, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val name = theme.name.lowercase()
        val id = theme.themeId.lowercase()
        var score = 0
        when {
            name == q -> score += SCORE_NAME_EXACT
            name.startsWith(q) -> score += SCORE_NAME_PREFIX
            name.contains(q) -> score += SCORE_NAME_CONTAINS
        }
        if (theme.author.lowercase().contains(q)) score += SCORE_AUTHOR_CONTAINS
        if (id.contains(q)) score += SCORE_ID_CONTAINS
        for (tag in theme.tags) {
            val t = tag.lowercase()
            when {
                t == q -> score += SCORE_TAG_EXACT
                t.contains(q) -> score += SCORE_TAG_CONTAINS
            }
        }
        if (theme.summary.lowercase().contains(q)) score += SCORE_SUMMARY_CONTAINS
        if (theme.description.lowercase().contains(q)) score += SCORE_DESCRIPTION_CONTAINS
        return score
    }

    /** 搜索建议：名字里包含输入的前 [limit] 条（输入为空时给空表）。 */
    fun suggestions(items: List<StoreTheme>, query: String, limit: Int = 6): List<StoreTheme> {
        if (query.isBlank()) return emptyList()
        return search(items, query, limit)
    }

    /**
     * 把索引里的时间串换成可比较的时间戳。
     *
     * 索引允许三种写法（都是真实出现过的手写形态）：完整 ISO-8601、
     * 带偏移量的 `2026-10-20T10:00:00+08:00`、只有日期 `2026-10-20`。
     * 解析不了就当 0（排最后），绝不因为一条时间写错整页崩掉。
     */
    fun timeOf(theme: StoreTheme): Long {
        val raw = theme.updatedAt.ifEmpty { theme.publishedAt }
        if (raw.isEmpty()) return 0L
        parseIso(raw)?.let { return it }
        return 0L
    }

    private fun parseIso(raw: String): Long? {
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching {
            return LocalDate.parse(raw.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }
}

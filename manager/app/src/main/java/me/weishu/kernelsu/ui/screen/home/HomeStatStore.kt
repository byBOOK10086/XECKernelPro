package me.weishu.kernelsu.ui.screen.home

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.weishu.kernelsu.ksuApp

/**
 * 首页数据面板的槽位配置（第三轮）。
 *
 * ## 为什么是一个独立 object，而不是走 `SettingsRepository` 那一套
 *
 * 仓库里配置的常规路径是「`SettingsRepository` 加一个 `var` → 设置页读一次 →
 * 想让全 App 跟着变，还得把 key 加进 `MainActivityViewModel.observedKeys` 白名单」
 * （`MainActivityViewModel.kt:64-85` 是个**白名单**，漏加就静默不刷新）。
 *
 * 面板配置只有**一个写入者**（编辑面板自己），读它的也只有首页一处。为了它去动
 * 五个设置文件、还依赖一个容易漏的白名单，是纯负债。这里用 `StateFlow` 直接广播：
 * 编辑面板 `assign` 一次，首页的 `collectAsState` 立刻重组 —— 没有中间层，也就没有
 * "改了不生效"这种坑。
 *
 * ## 落盘格式
 *
 * SharedPreferences 里**一个有序字符串**：`"kernel_version,manager_version,superuser"`。
 * 空串 = 从没配置过 → 用 [Defaults]。
 *
 * 不用 JSON：仓库里落盘的结构化数据确实用 `org.json`（`StoreIndexCodec.kt`），但那是
 * 有嵌套结构的索引；这里就是三个 id，一个逗号分隔串足够，且**读坏了自己能兜住**
 * （[decode] 对未知 id 静默跳过并向默认项补齐，永不抛）。
 */
object HomeStatStore {

    /** 面板固定三格。 */
    const val SLOT_COUNT = 3

    private const val PREFS = "settings"
    private const val KEY = "home_stat_items"

    /**
     * 出厂默认：就是第二轮那三格（内核 / 管理器 / 超级用户）。
     *
     * 刻意**不改**默认值：让老用户升级后看到的还是原来那三格，面板"可自定义"
     * 是新增能力，不是替他重排。
     */
    val Defaults: List<HomeStatItem> = listOf(
        HomeStatItem.KERNEL_VERSION,
        HomeStatItem.MANAGER_VERSION,
        HomeStatItem.SUPERUSER,
    )

    private val prefs by lazy {
        ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val state: MutableStateFlow<List<HomeStatItem>> by lazy {
        MutableStateFlow(decode(prefs.getString(KEY, "").orEmpty()))
    }

    /** 当前三个槽位。首页订阅它。 */
    val slots: StateFlow<List<HomeStatItem>> get() = state

    /** 第 [slot] 格放什么；越界时返回默认项（不该发生，纯兜底）。 */
    fun at(slot: Int): HomeStatItem =
        state.value.getOrNull(slot) ?: Defaults[slot.coerceIn(0, Defaults.lastIndex)]

    /**
     * 把 [item] 放进第 [slot] 格。
     *
     * 已经被别的格子占用的项目直接忽略 —— 三个格子显示同一个数字没有意义，
     * 而"点了没反应"比"悄悄换掉另一个格子"好理解。想调换请用 [move]。
     */
    fun assign(slot: Int, item: HomeStatItem) {
        val current = state.value
        if (slot !in current.indices) return
        if (current[slot] == item) return
        // 别处已占用 → 不动。候选列表里那一项是置灰的，正常点不到这里。
        if (current.withIndex().any { it.index != slot && it.value == item }) return
        val next = current.toMutableList().also { it[slot] = item }
        write(next)
    }

    /** 把第 [index] 格与相邻的第 `index + delta` 格对调（排序）。 */
    fun move(index: Int, delta: Int) {
        val current = state.value
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val next = current.toMutableList()
        val swapped = next[index]
        next[index] = next[target]
        next[target] = swapped
        write(next)
    }

    /** 恢复出厂三项。 */
    fun reset() = write(Defaults)

    private fun write(items: List<HomeStatItem>) {
        val normalized = normalize(items)
        state.value = normalized
        prefs.edit { putString(KEY, normalized.joinToString(",") { it.id }) }
    }

    /**
     * 把任意长度、可能重复的列表规整成"恰好三格、互不重复"。
     *
     * 补齐顺序：先补 [Defaults] 里还没用到的，再补其余项目。这样即使存档里只有
     * 一个有效 id（例如用户在老版本里手改过 prefs），也能得到一个合理的三格。
     */
    private fun normalize(items: List<HomeStatItem>): List<HomeStatItem> {
        val result = items.distinct().take(SLOT_COUNT).toMutableList()
        if (result.size < SLOT_COUNT) {
            for (item in Defaults) {
                if (result.size >= SLOT_COUNT) break
                if (item !in result) result += item
            }
        }
        if (result.size < SLOT_COUNT) {
            for (item in HomeStatItem.ALL) {
                if (result.size >= SLOT_COUNT) break
                if (item !in result) result += item
            }
        }
        return result
    }

    /**
     * 读存档。**永不抛**：未知 id 跳过，全空就用默认。
     *
     * 用 `split(',')` 而不是 `split(',', limit = 3)`：万一以后允许重复 id 或加字段，
     * 截断会静默吃掉数据。
     */
    internal fun decode(raw: String): List<HomeStatItem> {
        val parsed = raw.split(',')
            .mapNotNull { HomeStatItem.fromId(it) }
            .distinct()
        return normalize(parsed)
    }
}

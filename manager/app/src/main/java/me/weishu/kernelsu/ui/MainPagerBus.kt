package me.weishu.kernelsu.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨页面切主分页的请求通道（2026-11-09 建立，2026-11-10 改为"粘滞待办"）。
 *
 * 为什么不是直接读 `LocalMainPagerState`：它的 provider 在 `MainScreen` 里，
 * 而工具箱这类页面是 NavDisplay 的**独立 entry**，不在那棵子树里 —— 读它会命中
 * `error("LocalMainPagerState not provided")`（审计 P0）。
 *
 * 2026-11-10 真机故障："工具箱 → XEC SCORE 卡点了进不去检测页"。
 * 旧实现是 `MutableSharedFlow(replay = 0)`：工具箱压在主分页之上时 `MainScreen`
 * 已经退出组合、收集者不在，`tryEmit` 的值**当场被丢弃**；随后 `navigator.pop()`
 * 回到主分页，仍停在原来那页 —— 表现为"点了没反应"。
 *
 * 改成 StateFlow 保存**待办**：请求留在那里，等主分页重新组合、收集者上线时立刻
 * 消费一次，然后清空（避免每次重组都重复切页）。
 *
 * 分页索引与 [me.weishu.kernelsu.ui.viewmodel.MainPagerConfig] 一致。
 */
object MainPagerBus {

    private val _pending = MutableStateFlow<Int?>(null)
    val pending: StateFlow<Int?> = _pending.asStateFlow()

    fun jumpTo(page: Int) {
        _pending.value = page
    }

    /** 主分页已执行这条请求，清空待办。 */
    fun consume() {
        _pending.value = null
    }
}

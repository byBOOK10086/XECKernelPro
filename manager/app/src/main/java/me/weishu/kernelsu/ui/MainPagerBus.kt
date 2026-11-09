package me.weishu.kernelsu.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 跨页面切主分页的请求通道（2026-11-09）。
 *
 * 为什么不是直接读 `LocalMainPagerState`：它的 provider 在 `MainScreen` 里，
 * 而工具箱这类页面是 NavDisplay 的**独立 entry**，不在那棵子树里 —— 读它会命中
 * `error("LocalMainPagerState not provided")`（审计 P0）。
 *
 * 把 pager 状态整体上提到 NavDisplay 之上是治本方案，但会动 4 个 nav entry 的
 * 构造参数；这一版先用一条**有界**的单向事件通道：MainScreen 订阅、其它页面只能
 * 发"请切到第 N 页"。通道容量 1、满则丢旧值 —— 连点只会落到最后一页，不会堆积。
 *
 * 分页索引与 [me.weishu.kernelsu.ui.viewmodel.MainPagerConfig] 一致。
 */
object MainPagerBus {

    private val _requests = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests: SharedFlow<Int> = _requests

    fun jumpTo(page: Int) {
        _requests.tryEmit(page)
    }
}

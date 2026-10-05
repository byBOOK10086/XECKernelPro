// 液态玻璃绘制内核的全局开关与异常熔断状态。
//
// 设计文档：docs/superpowers/specs/2026-09-16-liquid-glass-kernel-port-design.md（措施 2、措施 3）

package me.weishu.kernelsu.ui.component.liquid

import android.util.Log

internal object XcGlassKernel {

    private const val TAG = "XcGlassKernel"

    /** 玻璃绘制内核。 */
    enum class Kernel {
        /**
         * 移植自 QWEA0/Liquid-Glass-Android（MIT）的 `LENS_AGSL`：
         * 单 pass 完成圆角盒 SDF → 斜面厚度剖面 → 屏幕空间法线 → 折射 → 三通道色散 → 贴边亮线。
         */
        NEW,

        /** 本仓库原有的三个 AGSL 常量，保留为回退路径。 */
        LEGACY,
    }

    /**
     * 当前内核。默认走新内核；一旦 [trip] 被触发就永久切到 [Kernel.LEGACY]。
     *
     * 在绘制线程读取、可能在绘制线程写入，故用 `@Volatile`。
     */
    @Volatile
    var current: Kernel = Kernel.NEW
        private set

    /**
     * 熔断：把内核切到 [Kernel.LEGACY]，本进程后续所有玻璃绘制都走旧内核。
     *
     * 与上游 `GlassLensRenderer` 的 `shaderBroken` 语义一致：只在首次触发时记一条日志，
     * 之后重复调用是空操作，避免每帧刷日志。
     */
    fun trip(cause: Throwable) {
        if (current == Kernel.LEGACY) return
        current = Kernel.LEGACY
        Log.w(TAG, "新液态玻璃内核不可用，已回退到旧内核", cause)
    }
}

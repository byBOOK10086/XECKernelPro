package me.weishu.kernelsu.ui.design.clear

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 壁纸采样源：挂在根层壁纸 `Image` 上的 [LayerBackdrop]，经 CompositionLocal 下发全应用。
 *
 * 这是「全部组件真液态玻璃」的拓扑基础。自采样成环的判据是
 * 「消费者位于采样源的录制子树**内部**」（详见 `XGlassSurface.xGlassBody` 的注释）：
 * 页面级 backdrop 录的是滚动内容，卡体就在里面，采它必成环 → SIGSEGV；
 * 而壁纸 Image 的录制子树里**永远只有壁纸自己**——任何组件（卡体、按钮、面板、
 * 对话框内容）采它都合法，并且所见即所得：玻璃折射的正好是每张卡片身后那段壁纸。
 *
 * 值为 `null` 表示设备不支持模糊或用户关掉了模糊，消费方应走实色降级档。
 * 生产端在 `MainActivity` 根层：`rememberBlurBackdrop(enableBlur)` 建源、
 * `Modifier.layerBackdrop(...)` 挂到壁纸 Image 上。
 */
val LocalWallpaperBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

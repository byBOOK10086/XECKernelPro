package me.weishu.kernelsu.ui

/**
 * 隐身入口的常开落点：xudc stealth 守卫 am start 到这里。
 *
 * 为什么需要它：藏图标要停用的是 launcher 组件（XecMainActivity），而**已停用的
 * 组件无法被 am start**——守卫若指向 launcher 组件，图标一藏入口就死（第一版
 * 的实测教训）。这个 Activity 不带 LAUNCHER filter、永不停用，直接继承
 * MainActivity，没有转发没有闪屏，行为与从图标打开一致。
 */
class StealthLaunchActivity : MainActivity()

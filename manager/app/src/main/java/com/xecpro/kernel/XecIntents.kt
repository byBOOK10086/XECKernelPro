package com.xecpro.kernel

import android.content.Context
import android.content.Intent

/**
 * Internal navigation intents used by the home-screen widget.
 *
 * 2026-11-18（Ultra 精简）：一键隐藏（紫罗兰环境隐藏 / 隐藏 TEE 与密钥）整块功能下线，
 * 这里只剩"回到管理器首页"这一个入口。原先的一次性 token 路由与鉴权分支一起删除，
 * 避免留下一个点下去没反应的桌面组件按钮。
 */
object XecIntents {
    fun openHome(context: Context): Intent =
        Intent(context, me.weishu.kernelsu.ui.StealthLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

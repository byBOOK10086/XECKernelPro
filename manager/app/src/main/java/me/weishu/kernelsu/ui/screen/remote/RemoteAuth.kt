package me.weishu.kernelsu.ui.screen.remote

import android.content.Context

/**
 * 远程模块助手账号会话（本地持久化 token；服务端会话 30 天有效，过期后
 * 服务端返回未登录错误，调用方清会话并引导重新登录）。
 */
object RemoteAuth {
    private const val PREFS = "remote_hub"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "username"
    private const val KEY_ADMIN = "is_admin"

    data class Account(val token: String, val username: String, val isAdmin: Boolean)

    fun load(context: Context): Account? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString(KEY_TOKEN, null) ?: return null
        return Account(token, p.getString(KEY_USER, "") ?: "", p.getBoolean(KEY_ADMIN, false))
    }

    fun save(context: Context, session: RemoteApi.Session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USER, session.username)
            .putBoolean(KEY_ADMIN, session.isAdmin)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

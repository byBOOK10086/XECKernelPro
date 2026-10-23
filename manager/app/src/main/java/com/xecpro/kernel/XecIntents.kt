package com.xecpro.kernel

import android.content.Context
import android.content.Intent
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl

/**
 * Internal navigation intents used by the home-screen widget and the Quick
 * Settings tile. The route carries the same one-shot token as the module-install
 * deep link, so a third-party app cannot ask the manager to run a root action.
 */
object XecIntents {
    const val EXTRA_ROUTE = "me.weishu.kernelsu.extra.OPEN_ROUTE"
    const val EXTRA_TOKEN = "me.weishu.kernelsu.extra.OPEN_TOKEN"
    const val ROUTE_ONE_TAP_HIDE = "one_tap_hide"

    fun openHome(context: Context): Intent =
        Intent(context, XecMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun oneTapHide(context: Context): Intent =
        openHome(context).apply {
            putExtra(EXTRA_ROUTE, ROUTE_ONE_TAP_HIDE)
            putExtra(EXTRA_TOKEN, SettingsRepositoryImpl().intentToken)
        }

    fun isAuthorized(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getStringExtra(EXTRA_ROUTE) != ROUTE_ONE_TAP_HIDE) return false
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        return token.isNotBlank() && token == SettingsRepositoryImpl().intentToken
    }
}

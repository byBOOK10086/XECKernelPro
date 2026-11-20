package com.xecpro.kernel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.weishu.kernelsu.privilege.StealthEntrance

/** Fail open after reboot/update, including temporary-root devices with no daemon. */
class StealthRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!StealthEntrance.restoreIcon(context)) Log.e("StealthRecovery", "Could not restore launcher")
    }
}

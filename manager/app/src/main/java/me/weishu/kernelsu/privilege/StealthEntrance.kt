package me.weishu.kernelsu.privilege

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import me.weishu.kernelsu.ui.util.getRootShell

object StealthEntrance {
    private const val LAUNCHER = "com.xecpro.kernel.XecMainActivity"
    private const val STEALTH_ENTRY = "me.weishu.kernelsu.ui.StealthLaunchActivity"
    private const val PREFS = "stealth_entrance"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LAUNCHERS = "launchers"
    private const val TAG = "StealthEntrance"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun stealthComponent(context: Context) = ComponentName(context.packageName, STEALTH_ENTRY)

    private fun launchers(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val discovered = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapNotNull { it.activityInfo?.name }
        val saved = prefs(context).getString(KEY_LAUNCHERS, "").orEmpty().split('\n')
        return (discovered + saved + LAUNCHER).distinct().filter { name ->
            name.isNotBlank() && name != STEALTH_ENTRY && runCatching {
                context.packageManager.getActivityInfo(ComponentName(context.packageName, name), PackageManager.MATCH_DISABLED_COMPONENTS)
            }.isSuccess
        }
    }

    private fun enabled(context: Context, name: String): Boolean {
        val component = ComponentName(context.packageName, name)
        return when (context.packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> context.packageManager
                .getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            else -> false
        }
    }

    private fun setEnabled(context: Context, name: String, value: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, name),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        check(enabled(context, name) == value) { "Component change did not persist: $name" }
    }

    private fun controller(context: Context) = StealthController(object : StealthController.Backend {
        override fun launchers() = StealthEntrance.launchers(context)
        override fun enableRecovery() = setEnabled(context, STEALTH_ENTRY, true)
        override fun setLauncher(name: String, enabled: Boolean) = setEnabled(context, name, enabled)
        override fun launcherEnabled(name: String) = enabled(context, name)
        override fun save(hidden: Boolean, launchers: List<String>) {
            check(prefs(context).edit().putBoolean(KEY_ACTIVE, hidden)
                .putString(KEY_LAUNCHERS, launchers.joinToString("\n")).commit()) { "Cannot save recovery state" }
        }
        override fun startWatcher(): Boolean {
            val component = stealthComponent(context).flattenToString()
            val user = Process.myUid() / 100000
            val result = getRootShell().newJob()
                .add("${getKsuDaemonPath()} stealth start --component '$component' --user $user")
                .exec()
            return result.isSuccess && result.out.any { it.trim() == "stealth: ready v2" }
        }
        override fun stopWatcher() = StealthEntrance.stopWatcher()
    })

    @Synchronized
    fun isIconHidden(context: Context): Boolean = runCatching {
        launchers(context).any { !enabled(context, it) }
    }.getOrDefault(prefs(context).getBoolean(KEY_ACTIVE, false))

    @Synchronized
    fun repair(context: Context) {
        runCatching {
            setEnabled(context, STEALTH_ENTRY, true)
            if (!prefs(context).getBoolean(KEY_ACTIVE, false)) controller(context).restore()
        }.onFailure { Log.e(TAG, "Entrance repair failed", it) }
    }

    @Synchronized
    fun enable(context: Context): Boolean = controller(context).hide()

    /** Root is deliberately not required to restore the icon. */
    @Synchronized
    fun restoreIcon(context: Context): Boolean = controller(context).restore()

    fun disable(context: Context): Boolean {
        val restored = restoreIcon(context)
        if (restored) runCatching { stopWatcher() }.onFailure { Log.w(TAG, "Watcher cleanup failed", it) }
        return restored
    }

    private fun stopWatcher() {
        getRootShell().newJob().add("${getKsuDaemonPath()} stealth stop").exec()
    }

    fun adbFallback(context: Context): String =
        "adb shell am start --user ${Process.myUid() / 100000} -n ${stealthComponent(context).flattenToString()}"
}

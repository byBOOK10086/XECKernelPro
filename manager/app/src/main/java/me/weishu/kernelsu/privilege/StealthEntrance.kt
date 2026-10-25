package me.weishu.kernelsu.privilege

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.ui.util.getKsuDaemonPath

/**
 * 隐身入口（寄生在「激活开发者选项」原生手势上）的管理器侧开关。
 *
 * 第一版的教训（用户实测）：launcher 组件是品牌壳 `XecMainActivity`
 * （MainActivity 的子类），且**已停用的组件无法被 am start**——守卫指向被
 * 停用的组件等于入口自杀；反之停用基础 `MainActivity` 又拦不住内部重启流程。
 *
 * 第二版的组件账：
 *  - 图标组件 = 运行时发现（MAIN/LAUNCHER intent 查询本包），发现即记进
 *    prefs；隐身后 queryIntentActivities 查不到停用组件，恢复全靠 prefs。
 *  - 守卫落点 = [STEALTH_ENTRY]（StealthLaunchActivity，无 LAUNCHER filter、
 *    永不停用、exported）——图标藏了它照样能被 am start。
 *  - 开启顺序仍是安全关键：守卫武装成功才藏图标；关闭先恢复图标。
 *  - 自愈：开启/关闭/管理器启动都会把基础 MainActivity 拉回 ENABLED，
 *    修复第一版留下的坏状态。
 */
object StealthEntrance {

    private const val BASE_MAIN = "me.weishu.kernelsu.ui.MainActivity"
    private const val STEALTH_ENTRY = "me.weishu.kernelsu.ui.StealthLaunchActivity"
    private const val PREFS = "stealth_entrance"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LAUNCHERS = "launchers" // '\n' 分隔的 className 列表

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun baseMain(context: Context) = ComponentName(context.packageName, BASE_MAIN)

    /** 守卫 am start 的常开落点。 */
    fun stealthComponent(context: Context) =
        ComponentName(context.packageName, STEALTH_ENTRY)

    /** 当前真正挂着桌面图标的组件（隐身后查不到，历史值在 prefs）。 */
    private fun queryLauncherComponents(context: Context): List<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info -> info.activityInfo?.let { ComponentName(it.packageName, it.name) } }
            .distinct()
    }

    private fun savedLaunchers(context: Context): List<ComponentName> =
        prefs(context).getString(KEY_LAUNCHERS, null)
            ?.split('\n')
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.map { ComponentName(context.packageName, it) }
            .orEmpty()

    private fun setEnabled(context: Context, comp: ComponentName, enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                comp,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun isEnabled(context: Context, comp: ComponentName): Boolean =
        context.packageManager.getComponentEnabledSetting(comp) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** 管理器每次启动都调：修复第一版误停的基础 MainActivity。 */
    fun repair(context: Context) {
        if (!isEnabled(context, baseMain(context))) {
            setEnabled(context, baseMain(context), true)
        }
    }

    /** 隐身是否生效（prefs 是事实源；图标隐藏后组件查不到，不能现场查）。 */
    fun isIconHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    /** @return 守卫已武装且图标组件已全部停用。 */
    fun enable(context: Context): Boolean {
        val target = stealthComponent(context)
        val arg = "${target.packageName}/${target.className}"
        val result = Shell.cmd("${getKsuDaemonPath()} stealth start --component '$arg'").exec()
        if (!result.isSuccess) {
            return false
        }
        // 先把图标组件记下来再停用（停用后 queryIntentActivities 查不到）。
        val launchers = queryLauncherComponents(context)
        prefs(context).edit()
            .putString(KEY_LAUNCHERS, launchers.joinToString("\n") { it.className })
            .putBoolean(KEY_ACTIVE, true)
            .apply()
        repair(context)
        launchers.forEach { setEnabled(context, it, false) }
        return true
    }

    /** 先恢复图标与基础组件，再停守卫清配置。 */
    fun disable(context: Context): Boolean {
        savedLaunchers(context).forEach { setEnabled(context, it, true) }
        setEnabled(context, baseMain(context), true)
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
        Shell.cmd("${getKsuDaemonPath()} stealth stop").submit()
        return true
    }

    /** adb 兜底命令（确认对话框里展示给用户抄；隐身态下也有效）。 */
    fun adbFallback(context: Context): String {
        val comp = stealthComponent(context)
        return "adb shell am start -n ${comp.packageName}/${comp.className}"
    }
}

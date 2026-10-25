package me.weishu.kernelsu.privilege

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.ui.util.getKsuDaemonPath

/**
 * 隐身入口（寄生在「激活开发者选项」原生手势上）的管理器侧开关。
 *
 * 语义（与 ksud stealth 守卫对齐）：
 *  - 开启顺序是安全关键：**先武装守卫、成功后才藏图标**——守卫没起来绝不藏，
 *    否则一次失败就把用户锁在 GUI 外面。
 *  - 关闭顺序相反：**先恢复图标**（无论守卫状态），再停守卫清配置。
 *  - 入口 = 设置 → 关于手机 → 连点版本号 10 下（5 下的原生开发者选项行为不受影响）。
 *  - 永久兜底：adb / root shell 的 am start 一句拉起（见 [adbFallback]）。
 */
object StealthEntrance {

    // 品牌化只改包名（KSU_PACKAGE_NAME），入口 Activity 类名是稳定的。
    private const val MAIN_ACTIVITY = "me.weishu.kernelsu.ui.MainActivity"

    fun launcherComponent(context: Context): ComponentName =
        ComponentName(context.packageName, MAIN_ACTIVITY)

    fun isIconHidden(context: Context): Boolean {
        val state = context.packageManager
            .getComponentEnabledSetting(launcherComponent(context))
        return state.newEnabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun daemonCmd(sub: String): Shell.Result =
        Shell.cmd("${getKsuDaemonPath()} stealth $sub").exec()

    /** 守卫是否在跑（root 查询；无 root / 老守护返回 false）。 */
    fun watcherRunning(): Boolean = runCatching {
        val result = daemonCmd("status")
        result.isSuccess && result.out.any { it.contains("running (pid") }
    }.getOrDefault(false)

    /** @return 守卫已武装且图标已隐藏（两者都在才算隐身生效）。 */
    fun enable(context: Context): Boolean {
        val comp = launcherComponent(context)
        val arg = "${comp.packageName}/${comp.className}"
        val result = Shell.cmd("${getKsuDaemonPath()} stealth start --component '$arg'").exec()
        if (!result.isSuccess) {
            return false
        }
        // 守卫已武装：现在才藏图标。
        context.packageManager.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        return true
    }

    /** 先恢复图标再停守卫；返回值只代表图标恢复成功（停守护失败不阻塞）。 */
    fun disable(context: Context): Boolean {
        context.packageManager.setComponentEnabledSetting(
            launcherComponent(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Shell.cmd("${getKsuDaemonPath()} stealth stop").submit()
        return true
    }

    /** adb 兜底命令（确认对话框里展示给用户抄）。 */
    fun adbFallback(context: Context): String {
        val comp = launcherComponent(context)
        return "adb shell am start -n ${comp.packageName}/${comp.className}"
    }
}

package me.weishu.kernelsu.ui.navigation3

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.modulerepo.RepoModuleArg
import me.weishu.kernelsu.ui.util.FlashItSerializer
import me.weishu.kernelsu.ui.util.RepoModuleArgSerializer
import me.weishu.kernelsu.ui.util.TemplateInfoSerializer
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel

/**
 * Type-safe navigation keys for Navigation3.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object SuperUser : Route

    @Parcelize
    @Serializable
    data object Module : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object About : Route

    @Parcelize
    @Serializable
    data object Sulog : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    /**
     * 自定义背景：本机私有目录里的浅/深两档壁纸。
     *
     * 单独一条路由而不是塞进调色屏，是因为这两件事的"生效面"不同——主题改的是玻璃令牌，
     * 背景改的是全应用玻璃的折射源，且要跑相册选图 + 重编码，塞在一起会让调色屏
     * 背一个它不需要的 ActivityResult 生命周期。
     */
    @Parcelize
    @Serializable
    data object Wallpaper : Route

    @Parcelize
    @Serializable
    data object AppProfileTemplate : Route

    @Parcelize
    @Serializable
    data class TemplateEditor(
        @Serializable(with = TemplateInfoSerializer::class) val template: TemplateViewModel.TemplateInfo,
        val readOnly: Boolean
    ) : Route

    @Parcelize
    @Serializable
    data class AppProfile(val uid: Int) : Route

    @Parcelize
    @Serializable
    data object Install : Route

    @Parcelize
    @Serializable
    data class ModuleRepoDetail(@Serializable(with = RepoModuleArgSerializer::class) val module: RepoModuleArg) : Route

    @Parcelize
    @Serializable
    data object ModuleRepo : Route

    @Parcelize
    @Serializable
    data class Flash(@Serializable(with = FlashItSerializer::class) val flashIt: FlashIt) : Route

    @Parcelize
    @Serializable
    data class ExecuteModuleAction(val moduleId: String, val fromShortcut: Boolean = false) : Route

    @Parcelize
    @Serializable
    data object HideEnvList : Route

    @Parcelize
    @Serializable
    data object OneTapHide : Route

    @Parcelize
    @Serializable
    data object RemoteAssistant : Route

    /** KPM 管理：原底栏第 4 页，现从模块页入口卡进入（底栏 7 页收敛为 5 页）。 */
    @Parcelize
    @Serializable
    data object Kpm : Route

    /** 终端：原底栏第 7 页，现从设置页入口卡进入（底栏 7 页收敛为 5 页）。 */
    @Parcelize
    @Serializable
    data object Terminal : Route
}

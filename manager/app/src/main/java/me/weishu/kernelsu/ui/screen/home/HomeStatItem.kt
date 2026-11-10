package me.weishu.kernelsu.ui.screen.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R

/**
 * 首页数据面板的可选项（第三轮：面板从"写死三项"变成"用户自选"）。
 *
 * ## 为什么只有八个
 *
 * 设计要求给了 11 个候选，这里只收 8 个 —— 剩下三个**当前代码根本拿不到准确值**，
 * 放进来就只能是编的：
 *
 * | 候选 | 为什么不收 |
 * |---|---|
 * | 构建编号 | 仓库里没有"构建编号"这个概念：`VERSION_CODE == BASE_VERSION_CODE`（`manager/build.gradle.kts:30-34`），真正的内部号只存在于 CI tag `v<code>-<sha>`（`.github/workflows/build-xecpro.yml:906-907`），**不进 APK**。任何包装成"版本号"的展示都是造假。 |
 * | 系统运行时间 | 取数免费（`SystemClock.elapsedRealtime()`），但要新建一个每秒 ticker 才能自己走；全仓唯一同类先例是 `OneTapHideScreen.kt:225-234` 的 `delay(1_000)`。收益不抵风险，留到下一轮。 |
 * | 内存使用 | **全仓零封装**（`MemoryInfo` / `/proc/meminfo` / `availMem` 零命中），口径（系统级 vs 本进程）也没定。要新增探测，不做。 |
 *
 * ## id 不是 ordinal
 *
 * 持久化用 [id]（枚举名小写），不用 `ordinal`：枚举中间插一项就会把所有人的
 * 配置读错位，而这种错是静默的 —— 用户只会看到"面板莫名其妙变了"。
 */
enum class HomeStatItem(@param:StringRes val label: Int) {
    /** 三段式内核版本（`6.12.23`），与设备表同一口径。 */
    KERNEL_VERSION(R.string.home_stat_kernel),

    /** 管理器版本号（`31425`）。 */
    MANAGER_VERSION(R.string.home_stat_manager),

    /** 已授权应用数（内核 allowlist 里的 UID 数）。 */
    SUPERUSER(R.string.home_stat_superuser),

    /** `/data/adb/modules` 里已安装的模块数（含待卸载）。 */
    MODULE_TOTAL(R.string.home_stat_module_total),

    /** 其中处于启用状态的模块数。 */
    MODULE_ENABLED(R.string.home_stat_module_enabled),

    /** 当前 SELinux 策略档。 */
    SELINUX(R.string.home_selinux_status),

    /** 内核 SUSFS 版本。 */
    SUSFS(R.string.home_susfs_status),

    /** Seccomp 过滤模式。 */
    SECCOMP(R.string.home_seccomp_status),
    ;

    /** 持久化 id：稳定、可读、与枚举顺序无关。 */
    val id: String get() = name.lowercase()

    companion object {
        val ALL: List<HomeStatItem> = entries.toList()

        /** 解析一个 id；未知 id 返回 null（调用方负责兜底，不要抛）。 */
        fun fromId(id: String): HomeStatItem? = entries.firstOrNull { it.id == id.trim() }
    }
}

/** 取不到值时的占位。**不能画成 0** —— "0 个授权"和"问不到"是两件事。 */
const val HOME_STAT_PLACEHOLDER = "—"

/**
 * 一次面板渲染需要的全部输入。
 *
 * 做成一个数据类而不是让 [HomeStatItem.value] 直接读 `HomeUiState`：模块计数不在
 * `HomeUiState` 里（它属于 `ModuleViewModel`），硬塞进去会让首页多背一份模块状态。
 */
@Immutable
data class HomeStatInputs(
    val state: HomeUiState,
    val rootGrantCount: Int,
    /** null = 模块列表还没加载出来（**不是** 0）。 */
    val moduleTotal: Int?,
    val moduleEnabled: Int?,
)

/** 一个格子要画的东西。[numeric] 决定用数字样式还是文字样式。 */
@Immutable
data class HomeStatValue(
    val text: String,
    val numeric: Boolean,
)

/**
 * 把一个项目解析成"这一帧该显示的文本"。
 *
 * 每个分支都必须处理**三态**：有值 / 真的是 0 / 问不到。设计要求里点名过这条，
 * 而现有的三处实现都在违反它（`getSuperuserCount()` 无驱动返 0、`listModules()`
 * 失败返 `[]`、SELinux `Unknown` 被画成绿色）。
 */
@Composable
fun HomeStatItem.value(inputs: HomeStatInputs): HomeStatValue = when (this) {
    HomeStatItem.KERNEL_VERSION -> HomeStatValue(
        // major < 0 是 KernelVersion 的"解析失败"哨兵（Kernels.kt:32-39）。
        text = if (inputs.state.kernelVersion.major >= 0) {
            inputs.state.kernelVersion.toString()
        } else {
            HOME_STAT_PLACEHOLDER
        },
        numeric = true,
    )

    HomeStatItem.MANAGER_VERSION -> HomeStatValue(
        text = inputs.state.currentManagerVersionCode.toString(),
        numeric = true,
    )

    HomeStatItem.SUPERUSER -> HomeStatValue(
        // 驱动不应答时问不到授权数，不能显示 0。
        text = if (inputs.state.ksuVersion == null) HOME_STAT_PLACEHOLDER else inputs.rootGrantCount.toString(),
        numeric = true,
    )

    HomeStatItem.MODULE_TOTAL -> HomeStatValue(
        text = inputs.moduleTotal?.toString() ?: HOME_STAT_PLACEHOLDER,
        numeric = true,
    )

    HomeStatItem.MODULE_ENABLED -> HomeStatValue(
        text = inputs.moduleEnabled?.toString() ?: HOME_STAT_PLACEHOLDER,
        numeric = true,
    )

    HomeStatItem.SELINUX -> HomeStatValue(
        text = selinuxLabel(inputs.state.systemInfo.selinuxStatus),
        numeric = false,
    )

    HomeStatItem.SUSFS -> {
        val version = inputs.state.susfsVersion
        HomeStatValue(
            text = when {
                version == null -> HOME_STAT_PLACEHOLDER
                version == "unsupport" -> stringResource(R.string.home_susfs_unsupported)
                else -> version
            },
            numeric = false,
        )
    }

    HomeStatItem.SECCOMP -> HomeStatValue(
        text = seccompLabel(inputs.state.systemInfo.seccompStatus),
        numeric = false,
    )
}

/** SELinux 四态文案。抽出来是为了让设备表/安全面板/数据面板共用同一份，避免三处漂移。 */
@Composable
fun selinuxLabel(status: String): String = when (status) {
    "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
    "Permissive" -> stringResource(R.string.selinux_status_permissive)
    "Disabled" -> stringResource(R.string.selinux_status_disabled)
    else -> stringResource(R.string.selinux_status_unknown)
}

/**
 * Seccomp 五态文案。
 *
 * `-1` 是 `Os.prctl(21)` **调用失败**（`HomeViewModel.kt:214-216` 的 `getOrDefault(-1)`），
 * 不是"设备不支持 seccomp"—— 后者在 Android 上不存在。以前这里写「不支持」，
 * 属于把"没问到"说成"没有"，按设计要求的红线改成「未知」。
 */
@Composable
fun seccompLabel(status: Int): String = when (status) {
    0 -> stringResource(R.string.seccomp_status_disabled)
    1 -> stringResource(R.string.seccomp_status_strict)
    2 -> stringResource(R.string.seccomp_status_filter)
    else -> stringResource(R.string.seccomp_status_unknown)
}

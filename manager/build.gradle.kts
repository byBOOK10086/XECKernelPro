plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
// 首页判断内置 LKM 是否落后时，必须拿内核的 KERNEL_SU_VERSION（= 31000+提交数）
// 跟这个基准码比；APK 的 versionCode 是它的十倍，属于另一个数域。
extra["managerBaseVersionCode"] = getBaseVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    // `--exclude 'gki-*'`：gki-* 是内核预发布标签（build-susfs-gki.yml 打的），
    // 不是管理器版本。不排除的话最近的那个 GKI 标签会变成 versionName，
    // APK 文件名会成 XECKernelPro_vgki-android16-6.12-<sha>-65-g<sha>_31066-release.apk。
    val process = Runtime.getRuntime()
        .exec(arrayOf("git", "describe", "--tags", "--always", "--exclude", "gki-*"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    // 版本码就是「31000 + 提交数」，**不乘任何系数**：这是对外公开、用户能在关于页
    // 和更新日志里看到的号，必须和历史上发出去的那条序列连贯（v31248 → v31277 → …）。
    // 曾经为了给 debug 变体留个 +1 而整体乘十（得到 312770 这种六位数），用户看到
    // 就是"版本号乱跳"，已被要求改回。
    return getBaseVersionCode()
}

/**
 * 与内核 `KERNEL_SU_VERSION` 同一口径的版本码（`31000 + 提交数`，见 kernel/Kbuild）。
 *
 * 首页用「内核上报的版本 == 管理器版本」来判断内置 LKM 是否落后，所以这两边必须
 * 永远相等；现在 APK 的 versionCode 也取这个值，三项天然同源。**不要再给其中任何
 * 一项加偏移或乘系数**——之前乘十导致过两个问题：更新提示永远清不掉、公开发版号
 * 变成六位数。
 */
fun getBaseVersionCode(): Int {
    val commitCount = getGitCommitCount()
    // 30000 基数随旧仓库（byBOOK10086/xecpro）废弃：新仓库提交数从 1 重新起算，
    // 基数改为 31000 以保证首版 v31001 大于旧仓库最后一个版本 v30153。
    return 31000 + commitCount
}

fun getVersionName(): String {
    // 必须带 v 前缀，让 APK 文件名形如 XECKernelPro_v<ver>_<code>-release.apk，
    // 以同时兼容旧版 checkNewVersion 的正则 v(.+?)_(\d+)- 与新版 _( \d+)-release.apk 两种解析。
    val desc = getGitDescribe()
    return if (desc.startsWith("v")) desc else "v$desc"
}

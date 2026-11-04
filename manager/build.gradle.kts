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
// 首页判断内置 LKM 是否落后时，拿内核的 KERNEL_SU_VERSION 跟这个基准码比。
// 两者现在取自同一个文件（仓库根 version.properties），是同一个数字，不再是两个数域。
extra["managerBaseVersionCode"] = getBaseVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitDescribe(): String {
    // `--exclude 'gki-*'`：gki-* 是内核预发布标签（build-susfs-gki.yml 打的），
    // 不是管理器版本。不排除的话最近的那个 GKI 标签会变成 versionName，
    // APK 文件名会成 XECKernelPro_vgki-android16-6.12-<sha>-65-g<sha>_31066-release.apk。
    val process = Runtime.getRuntime()
        .exec(arrayOf("git", "describe", "--tags", "--always", "--exclude", "gki-*"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    // 公开版本码，**不乘任何系数、不加偏移**：这是用户在关于页和更新日志里看到的号，
    // 必须和历史上发出去的那条序列连贯（… → v31261 → v31346 → v31347）。
    return getBaseVersionCode()
}

/**
 * 公开版本码的唯一真源：仓库根的 `version.properties`。
 *
 * 内核 `KERNEL_SU_VERSION`（kernel/Kbuild）、CI 的 release tag（build-xecpro.yml）
 * 和 APK 的 versionCode 都读这一个文件，所以三项天然相等。
 *
 * **不要改回从 `git rev-list --count HEAD` 推导。** 提交数会随分支、合并和 CI 的
 * clone 深度变化，这个思路已经造成过两次对外事故：
 *   1) 给 debug 变体留 +1 而整体乘十 → 公开号变成 312770 六位数，内核报 31277、
 *      管理器报 312770，首页「LKM 可更新」提示永远清不掉；
 *   2) 31000 + 提交数，从 kpm-integration(346 提交) 切回 fresh-main(279 提交)
 *      → 31346 倒退成 31279，已安装 31346 的用户永远收不到更新。
 * 改动前先跑 scripts/check_version_monotonic.py。
 */
fun getBaseVersionCode(): Int {
    // manager/ 自己就是一个 Gradle root（settings.gradle.kts 在它下面），所以
    // rootProject.file("version.properties") 会指向 manager/version.properties。
    // 两个位置都认一下：CI 用 ./manager 作工作目录，本地直接开 manager 也一样。
    val candidates = listOf(
        rootProject.file("version.properties"),
        rootProject.file("../version.properties"),
    )
    val file = candidates.firstOrNull { it.isFile }
        ?: error("缺少 version.properties（找过 ${candidates.joinToString(" 和 ") { it.path }}）：公开版本码必须有唯一真源，不能从提交数推导")
    val line = file.readLines().firstOrNull { it.trimStart().startsWith("versionCode") }
        ?: error("${file.path} 里没有 versionCode 这一行")
    return line.substringAfter('=').trim().toIntOrNull()
        ?: error("${file.path} 的 versionCode 不是整数：$line")
}

fun getVersionName(): String {
    // 必须带 v 前缀，让 APK 文件名形如 XECKernelPro_v<ver>_<code>-release.apk，
    // 以同时兼容旧版 checkNewVersion 的正则 v(.+?)_(\d+)- 与新版 _( \d+)-release.apk 两种解析。
    val desc = getGitDescribe()
    return if (desc.startsWith("v")) desc else "v$desc"
}

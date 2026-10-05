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
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
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

package me.weishu.kernelsu.privilege

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GhostLock profile 离线生成器（②）。
 *
 * 诚实边界（这是全部设计的前提）：
 *  - 本生成器**不能凭空算出偏移**——结构布局是编译期绑定的事实，只有上游
 *    为该 release 实测发布的 .conf 才是「已验证」。任何声称无中生有的
 *    生成器都是糊弄。
 *  - 它能诚实做的是**同 KMI 近邻克隆**：GKI 的 KMI 稳定契约保证同一
 *    androidXX-<kmi> 代内结构布局不变（这正是 KMI 存在的意义），所以
 *    6.1.140-android14-11 的设备克隆 6.1.138-android14-11 的已发布
 *    profile、只重绑 release，是「大概率正确但未经本机验证」的推断。
 *  - 推断就是推断：派生头注释写明来源与风险，跑错可以内核 panic，外部
 *    救援通道（fastboot/Recovery）必须保留。
 *  - 近邻都不存在时退到 major.minor 模板（全 null），导入后 profile
 *    校验会 fail closed 拒绝执行，直到人工填完字段——这是编辑起点，
 *    不是可用 profile。
 */
object ProfileGenerator {

    sealed class GenResult {
        data class AlreadySupported(val release: String) : GenResult()
        data class Generated(val release: String, val donorRelease: String, val importedName: String) : GenResult()
        data class TemplateBound(val release: String, val template: String, val importedName: String) : GenResult()
        data class NoMatch(val release: String, val reason: String) : GenResult()
    }

    private data class KernelId(val major: Int, val minor: Int, val patch: Int, val android: Int?, val kmi: Int?) {
        val versionPrefix get() = "$major.$minor"
    }

    // 6.1.138-android14-11-g0c3d559bcd85-ab14529422 → (6, 1, 138, 14, 11)
    private val ID_RE = Regex(
        """^(\d+)\.(\d+)(?:\.(\d+))?(?:-android(\d+))?(?:-(\d+))?"""
    )

    private fun parseId(release: String): KernelId? {
        val m = ID_RE.find(release) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        if (major < 3 || major > 99 || minor < 0) return null
        return KernelId(
            major = major,
            minor = minor,
            patch = m.groupValues[3].toIntOrNull() ?: -1,
            android = m.groupValues[4].toIntOrNull(),
            kmi = m.groupValues[5].toIntOrNull(),
        )
    }

    suspend fun generate(runner: GhostLockRunner, context: Context): GenResult =
        withContext(Dispatchers.IO) {
            val release = runner.kernelRelease()
            if (release.isBlank() || release == "unknown") {
                return@withContext GenResult.NoMatch(release, "kernel release unavailable")
            }

            // 已有可用 profile 就不动（幂等；精确匹配层已覆盖）。
            val snapshot = runner.snapshot()
            if (snapshot.supported) {
                return@withContext GenResult.AlreadySupported(release)
            }

            val device = parseId(release)
                ?: return@withContext GenResult.NoMatch(release, "unparsable release")

            // 内置目录 + index.conf 全量解析（assets/kernel_profiles/index.conf）。
            val entries = runCatching {
                context.assets.open("kernel_profiles/index.conf").bufferedReader().use { r ->
                    INDEX_RE.findAll(r.readText()).mapNotNull { m ->
                        val rel = m.groupValues[1]
                        val file = m.groupValues[2]
                        if (rel.isNotEmpty() && file.isNotEmpty()) rel to file else null
                    }.toList()
                }
            }.getOrDefault(emptyList())
            if (entries.isEmpty()) {
                return@withContext GenResult.NoMatch(release, "empty profile index")
            }

            // 同 KMI 近邻：major.minor 相同 + androidXX 相同 + kmi 子代号相同，
            // 取 patch 版本距离最小者；模板与 maybe-dirty 降权。
            data class Candidate(val release: String, val file: String, val distance: Int, val penalty: Int)

            val candidates = entries.mapNotNull { (rel, file) ->
                if (rel == release) return@mapNotNull null
                val id = parseId(rel) ?: return@mapNotNull null
                if (id.major != device.major || id.minor != device.minor) return@mapNotNull null
                if (id.android != device.android) return@mapNotNull null
                var penalty = 0
                if (id.kmi != device.kmi) penalty += 1000 // 跨 KMI 子代：布局可能重排
                if ("template" in rel || "template" in file) penalty += 2000
                if ("maybe-dirty" in rel) penalty += 100
                if (id.patch < 0 || device.patch < 0) penalty += 500
                val distance = if (id.patch >= 0 && device.patch >= 0) {
                    kotlin.math.abs(id.patch - device.patch)
                } else 9999
                Candidate(rel, file, distance, penalty)
            }.sortedWith(compareBy({ it.penalty }, { it.distance }))

            val donor = candidates.firstOrNull { it.penalty < 1000 }
            if (donor != null) {
                val text = runCatching {
                    context.assets.open("kernel_profiles/${donor.file}").bufferedReader().use { it.readText() }
                }.getOrNull() ?: return@withContext GenResult.NoMatch(release, "cannot read ${donor.file}")

                val derived = buildString {
                    append("# XECKernelPro generated profile - do not edit this header.\n")
                    append("# device release: $release\n")
                    append("# derived from: ${donor.file} (${donor.release})\n")
                    append("# generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                    append("#\n")
                    append("# WARNING: offsets are INHERITED from the same-KMI neighbor above.\n")
                    append("# GKI KMI stability implies identical struct layouts, but this file is\n")
                    append("# NOT device-verified; a wrong offset can panic the kernel. Keep an\n")
                    append("# external rescue path (fastboot / recovery) available.\n")
                    append(text)
                }.replaceFirst(RELEASE_RE, "release = \"$release\"")

                val name = runner.importProfileText("generated-$release.conf", derived)
                return@withContext GenResult.Generated(release, donor.release, name)
            }

            // 近邻不存在：退到 major.minor 模板（全 null → 运行时 fail closed）。
            val template = entries.firstOrNull { (rel, _) ->
                rel == "${device.versionPrefix}-template"
            } ?: entries.firstOrNull { (rel, _) ->
                parseId(rel)?.let { it.major == device.major && it.minor == device.minor && "template" in rel } == true
            }
            if (template != null) {
                val text = runCatching {
                    context.assets.open("kernel_profiles/${template.second}").bufferedReader().use { it.readText() }
                }.getOrNull() ?: return@withContext GenResult.NoMatch(release, "cannot read ${template.second}")

                val derived = buildString {
                    append("# XECKernelPro generated profile (TEMPLATE-BOUND, fields unfilled).\n")
                    append("# device release: $release\n")
                    append("# bound from: ${template.second}\n")
                    append("#\n")
                    append("# WARNING: every field is null. The runner will refuse to execute\n")
                    append("# until all geometry/offset/route fields are filled in manually.\n")
                    append(text)
                }.replaceFirst(RELEASE_RE, "release = \"$release\"")

                val name = runner.importProfileText("generated-$release.conf", derived)
                return@withContext GenResult.TemplateBound(release, template.first, name)
            }

            GenResult.NoMatch(release, "no ${device.versionPrefix} profile or template in library")
        }

    private companion object {
        // index.conf 行：{ release = "...", file = "..." }
        // 普通转义字符串而不是 raw string：正则尾巴的 `"` 会与收尾 `"""` 粘连。
        val INDEX_RE =
            Regex("release\\s*=\\s*\"([^\"]+)\"\\s*,\\s*file\\s*=\\s*\"([^\"]+)\"")
        val RELEASE_RE = Regex("(?m)^release\\s*=\\s*\"[^\"]*\"")
    }
}

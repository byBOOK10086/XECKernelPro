package me.weishu.kernelsu.privilege

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GhostLock 内核 profile 自动更新器（②）。
 *
 * 数据源 = 上游官方 release（Apache-2.0 项目，资产公开）：
 *  - GhostLock-kernel-profiles.zip（全部 .conf）
 *  - sha256sum.txt（逐文件摘要——先下摘要、再下 zip、逐条校验后才入库）
 *
 * 诚实边界：
 *  - 这里只做「库更新」——把上游已发布的 profile 全量导入本地 store（含
 *    新内核），不能凭空生成未发布的 profile；设备 OTA 换内核后若上游尚未
 *    收录，仍走手动导入路径。
 *  - 导入全部文件而不是只挑当前内核：store 按 release 精确匹配，多余条目
 *    不影响判定，且下次换机/换内核零操作。
 */
object ProfileUpdater {

    private const val BASE =
        "https://github.com/YuKongA/ghostlock-app/releases/download/pre-release"
    private const val ZIP_NAME = "GhostLock-kernel-profiles.zip"
    private const val SUM_NAME = "sha256sum.txt"

    data class Result(val imported: Int, val skipped: Int, val error: String?)

    suspend fun update(runner: GhostLockRunner, onLog: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                onLog("[*] fetching $SUM_NAME ...")
                val sums = downloadText(client, "$BASE/$SUM_NAME")
                    ?: return@withContext Result(0, 0, "cannot fetch $SUM_NAME")
                // sha256sum.txt 行格式：<hex>  <name>
                val sumMap = sums.lineSequence()
                    .mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2) parts[1].trim() to parts[0].lowercase() else null
                    }
                    .toMap()
                if (sumMap.isEmpty()) {
                    return@withContext Result(0, 0, "$SUM_NAME has no entries")
                }

                onLog("[*] fetching $ZIP_NAME ...")
                val zipBytes = downloadBytes(client, "$BASE/$ZIP_NAME")
                    ?: return@withContext Result(0, 0, "cannot fetch $ZIP_NAME")

                var imported = 0
                var skipped = 0
                ZipInputStream(zipBytes.inputStream()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory || !name.endsWith(".conf")) {
                            zis.closeEntry()
                            continue
                        }
                        val bytes = zis.readBytes()
                        zis.closeEntry()

                        // 摘要逐条校验；摘要表里没有的条目不导入（fail closed）。
                        val want = sumMap[entry.name.substringAfterLast('/')]
                            ?: sumMap[entry.name]
                        val got = sha256(bytes)
                        if (want == null) {
                            onLog("[!] no checksum for ${name}; skipped")
                            skipped++
                            continue
                        }
                        if (got != want) {
                            onLog("[!] checksum mismatch for $name; skipped")
                            skipped++
                            continue
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        runner.importProfileText("auto-$name", text)
                        imported++
                        onLog("[+] imported $name")
                    }
                }
                Result(imported, skipped, null)
            } catch (e: Exception) {
                Result(0, 0, "${e::class.simpleName}: ${e.message}")
            }
        }

    private fun downloadBytes(client: OkHttpClient, url: String): ByteArray? {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) return null
            return it.body?.bytes()
        }
    }

    private fun downloadText(client: OkHttpClient, url: String): String? =
        downloadBytes(client, url)?.toString(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

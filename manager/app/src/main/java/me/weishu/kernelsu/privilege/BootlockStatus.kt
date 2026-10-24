package me.weishu.kernelsu.privilege

import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bootloader 状态检测 + 解锁通道情报（⑧ 解锁情报）。
 *
 * 为什么存在：持久 root（写 boot/init_boot）必须 bootloader 已解锁——锁着写
 * 验证链内分区 = AVB 验签失败 = 变砖。所以「一键永久」入口必须先过这个闸门，
 * 同时把「这台设备能不能解锁 / 怎么解 / 代价是什么」如实告诉用户。
 *
 * 检测用三条只读 prop（任何 app 都能读，无需 root）：
 *  - ro.boot.flash.locked（1=锁定）
 *  - ro.boot.verifiedbootstate（green=锁定验证通过 / orange=解锁）
 *  - ro.boot.vbmeta.device_state（locked/unlocked）
 * 三条交叉判定：device_state == "unlocked" 或 flash.locked == "0" → 解锁；
 * 任一命中锁定信号 → 锁定；全空 → 未知（按锁定处理，安全侧倒）。
 */
object BootlockStatus {

    data class State(
        /** null = 读不到（按锁定处理）。false = 已解锁。 */
        val locked: Boolean?,
        /** ro.boot.verifiedbootstate 原值（green/orange/yellow/red）。 */
        val verifiedBootState: String?,
        /** 结论来自哪条 prop，给 UI 与日志一个可解释的来源。 */
        val source: String,
    )

    fun detectSync(): State {
        val flashLocked = getprop("ro.boot.flash.locked")
        val vbs = getprop("ro.boot.verifiedbootstate")
        val deviceState = getprop("ro.boot.vbmeta.device_state")

        val unlocked = deviceState.equals("unlocked", ignoreCase = true) ||
            flashLocked == "0" ||
            vbs.equals("orange", ignoreCase = true)
        if (unlocked) {
            val src = when {
                deviceState.equals("unlocked", ignoreCase = true) ->
                    "ro.boot.vbmeta.device_state=unlocked"
                flashLocked == "0" -> "ro.boot.flash.locked=0"
                else -> "verifiedbootstate=orange"
            }
            return State(locked = false, verifiedBootState = vbs.ifBlank { null }, source = src)
        }

        val lockedSignal = flashLocked == "1" ||
            vbs.equals("green", ignoreCase = true) ||
            deviceState.equals("locked", ignoreCase = true)
        if (lockedSignal) {
            val src = when {
                flashLocked == "1" -> "ro.boot.flash.locked=1"
                vbs.equals("green", true) -> "verifiedbootstate=green"
                else -> "vbmeta.device_state=locked"
            }
            return State(locked = true, verifiedBootState = vbs.ifBlank { null }, source = src)
        }

        return State(locked = null, verifiedBootState = vbs.ifBlank { null }, source = "no boot props")
    }

    suspend fun detect(): State = withContext(Dispatchers.IO) { detectSync() }

    private fun getprop(name: String): String = try {
        ProcessBuilder("getprop", name)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().use { it.readText().trim() }
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 机型 → 官方解锁通道情报。
 *
 * 原则（不糊弄）：
 *  - 只收录官方 / 厂商公开的通道；第三方付费解锁、漏洞强解一概不写——
 *    现代（2019+）BL 锁在 RPMB/eFuse/服务器签名层，kernel root 摸不到，
 *    任何"root 一键强解"都是假承诺。
 *  - 代价如实写：数据清除、等待期、Knox 熔丝等。
 *  - [Channel.url] 给官方入口；没有公开通道的机型直接说"无公开通道"。
 */
object UnlockChannels {

    enum class Availability { OPEN, LIMITED, CLOSED }

    data class Channel(
        val availability: Availability,
        /** 官方/半官方入口链接；null = 没有可给的链接。 */
        val url: String?,
    ) {
        companion object {
            val XIAOMI = Channel(
                Availability.LIMITED,
                "https://www.miui.com/unlock/",
            )
            val ONEPLUS = Channel(Availability.OPEN, "https://www.oneplus.com/support")
            val OPPO = Channel(Availability.LIMITED, "https://www.oppo.com/support/")
            val REALME = Channel(Availability.LIMITED, "https://www.realme.com/support/")
            val SAMSUNG = Channel(
                Availability.OPEN,
                "https://www.samsung.com/us/support/",
            )
            val GOOGLE = Channel(
                Availability.OPEN,
                "https://source.android.com/docs/core/architecture/bootloader/locking_unlocking",
            )
            val HUAWEI = Channel(Availability.CLOSED, null)
            val HONOR = Channel(Availability.LIMITED, "https://www.hihonor.com/support/")
            val VIVO = Channel(Availability.CLOSED, null)
            val IQOO = Channel(Availability.CLOSED, null)
            val MOTOROLA = Channel(Availability.OPEN, "https://www.motorola.com/support")
            val SONY = Channel(Availability.OPEN, "https://www.sonymobile.com/support/")
            val NOTHING = Channel(Availability.OPEN, "https://nothing.tech/support")
            val UNKNOWN = Channel(Availability.LIMITED, null)
        }
    }

    /** 按 Build.MANUFACTURER 匹配通道。 */
    fun forDevice(): Channel {
        val brand = (Build.MANUFACTURER ?: "").trim().lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                Channel.XIAOMI
            brand.contains("oneplus") -> Channel.ONEPLUS
            brand.contains("oppo") -> Channel.OPPO
            brand.contains("realme") -> Channel.REALME
            brand.contains("samsung") -> Channel.SAMSUNG
            brand.contains("google") -> Channel.GOOGLE
            brand.contains("huawei") -> Channel.HUAWEI
            brand.contains("honor") -> Channel.HONOR
            brand.contains("vivo") -> Channel.VIVO
            brand.contains("iqoo") -> Channel.IQOO
            brand.contains("motorola") -> Channel.MOTOROLA
            brand.contains("sony") -> Channel.SONY
            brand.contains("nothing") -> Channel.NOTHING
            else -> Channel.UNKNOWN
        }
    }
}

package me.weishu.kernelsu.ui.screen.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * XEC Hub 后端客户端（账号 + 短码 + 流水账）。
 * 服务端统一返回 {ok, ...}；业务失败 ok=false 且带 error（人话，可直接展示）。
 * 登录态用 Bearer token（服务端 30 天有效），401/未登录类错误由调用方引导去登录页。
 */
object RemoteApi {
    private const val BASE = "http://42.193.123.248:8010"
    private const val TIMEOUT_MS = 20000

    data class Session(val token: String, val username: String, val isAdmin: Boolean)
    data class Redeem(val links: List<String>, val remaining: Int, val expiresAt: Long?, val creator: String)

    private fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val conn = URL("$BASE$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String, token: String): JSONObject {
        val conn = URL("$BASE$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        } ?: throw IOException("HTTP $code")
        if (code !in 200..299 && !json.has("ok")) throw IOException("HTTP $code")
        return json
    }

    fun register(username: String, password: String) {
        val r = post("/api/register", JSONObject().put("username", username).put("password", password))
        if (!r.optBoolean("ok")) throw IOException(r.optString("error", "register failed"))
    }

    fun login(username: String, password: String): Session {
        val r = post("/api/login", JSONObject().put("username", username).put("password", password))
        if (!r.optBoolean("ok")) throw IOException(r.optString("error", "login failed"))
        return Session(r.getString("token"), r.getString("username"), r.optBoolean("is_admin"))
    }

    /** 校验本地缓存的 token 是否仍有效；失效返回 null。 */
    fun me(token: String): Session? = try {
        val r = get("/api/me", token)
        if (r.optBoolean("ok")) Session(token, r.getString("username"), r.optBoolean("is_admin")) else null
    } catch (e: Exception) {
        null
    }

    /** 生成短码；服务端登记链接、次数、有效期并记流水。 */
    fun share(token: String, links: List<String>, maxUses: Int, expiresHours: Int): String {
        val arr = JSONArray()
        links.forEach { arr.put(it) }
        val r = post(
            "/api/share",
            JSONObject().put("links", arr).put("max_uses", maxUses).put("expires_hours", expiresHours),
            token,
        )
        if (!r.optBoolean("ok")) throw IOException(r.optString("error", "share failed"))
        return r.getString("code")
    }

    /** 兑换短码：服务端扣一次次数并记录「谁下载了谁分享的文件」。 */
    fun redeem(token: String, code: String): Redeem {
        val r = post("/api/redeem", JSONObject().put("code", code), token)
        if (!r.optBoolean("ok")) throw IOException(r.optString("error", "redeem failed"))
        val links = mutableListOf<String>()
        val arr = r.optJSONArray("links") ?: JSONArray()
        for (i in 0 until arr.length()) links.add(arr.getString(i))
        return Redeem(
            links,
            r.optInt("remaining", 0),
            if (r.isNull("expires_at")) null else r.optLong("expires_at"),
            r.optString("creator"),
        )
    }
}

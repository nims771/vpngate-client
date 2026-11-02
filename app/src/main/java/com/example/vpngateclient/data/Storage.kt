package com.example.vpngateclient.data

import android.content.Context
import com.example.vpngateclient.vpn.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ---------- Настройки ----------

data class AppSettings(
    val listUrl: String = DEFAULT_URL,
    val pingTimeoutMs: Int = 2000,
    val pingParallel: Int = 16,
    val autoPing: Boolean = true,
    val autoRefresh: Boolean = true,
    val protocol: Protocol = Protocol.AUTO,
    /** Доверять самоподписанным сертификатам SSTP-серверов. */
    val sstpTrustAll: Boolean = false,
) {
    companion object {
        const val DEFAULT_URL = "http://www.vpngate.net/api/iphone/"
    }
}

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        listUrl = sp.getString("listUrl", null)?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_URL,
        pingTimeoutMs = sp.getInt("pingTimeoutMs", 2000),
        pingParallel = sp.getInt("pingParallel", 16),
        autoPing = sp.getBoolean("autoPing", true),
        autoRefresh = sp.getBoolean("autoRefresh", true),
        protocol = runCatching { Protocol.valueOf(sp.getString("protocol", null) ?: "AUTO") }
            .getOrDefault(Protocol.AUTO),
        sstpTrustAll = sp.getBoolean("sstpTrustAll", false),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("listUrl", s.listUrl)
            .putInt("pingTimeoutMs", s.pingTimeoutMs)
            .putInt("pingParallel", s.pingParallel)
            .putBoolean("autoPing", s.autoPing)
            .putBoolean("autoRefresh", s.autoRefresh)
            .putString("protocol", s.protocol.name)
            .putBoolean("sstpTrustAll", s.sstpTrustAll)
            .apply()
    }
}

// ---------- Избранное ----------
// Храним сервер целиком (с конфигом), чтобы избранное работало,
// даже когда сервер пропал из свежего списка.

class FavoritesStore(context: Context) {
    private val file = File(context.filesDir, "favorites.json")

    fun load(): Map<String, VpnServer> = try {
        if (!file.exists()) emptyMap() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length())
                .map { serverFromJson(arr.getJSONObject(it)) }
                .associateBy { it.id }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(map: Map<String, VpnServer>) {
        val arr = JSONArray()
        map.values.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun VpnServer.toJson() = JSONObject().apply {
        put("hostName", hostName); put("ip", ip); put("score", score)
        put("speedBps", speedBps); put("countryLong", countryLong); put("countryShort", countryShort)
        put("sessions", sessions); put("uptimeMs", uptimeMs); put("totalUsers", totalUsers)
        put("totalTraffic", totalTraffic); put("logType", logType); put("operator", operator)
        put("message", message); put("proto", proto); put("port", port); put("config", config)
    }

    private fun serverFromJson(o: JSONObject) = VpnServer(
        hostName = o.getString("hostName"),
        ip = o.optString("ip"),
        score = o.optLong("score"),
        speedBps = o.optLong("speedBps"),
        countryLong = o.optString("countryLong"),
        countryShort = o.optString("countryShort"),
        sessions = o.optInt("sessions"),
        uptimeMs = o.optLong("uptimeMs"),
        totalUsers = o.optLong("totalUsers"),
        totalTraffic = o.optLong("totalTraffic"),
        logType = o.optString("logType"),
        operator = o.optString("operator"),
        message = o.optString("message"),
        proto = o.optString("proto", "udp"),
        port = o.optInt("port", 1194),
        config = o.getString("config"),
    )
}

// ---------- Загрузка списка + кэш ----------

data class CachedList(val servers: List<VpnServer>, val savedAt: Long)

class ServerRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "servers_cache.csv")

    suspend fun loadCache(): CachedList? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        val servers = runCatching { VpnGateParser.parse(cacheFile.readText()) }.getOrNull()
        if (servers.isNullOrEmpty()) null else CachedList(servers, cacheFile.lastModified())
    }

    suspend fun download(url: String): List<VpnServer> = withContext(Dispatchers.IO) {
        val text = httpGet(url)
        val servers = VpnGateParser.parse(text)
        if (servers.isEmpty()) throw IOException("список пуст или формат не распознан")
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(cacheFile)
        servers
    }

    private fun httpGet(start: String): String {
        var url = URL(start.trim())
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false // сами обрабатываем, в т.ч. http -> https
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VpnGateClient/1.0")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("редирект без адреса")
                    url = URL(url, location)
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("сервер ответил HTTP $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("слишком много редиректов")
    }
}

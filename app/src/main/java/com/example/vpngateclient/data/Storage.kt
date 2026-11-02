package com.example.vpngateclient.data

import android.content.Context
import com.example.vpngateclient.vpn.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
    val pingTimeoutMs: Int = 3000,
    val pingParallel: Int = 12,
    val autoPing: Boolean = true,
    val autoRefresh: Boolean = true,
    val protocol: Protocol = Protocol.SSTP,
    /** Доверять самоподписанным сертификатам SSTP-серверов. */
    val sstpTrustAll: Boolean = false,
    /** Отправлять имя сервера (SNI) при SSTP. Выключено: фильтры видят только IP. */
    val sstpSendSni: Boolean = false,
    /** Подключаться автоматически при запуске приложения. */
    val autoConnect: Boolean = false,
    /** Сколько серверов перебирать при автовыборе, если подключение не удалось. */
    val maxAttempts: Int = 5,
    /** MTU туннеля SSTP. */
    val sstpMtu: Int = 1400,
) {
    companion object {
        const val DEFAULT_URL = "http://www.vpngate.net/api/iphone/"
    }
}

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        listUrl = sp.getString("listUrl", null)?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_URL,
        pingTimeoutMs = sp.getInt("pingTimeout3", 3000),
        pingParallel = sp.getInt("pingParallel3", 12),
        autoPing = sp.getBoolean("autoPing", true),
        autoRefresh = sp.getBoolean("autoRefresh", true),
        // ключ сменён в 3.1: по умолчанию теперь SSTP (OpenVPN часто блокируется)
        protocol = runCatching { Protocol.valueOf(sp.getString("protocol3", null) ?: "SSTP") }
            .getOrDefault(Protocol.SSTP),
        sstpTrustAll = sp.getBoolean("sstpTrustAll", false),
        sstpSendSni = sp.getBoolean("sstpSendSni", false),
        autoConnect = sp.getBoolean("autoConnect", false),
        maxAttempts = sp.getInt("maxAttempts", 5),
        sstpMtu = sp.getInt("sstpMtu", 1400),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("listUrl", s.listUrl)
            .putInt("pingTimeout3", s.pingTimeoutMs)
            .putInt("pingParallel3", s.pingParallel)
            .putBoolean("autoPing", s.autoPing)
            .putBoolean("autoRefresh", s.autoRefresh)
            .putString("protocol3", s.protocol.name)
            .putBoolean("sstpTrustAll", s.sstpTrustAll)
            .putBoolean("sstpSendSni", s.sstpSendSni)
            .putBoolean("autoConnect", s.autoConnect)
            .putInt("maxAttempts", s.maxAttempts)
            .putInt("sstpMtu", s.sstpMtu)
            .apply()
    }
}

// ---------- Выбор сервера ----------

/** Что выбрано для подключения: автовыбор, страна или конкретный сервер. */
sealed interface Selection {
    data object Auto : Selection
    data class Country(val code: String) : Selection
    data class Server(val id: String) : Selection

    fun encode(): String = when (this) {
        Auto -> "auto"
        is Country -> "country:$code"
        is Server -> "server:$id"
    }

    companion object {
        fun decode(s: String?): Selection = when {
            s == null -> Auto
            s.startsWith("country:") -> Country(s.removePrefix("country:"))
            s.startsWith("server:") -> Server(s.removePrefix("server:"))
            else -> Auto
        }
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
        sstpPortHint?.let { put("sstpPort", it) }
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
        sstpPortHint = if (o.has("sstpPort")) o.optInt("sstpPort") else null,
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

    /** Результат загрузки: список и адрес, с которого он реально получен. */
    data class Downloaded(val servers: List<VpnServer>, val url: String)

    /**
     * Пробует адреса по очереди. На каждый — не больше [perUrlTimeoutMs] в сумме
     * (DNS + соединение + чтение): заблокированный адрес больше не «подвешивает» обновление.
     */
    suspend fun download(urls: List<String>, perUrlTimeoutMs: Long = 35_000): Downloaded {
        val errors = mutableListOf<String>()
        for (url in urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            try {
                val text = fetchWithDeadline(url, perUrlTimeoutMs)
                val servers = VpnGateParser.parse(text)
                if (servers.isEmpty()) throw IOException("список пуст или формат не распознан")
                withContext(Dispatchers.IO) {
                    val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                    tmp.writeText(text)
                    tmp.renameTo(cacheFile)
                }
                return Downloaded(servers, url)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${shortUrl(url)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; ").ifEmpty { "адрес списка не задан" })
    }

    private fun shortUrl(url: String) = runCatching { URL(url).host }.getOrDefault(url)

    /**
     * Сам запрос идёт в отдельной задаче; если общий срок вышел — бросаем её
     * и закрываем соединение. Блокирующий DNS/сокет Android иначе не прервать.
     */
    private suspend fun fetchWithDeadline(url: String, timeoutMs: Long): String = coroutineScope {
        val holder = arrayOfNulls<HttpURLConnection>(1)
        val job = async(Dispatchers.IO) { httpGet(url, holder) }
        try {
            withTimeoutOrNull(timeoutMs) { job.await() }
                ?: throw IOException("нет ответа за ${timeoutMs / 1000} с (адрес заблокирован или недоступен)")
        } finally {
            if (!job.isCompleted) {
                runCatching { holder[0]?.disconnect() }
                job.cancel()
            }
        }
    }

    private fun httpGet(start: String, holder: Array<HttpURLConnection?>): String {
        var url = URL(start)
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = false // сами обрабатываем, в т.ч. http -> https
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VpnGateClient/1.0")
                setRequestProperty("Cache-Control", "no-cache")
            }
            holder[0] = conn
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

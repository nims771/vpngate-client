package com.example.vpngateclient.data

import com.example.vpngateclient.vpn.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/** Хранилище настроек (на Android — SharedPreferences, на Windows — файл). */
interface SettingsPersistence {
    fun load(): AppSettings
    fun save(s: AppSettings)
}

/** Простое хранилище строк «ключ → значение». */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
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

class FavoritesStore(dir: File) {
    private val file = File(dir.apply { mkdirs() }, "favorites.json")

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

class ServerRepository(dir: File) {
    private val cacheFile = File(dir.apply { mkdirs() }, "servers_cache.csv")

    suspend fun loadCache(): CachedList? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        val servers = runCatching { VpnGateParser.parse(cacheFile.readText()) }.getOrNull()
        if (servers.isNullOrEmpty()) null else CachedList(servers, cacheFile.lastModified())
    }

    /** Результат загрузки: список и адрес, с которого он реально получен. */
    data class Downloaded(val servers: List<VpnServer>, val url: String)

    /**
     * Запросы идут в отдельной области корутин, не привязанной к вызывающему:
     * на Android блокирующий DNS-запрос и установку соединения прервать нельзя,
     * поэтому по истечении срока мы просто перестаём ждать (поток доработает сам и закроется).
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Attempt(val url: String) {
        val conn = arrayOfNulls<HttpURLConnection>(1)
        /** Когда последний раз пришли данные — пока данные идут, ждём (медленная сеть). */
        @Volatile var lastProgress = System.currentTimeMillis()
        @Volatile var bytes = 0L
        lateinit var result: Deferred<Result<Pair<String, List<VpnServer>>>>
    }

    /**
     * Загружает список сразу со всех адресов параллельно и берёт ответ
     * по приоритету (первый адрес — главный).
     * Сдаёмся, если [stallMs] ни один адрес не прислал ни байта (или прошло [maxMs] всего):
     * в медленной сети список качается долго, но идёт — такую загрузку не обрываем.
     */
    suspend fun download(urls: List<String>, stallMs: Long = 25_000, maxMs: Long = 150_000): Downloaded {
        val list = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) throw IOException("адрес списка не задан")

        val attempts = list.map { url ->
            Attempt(url).also { a ->
                a.result = ioScope.async {
                    runCatching {
                        val text = httpGet(url, a)
                        val servers = VpnGateParser.parse(text)
                        if (servers.isEmpty()) throw IOException("список пуст или формат не распознан")
                        text to servers
                    }
                }
            }
        }

        val winner = try {
            withTimeoutOrNull(maxMs) { awaitWinner(attempts, stallMs) }
        } finally {
            // всё, что ещё висит, бросаем и закрываем — не дожидаясь
            attempts.filter { !it.result.isCompleted }.forEach {
                runCatching { it.conn[0]?.disconnect() }
                it.result.cancel()
            }
        }

        if (winner != null) {
            val (text, servers) = winner.result.await().getOrThrow()
            withContext(Dispatchers.IO) {
                val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(cacheFile)
            }
            return Downloaded(servers, winner.url)
        }

        val reasons = attempts.map { a ->
            val r = if (a.result.isCompleted && !a.result.isCancelled) a.result.await() else null
            val why = r?.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                ?: if (a.bytes > 0) "загрузка прервалась (получено ${a.bytes / 1024} КБ)" else "нет ответа"
            "${shortUrl(a.url)}: $why"
        }
        throw IOException(reasons.joinToString("; "))
    }

    /** Первый успешный по приоритету; запасной берём, если главный не ответил за 6 с. */
    private suspend fun awaitWinner(attempts: List<Attempt>, stallMs: Long): Attempt? {
        val start = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val stalled = attempts.filter { !it.result.isCompleted }.all { now - it.lastProgress > stallMs }
            if (stalled && attempts.none { it.result.isCompleted && it.result.await().isSuccess }) return null
            for (a in attempts) {
                if (!a.result.isCompleted) {
                    if (System.currentTimeMillis() - start < PRIMARY_GRACE_MS) break else continue
                }
                if (a.result.await().isSuccess) return a
            }
            if (attempts.all { it.result.isCompleted }) return null
            delay(150)
        }
    }

    private fun shortUrl(url: String) = runCatching { URL(url).host }.getOrDefault(url)

    private companion object {
        const val PRIMARY_GRACE_MS = 6_000L
    }

    /** Отмечает, что данные идут. */
    private class ProgressStream(private val inner: java.io.InputStream, private val a: Attempt) :
        java.io.FilterInputStream(inner) {
        override fun read(): Int = inner.read().also { if (it >= 0) progress(1L) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) progress(it.toLong()) }
        private fun progress(n: Long) {
            a.bytes += n
            a.lastProgress = System.currentTimeMillis()
        }
    }

    private fun httpGet(start: String, attempt: Attempt): String {
        val holder = attempt.conn
        var url = URL(start)
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = false // сами обрабатываем, в т.ч. http -> https
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VpnGateClient/1.0")
                setRequestProperty("Cache-Control", "no-cache")
                // список ~0,5 МБ, сжатый — в 3–4 раза меньше (важно в медленной сети)
                setRequestProperty("Accept-Encoding", "gzip")
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
                attempt.lastProgress = System.currentTimeMillis()
                var input: java.io.InputStream = ProgressStream(conn.inputStream, attempt)
                if (conn.contentEncoding.equals("gzip", ignoreCase = true)) input = java.util.zip.GZIPInputStream(input)
                return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("слишком много редиректов")
    }
}

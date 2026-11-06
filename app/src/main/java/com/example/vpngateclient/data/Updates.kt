package com.example.vpngateclient.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Найденное обновление. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    val source: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** [prompt] — показать окно «Доступно обновление» (при автоматической проверке). */
    data class Available(val info: UpdateInfo, val prompt: Boolean) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/** Что зависит от платформы: версия, сравнение и установка. */
interface UpdatePlatform {
    /** Ключ в version.json: "android" или "windows". */
    val key: String
    val currentVersion: String
    fun isNewer(entry: JSONObject): Boolean
    fun versionOf(entry: JSONObject): String
    /** Скачивает и запускает установку. Возвращает текст ошибки или null. */
    suspend fun install(info: UpdateInfo, onProgress: (Float) -> Unit): String?
}

/**
 * Обновления берутся из version.json:
 *  1) на вашем домене — рядом со списком серверов (…/proxvpn/version.json, его раскладывает зеркало);
 *  2) на GitHub — в последнем выпуске.
 * Файлы в version.json указаны относительно него самого.
 */
object Updates {
    const val GITHUB = "https://github.com/nims771/vpngate-client/releases/latest/download/version.json"

    fun sources(listUrl: String): List<String> {
        val own = runCatching {
            if (listUrl.isNotBlank() && listUrl != AppSettings.DEFAULT_URL && listUrl.startsWith("http")) {
                URL(URL(listUrl), "proxvpn/version.json").toString()
            } else null
        }.getOrNull()
        return listOfNotNull(own, GITHUB)
    }

    /** null — обновлений нет. Исключение — ни один источник не ответил. */
    fun check(listUrl: String, platform: UpdatePlatform): UpdateInfo? {
        var lastError: Exception? = null
        for (src in sources(listUrl)) {
            try {
                val json = JSONObject(String(get(src, 15_000), Charsets.UTF_8))
                val entry = json.optJSONObject(platform.key) ?: continue
                if (!platform.isNewer(entry)) return null
                val file = entry.getString("file")
                return UpdateInfo(
                    version = platform.versionOf(entry),
                    notes = json.optString("notes"),
                    url = URL(URL(src), file).toString(),
                    size = entry.optLong("size", 0),
                    source = URL(src).host,
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("не удалось проверить обновления: ${lastError?.message ?: "нет ответа"}")
    }

    private fun open(url: String, timeout: Int): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "ProXvpn-updater")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code без адреса")
                c.disconnect()
                current = URL(current, loc)
            } else {
                if (code != 200) {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                return c
            }
        }
        throw IOException("слишком много перенаправлений")
    }

    private fun get(url: String, timeout: Int): ByteArray = open(url, timeout).let { c ->
        try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Скачивает файл (с перенаправлениями GitHub) с прогрессом 0..1. */
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val c = open(url, 30_000)
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    if (total > 0 && done < total) throw IOException("файл скачан не полностью")
                }
            }
        } finally {
            c.disconnect()
        }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("не удалось сохранить файл")
        onProgress(1f)
    }

    /** Сравнение версий вида 4.0.1. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

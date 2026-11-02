package com.example.vpngateclient.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка скорости через текущее подключение: качаем файл ~8 секунд
 * и считаем мегабиты в секунду. Трафик приложения идёт через VPN,
 * поэтому измеряется именно скорость туннеля.
 */
object SpeedTest {

    data class Result(val mbps: Double, val latencyMs: Long?)

    private val SOURCES = listOf(
        "https://speed.cloudflare.com/__down?bytes=60000000",
        "http://speedtest.tele2.net/50MB.zip",
        "http://ipv4.download.thinkbroadband.com/50MB.zip",
    )

    suspend fun run(maxSeconds: Int = 8): Result? = withContext(Dispatchers.IO) {
        val latency = measureLatency()
        for (source in SOURCES) {
            ensureActive()
            val mbps = runCatching { download(source, maxSeconds) }.getOrNull()
            if (mbps != null && mbps > 0) return@withContext Result(mbps, latency)
        }
        null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VpnGateClient")
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Время HTTP-запроса (лучшее из трёх) — задержка через туннель. */
    private fun measureLatency(): Long? {
        var best: Long? = null
        repeat(3) {
            runCatching {
                val start = System.nanoTime()
                val conn = open("http://www.gstatic.com/generate_204")
                conn.responseCode
                conn.disconnect()
                val ms = (System.nanoTime() - start) / 1_000_000
                best = minOf(best ?: ms, ms)
            }
        }
        return best
    }

    private suspend fun download(url: String, maxSeconds: Int): Double? = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            val start = System.nanoTime()
            val limitNs = maxSeconds * 1_000_000_000L
            conn.inputStream.use { input ->
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    if (System.nanoTime() - start > limitNs) break
                }
            }
            val seconds = (System.nanoTime() - start) / 1e9
            if (seconds <= 0 || bytes < 50_000) null else bytes * 8 / seconds / 1e6
        } finally {
            conn.disconnect()
        }
    }
}

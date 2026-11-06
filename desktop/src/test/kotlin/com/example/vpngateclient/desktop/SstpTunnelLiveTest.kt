package com.example.vpngateclient.desktop

import com.example.vpngateclient.data.VpnGateParser
import com.example.vpngateclient.desktop.sstp.SstpTunnel
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Полная проверка на сборщике Windows (с правами администратора): туннель SSTP + Wintun + маршруты,
 * затем запрос в интернет должен уйти через VPN. Запускается с SSTP_TUNNEL_TEST=1.
 */
class SstpTunnelLiveTest {
    private fun trace(): String {
        val c = URL("http://1.1.1.1/cdn-cgi/trace").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        return c.inputStream.bufferedReader().readText().lines().firstOrNull { it.startsWith("ip=") }.orEmpty()
    }

    @Test
    fun fullTunnel() {
        if (System.getenv("SSTP_TUNNEL_TEST") == null) return
        val before = runCatching { trace() }.getOrElse { "?" }
        println("До VPN: $before")
        val c = URL("http://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val servers = VpnGateParser.parse(c.inputStream.bufferedReader().readText())
            .sortedByDescending { it.score }.take(5)
        var passed = false
        for (s in servers) {
            println("--- ${s.countryShort} ${s.hostName} ${s.ip}:${s.sstpPort}")
            val t = SstpTunnel(s.ip, s.sstpPort, 1400) { println("  $it") }
            try {
                t.start()
                Thread.sleep(3_000)
                val after = trace()
                println("  Через VPN: $after, туннель жив: ${t.alive}")
                if (after.isNotEmpty() && after != before) passed = true
            } catch (e: Exception) {
                println("  ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                t.stop()
            }
            if (passed) break
        }
        println("После отключения: ${runCatching { trace() }.getOrElse { "?" }}")
        println("Итог: ${if (passed) "ТУННЕЛЬ РАБОТАЕТ" else "не удалось"}")
        assertTrue(passed)
    }
}

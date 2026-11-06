package com.example.vpngateclient.desktop

import com.example.vpngateclient.data.VpnGateParser
import com.example.vpngateclient.desktop.sstp.SstpClient
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Живая проверка своего SSTP-клиента на настоящих серверах VPN Gate (только согласование, без адаптера).
 * Запускается в CI с SSTP_LIVE_TEST=1.
 */
class SstpLiveTest {
    @Test
    fun negotiateWithRealServers() {
        if (System.getenv("SSTP_LIVE_TEST") == null) return
        val c = URL("http://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val servers = VpnGateParser.parse(c.inputStream.bufferedReader().readText())
            .sortedByDescending { it.score }
            .take(10)
        println("Серверов для проверки: ${servers.size}")
        var ok = 0
        for (s in servers) {
            println("--- ${s.countryShort} ${s.hostName} ${s.ip}:${s.sstpPort}")
            val client = SstpClient(s.ip, s.sstpPort, log = { println("  $it") })
            try {
                val neg = client.connect(20_000)
                println("  УСПЕХ: IP ${neg.ip.hostAddress}")
                ok++
            } catch (e: Exception) {
                println("  ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                client.close()
            }
            if (ok >= 3) break
        }
        println("Успешно: $ok")
        assertTrue(ok > 0, "ни один сервер не принял SSTP")
    }
}

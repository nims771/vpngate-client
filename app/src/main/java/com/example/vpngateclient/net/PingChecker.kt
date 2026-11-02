package com.example.vpngateclient.net

import com.example.vpngateclient.data.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка задержки до сервера.
 *
 * ICMP-пинг на Android без root ненадёжен, поэтому меряем время TCP-рукопожатия
 * с тем самым портом, через который потом идёт подключение (SSTP/OpenVPN-TCP).
 * Каждый порт пробуем дважды: одиночные потери пакетов не должны помечать
 * рабочий сервер как «нет ответа». Берём лучшее из удачных попыток.
 */
object PingChecker {

    suspend fun ping(server: VpnServer, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        val hint = server.sstpPortHint
        val ports = if (hint != null) {
            listOf(hint)
        } else if (server.proto == "tcp") {
            listOf(server.port)
        } else {
            // У UDP-конфигов TCP-порт неизвестен — SoftEther обычно слушает эти
            listOf(443, 992, 1194, 5555)
        }

        var best: Long? = null
        for (port in ports) {
            repeat(ATTEMPTS) { attempt ->
                ensureActive()
                val ms = tcpConnect(server.ip, port, timeoutMs)
                if (ms != null) best = minOf(best ?: ms, ms)
                if (ms == null && attempt < ATTEMPTS - 1) delay(150)
            }
            if (best != null) return@withContext best
        }
        null
    }

    private const val ATTEMPTS = 2

    private fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use {
                it.tcpNoDelay = true
                it.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) {
            null
        }
    }
}

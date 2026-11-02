package com.example.vpngateclient.net

import com.example.vpngateclient.data.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка задержки до сервера.
 *
 * ICMP-пинг на Android без root ненадёжен, поэтому меряем время TCP-рукопожатия
 * с портом сервера. Это почти то же самое, что пинг, и заодно показывает,
 * что сервер действительно принимает соединения.
 */
object PingChecker {

    /** Задержка в мс или null, если сервер не ответил. */
    suspend fun ping(server: VpnServer, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        // SoftEther-серверы VPN Gate почти всегда слушают TCP 443 и 1194,
        // даже если OpenVPN-конфиг у них на UDP.
        val ports = buildList {
            if (server.proto == "tcp") add(server.port)
            add(443)
            add(1194)
        }.distinct().take(2)

        for (port in ports) {
            ensureActive()
            val ms = tcpConnect(server.ip, port, timeoutMs)
            if (ms != null) return@withContext ms
        }
        null
    }

    private fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) {
            null
        }
    }
}

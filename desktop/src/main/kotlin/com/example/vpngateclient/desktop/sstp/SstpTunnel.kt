package com.example.vpngateclient.desktop.sstp

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Полный туннель: SSTP-клиент + адаптер Wintun + маршруты Windows.
 * Весь трафик идёт в VPN (маршруты 0.0.0.0/1 и 128.0.0.0/1), а сам сервер — по-прежнему через обычный шлюз.
 */
class SstpTunnel(
    private val serverIp: String,
    private val port: Int,
    private val mtu: Int,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    private var client: SstpClient? = null
    private var tun: Wintun? = null
    private val threads = mutableListOf<Thread>()
    private var hostRoute: Pair<Int, String>? = null

    /** Почему туннель закрылся (null — работает). */
    @Volatile var closeReason: String? = null
        private set

    val alive: Boolean get() = running && closeReason == null

    var localIp: Inet4Address? = null
        private set

    /** Подключается и настраивает Windows. Блокирует поток; при ошибке всё убирает за собой. */
    fun start() {
        val dll = Wintun.findDll() ?: throw IOException("не найден wintun.dll — переустановите программу")
        val c = SstpClient(serverIp, port, mtu = mtu, log = log)
        client = c
        try {
            val neg = c.connect()
            localIp = neg.ip

            // маршрут к самому серверу — через текущий шлюз, иначе туннель пойдёт сам в себя
            val best = bestRoute(serverIp)

            log("Создаю адаптер VPN…")
            val t = Wintun.open(dll)
            tun = t
            t.start()
            val idx = t.ifIndex
            val dns = neg.dns.map { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }

            netsh("interface", "ipv4", "set", "interface", "$idx", "dadtransmits=0", "metric=1", "store=active")
            netsh("interface", "ipv4", "set", "subinterface", "$idx", "mtu=$mtu", "store=active")
            netsh(
                "interface", "ipv4", "set", "address", "name=$idx", "source=static",
                "address=${neg.ip.hostAddress}", "mask=255.255.255.255", "store=active", must = true,
            )
            if (best != null) {
                netsh("interface", "ipv4", "add", "route", "$serverIp/32", "interface=${best.first}", "nexthop=${best.second}", "metric=1", "store=active")
                hostRoute = best
            } else log("Не нашёл текущий шлюз — маршрут к серверу не добавлен")
            netsh("interface", "ipv4", "add", "route", "0.0.0.0/1", "interface=$idx", "nexthop=0.0.0.0", "metric=1", "store=active", must = true)
            netsh("interface", "ipv4", "add", "route", "128.0.0.0/1", "interface=$idx", "nexthop=0.0.0.0", "metric=1", "store=active", must = true)
            netsh("interface", "ipv4", "set", "dnsservers", "name=$idx", "source=static", "address=${dns[0]}", "register=none", "validate=no")
            dns.getOrNull(1)?.let { netsh("interface", "ipv4", "add", "dnsservers", "name=$idx", "address=$it", "index=2", "validate=no") }
            log("Маршруты настроены, DNS: ${dns.joinToString()}")

            running = true
            startThreads(c, t)
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun startThreads(c: SstpClient, t: Wintun) {
        // сервер → Windows
        threads += Thread({
            val reason = c.pump { t.write(it) }
            if (running) {
                closeReason = reason
                log("SSTP: $reason")
            }
        }, "sstp-rx").apply { isDaemon = true; start() }

        // Windows → сервер (только IPv4: IPv6 через VPN Gate не идёт)
        threads += Thread({
            try {
                while (running) {
                    val p = t.read(250) ?: continue
                    if (p.isNotEmpty() && (p[0].toInt() shr 4) == 4) c.sendIp(p)
                }
            } catch (e: Exception) {
                if (running && closeReason == null) {
                    closeReason = "ошибка отправки: ${e.message}"
                    log("SSTP: $closeReason")
                }
            }
        }, "sstp-tx").apply { isDaemon = true; start() }

        // проверка связи: сервер молчит — шлём эхо, молчит дольше минуты — соединение мёртвое
        threads += Thread({
            while (running) {
                runCatching { Thread.sleep(10_000) }
                if (!running) break
                val silent = System.currentTimeMillis() - c.lastRx
                if (silent > 60_000) {
                    if (closeReason == null) closeReason = "сервер не отвечает"
                    log("SSTP: сервер не отвечает больше минуты")
                    c.close()
                    break
                }
                if (silent > 20_000) c.sendEcho()
            }
        }, "sstp-keepalive").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        client?.close()
        threads.forEach { runCatching { it.join(1_500) } }
        threads.clear()
        tun?.close()
        tun = null
        hostRoute?.let { (ifIdx, gw) ->
            netsh("interface", "ipv4", "delete", "route", "$serverIp/32", "interface=$ifIdx", "nexthop=$gw", "store=active")
        }
        hostRoute = null
    }

    private fun netsh(vararg args: String, must: Boolean = false) {
        val p = ProcessBuilder(listOf("netsh") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes()
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        if (code != 0) {
            val text = String(out).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(200)
            val msg = "netsh ${args.take(5).joinToString(" ")}: код $code $text"
            if (must) throw IOException(msg) else log(msg)
        }
    }

    @Suppress("FunctionName")
    private interface IpHlp : Library {
        fun GetBestRoute(dest: Int, source: Int, row: Memory): Int
    }

    /** Текущий маршрут до адреса: (номер интерфейса, шлюз). */
    private fun bestRoute(ip: String): Pair<Int, String>? = runCatching {
        val addr = InetAddress.getByName(ip).address
        val dest = ByteBuffer.wrap(addr).order(ByteOrder.LITTLE_ENDIAN).int
        val row = Memory(56)
        val lib = Native.load("iphlpapi", IpHlp::class.java)
        val rc = lib.GetBestRoute(dest, 0, row)
        if (rc != 0) return null
        val gw = InetAddress.getByAddress(row.getByteArray(12, 4)).hostAddress
        row.getInt(16) to gw
    }.getOrNull()
}

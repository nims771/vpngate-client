package com.example.vpngateclient.data

import com.example.vpngateclient.vpn.OvpnConfig
import java.util.Base64

/**
 * Разбор CSV с http://www.vpngate.net/api/iphone/
 *
 * Формат:
 *   *vpn_servers
 *   #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
 *    TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 *   ...строки серверов...
 *   *
 */
object VpnGateParser {

    fun parse(csv: String): List<VpnServer> =
        csv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .distinctBy { it.id }
            .toList()

    fun parseLine(line: String): VpnServer? {
        val f = line.split(',')
        if (f.size < 15) return null

        // base64 всегда последний; в Message теоретически могут быть запятые
        val b64 = f.last().trim()
        if (b64.isEmpty()) return null
        val config = try {
            String(Base64.getMimeDecoder().decode(b64), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!config.contains("remote")) return null

        val ip = f[1].trim()
        val endpoint = OvpnConfig.endpoint(config)
        val sstpHint = SSTP_HINT.find(config)?.groupValues?.get(1)?.toIntOrNull()

        return VpnServer(
            hostName = f[0].trim(),
            ip = ip,
            score = f[2].toLongOrNull() ?: 0,
            speedBps = f[4].toLongOrNull() ?: 0,
            countryLong = f[5].trim(),
            countryShort = f[6].trim(),
            sessions = f[7].toIntOrNull() ?: 0,
            uptimeMs = f[8].toLongOrNull() ?: 0,
            totalUsers = f[9].toLongOrNull() ?: 0,
            totalTraffic = f[10].toLongOrNull() ?: 0,
            logType = f[11].trim(),
            operator = f[12].trim(),
            message = f.subList(13, f.size - 1).joinToString(",").trim(),
            proto = endpoint.proto,
            port = endpoint.port,
            config = config,
            sstpPortHint = sstpHint,
        )
    }

    private val SSTP_HINT = Regex("""^#sstp-port[ \t]+(\d+)""", RegexOption.MULTILINE)
}

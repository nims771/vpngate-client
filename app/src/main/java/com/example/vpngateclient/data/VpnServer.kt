package com.example.vpngateclient.data

/** Один сервер из публичного списка VPN Gate. */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val speedBps: Long,
    val countryLong: String,
    val countryShort: String,
    val sessions: Int,
    val uptimeMs: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    /** "tcp" или "udp" — из OpenVPN-конфига. */
    val proto: String,
    val port: Int,
    /** Готовый .ovpn (уже раскодирован из base64). */
    val config: String,
) {
    val id: String get() = hostName
    val flag: String get() = countryFlag(countryShort)
}

/** "JP" -> 🇯🇵 */
fun countryFlag(code: String): String {
    val up = code.uppercase()
    if (up.length != 2 || !up.all { it in 'A'..'Z' }) return "🌐"
    val first = up[0].code - 'A'.code + 0x1F1E6
    val second = up[1].code - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

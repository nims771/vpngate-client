package com.example.vpngateclient

import com.example.vpngateclient.vpn.OpenVpnController
import de.blinkt.openvpn.core.ICSOpenVPNApplication

/**
 * ICSOpenVPNApplication создаёт каналы уведомлений VPN и подписывается
 * на статус туннеля из процесса «:openvpn».
 */
class App : ICSOpenVPNApplication() {
    // Создаётся лениво: в процессе «:openvpn» интерфейс не нужен
    val vpn: OpenVpnController by lazy { OpenVpnController(this) }
}

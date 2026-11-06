package com.example.vpngateclient.vpn

import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.VpnServer
import kotlinx.coroutines.flow.StateFlow

/** Текущее состояние туннеля (уровни — из ConnectionStatus движка OpenVPN). */
data class VpnStatus(
    val level: String = "LEVEL_NOTCONNECTED",
    val state: String = "",
    val message: String = "",
    val serverId: String? = null,
    val serverName: String? = null,
    /** Каким протоколом идёт подключение: "SSTP" или "OpenVPN". */
    val protocol: String? = null,
    /** Этапы подключения для наглядного индикатора (пусто — индикатор не показывается). */
    val steps: List<String> = emptyList(),
    /** Текущий этап (индекс в [steps]); steps.size — все этапы пройдены. */
    val step: Int = 0,
    /** Этап [step] завершился ошибкой. */
    val stepFailed: Boolean = false,
    /** Работает ли интернет через туннель (null — не проверялось). */
    val internetOk: Boolean? = null,
    /** Внешний IP-адрес через VPN, если удалось узнать. */
    val publicIp: String? = null,
    /** Соединение оборвалось само (не по кнопке) — можно переподключиться к другому серверу. */
    val dropped: Boolean = false,
) {
    val isConnected get() = level == "LEVEL_CONNECTED"
    val isConnecting get() = level in CONNECTING
    val isActive get() = isConnected || isConnecting || level == "LEVEL_VPNPAUSED"

    val title: String
        get() = when (level) {
            "LEVEL_CONNECTED" -> "Подключено"
            "LEVEL_VPNPAUSED" -> "На паузе"
            "LEVEL_NONETWORK" -> "Нет сети"
            "LEVEL_AUTH_FAILED" -> "Ошибка авторизации"
            in CONNECTING -> "Подключение…"
            else -> "Не подключено"
        }

    private companion object {
        val CONNECTING = setOf(
            "LEVEL_START",
            "LEVEL_CONNECTING_SERVER_REPLIED",
            "LEVEL_CONNECTING_NO_SERVER_REPLY_YET",
            "LEVEL_WAITING_FOR_USER_INPUT",
        )
    }
}

enum class Protocol(val label: String) {
    AUTO("Авто: SSTP, при неудаче OpenVPN"),
    SSTP("SSTP (похож на HTTPS)"),
    OPENVPN("OpenVPN"),
    /** Только Windows: протокол SoftEther через установленный SoftEther VPN Client. */
    SOFTETHER("SoftEther (как VPN Gate на ПК)"),
    /** Только Windows: встроенный в Windows SSTP-клиент (rasdial). */
    WIN_SSTP("SSTP через Windows (rasdial)"),
}

sealed interface ConnectResult {
    data object Started : ConnectResult
    /** Нужно показать системный запрос на VPN и потом повторить connect(). */
    /** Нужно разрешение ОС (на Android payload — Intent системного диалога). */
    data class NeedPermission(val payload: Any) : ConnectResult
    data class Error(val message: String) : ConnectResult
}

/** VPN-движок платформы: Android (SSTP/OpenVPN) или Windows (SoftEther/SSTP). */
interface VpnBackend {
    val status: StateFlow<VpnStatus>
    val log: StateFlow<List<String>>
    suspend fun connect(server: VpnServer, settings: AppSettings): ConnectResult
    fun disconnect()
    fun clearLog()
    /** Сколько ждать подключения (null — значения по умолчанию для протокола). */
    fun connectTimeoutMs(protocol: Protocol): Long? = null
}

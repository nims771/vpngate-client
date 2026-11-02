package kittoku.osc.api

import android.content.Context
import android.content.Intent
import android.os.Build
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Простой программный интерфейс к SSTP-движку Open SSTP Client
 * (добавлен для VPN Gate Client).
 */
object SstpEngine {
    const val PREFS_NAME = "sstp_engine"

    enum class Level { DISCONNECTED, CONNECTING, CONNECTED }

    data class State(val level: Level = Level.DISCONNECTED, val message: String = "")

    data class Config(
        /** IP или имя хоста, к которому открывается TCP-соединение. */
        val host: String,
        val port: Int = 443,
        /** Имя для SNI и заголовка Host (например vpn123.opengw.net). */
        val sni: String? = null,
        val username: String = "vpn",
        val password: String = "vpn",
        /** Доверять любому сертификату сервера (для самоподписанных). */
        val trustAllCertificates: Boolean = false,
        /** Запасной DNS, если сервер свой не выдаст. */
        val dns: String? = "8.8.8.8",
        val serverName: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val log: SharedFlow<String> = _log.asSharedFlow()

    @Volatile internal var lastError: String? = null
    @Volatile var serverName: String? = null
        private set

    internal fun setState(level: Level, message: String) {
        _state.value = State(level, message)
    }

    internal fun log(message: String) {
        message.lines().filter { it.isNotBlank() }.forEach { _log.tryEmit(it) }
    }

    fun connect(context: Context, config: Config) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(OscPrefKey.HOME_HOSTNAME.name, config.host)
            .putString(OscPrefKey.HOME_USERNAME.name, config.username)
            .putString(OscPrefKey.HOME_PASSWORD.name, config.password)
            .putString(OscPrefKey.SSL_PORT.name, config.port.toString()) // движок хранит числа строками
            .putBoolean(OscPrefKey.SSL_DO_VERIFY.name, false) // имя хоста не проверяем: подключаемся по IP
            .putBoolean(OscPrefKey.SSL_DO_SPECIFY_CERT.name, config.trustAllCertificates)
            .putBoolean(OscPrefKey.SSL_DO_USE_CUSTOM_SNI.name, config.sni != null)
            .putString(OscPrefKey.SSL_CUSTOM_SNI.name, config.sni ?: "")
            .putStringSet(OscPrefKey.PPP_AUTH_PROTOCOLS.name, setOf(AUTH_PROTOCOl_PAP, AUTH_PROTOCOL_MSCHAPv2))
            .putBoolean(OscPrefKey.PPP_IPv4_ENABLED.name, true)
            .putBoolean(OscPrefKey.PPP_IPv6_ENABLED.name, false)
            .putBoolean(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.name, config.dns != null)
            .putString(OscPrefKey.DNS_CUSTOM_ADDRESS.name, config.dns ?: "")
            .putBoolean(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE.name, true)
            .putBoolean(OscPrefKey.RECONNECTION_ENABLED.name, false)
            .apply()

        lastError = null
        serverName = config.serverName
        setState(Level.CONNECTING, "Подключение по SSTP…")

        val intent = Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun disconnect(context: Context) {
        if (_state.value.level == Level.DISCONNECTED) return
        runCatching {
            context.startService(Intent(context, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
        }
    }
}

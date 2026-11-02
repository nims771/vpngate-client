package com.example.vpngateclient.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.StringReader
import de.blinkt.openvpn.core.VpnStatus as EngineStatus

/** Текущее состояние туннеля (уровни — из ConnectionStatus движка OpenVPN). */
data class VpnStatus(
    val level: String = "LEVEL_NOTCONNECTED",
    val state: String = "",
    val message: String = "",
    val serverId: String? = null,
    val serverName: String? = null,
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

sealed interface ConnectResult {
    data object Started : ConnectResult
    /** Нужно показать системный запрос на VPN и потом повторить connect(). */
    data class NeedPermission(val intent: Intent) : ConnectResult
    data class Error(val message: String) : ConnectResult
}

/**
 * Встроенный OpenVPN (движок OpenVPN for Android, собирается вместе с приложением).
 * Туннель работает в отдельном процессе «:openvpn», состояние приходит сюда
 * через EngineStatus (его транслирует StatusListener из ICSOpenVPNApplication).
 */
class OpenVpnController(context: Context) : EngineStatus.StateListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(
        VpnStatus(serverId = prefs.getString("serverId", null), serverName = prefs.getString("serverName", null))
    )
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    init {
        EngineStatus.addStateListener(this)
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?,
    ) {
        _status.update {
            it.copy(
                level = level?.name ?: it.level,
                state = state.orEmpty(),
                message = logmessage.orEmpty(),
            )
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    suspend fun connect(config: String, serverId: String, serverName: String): ConnectResult {
        // Системное разрешение на VPN (диалог Android показывается один раз)
        VpnService.prepare(appContext)?.let { return ConnectResult.NeedPermission(it) }

        return withContext(Dispatchers.IO) {
            try {
                val parser = ConfigParser()
                parser.parseConfig(StringReader(config))
                val profile = parser.convertProfile()
                profile.mName = serverName

                val check = profile.checkProfile(appContext)
                if (check != R.string.no_error_found) {
                    return@withContext ConnectResult.Error(
                        "Конфиг сервера не подходит: ${appContext.getString(check)}"
                    )
                }

                ProfileManager.setTemporaryProfile(appContext, profile)
                prefs.edit().putString("serverId", serverId).putString("serverName", serverName).apply()
                _status.update {
                    it.copy(level = "LEVEL_START", state = "", message = "", serverId = serverId, serverName = serverName)
                }
                VPNLaunchHelper.startOpenVpn(profile, appContext, "VPN Gate Client", true)
                ConnectResult.Started
            } catch (e: Exception) {
                ConnectResult.Error("Не удалось запустить VPN: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        val intent = Intent(appContext, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false)
                } catch (e: Exception) {
                    // ignore
                }
                runCatching { appContext.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
    }
}

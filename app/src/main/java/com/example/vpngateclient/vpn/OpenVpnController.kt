package com.example.vpngateclient.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Текущее состояние туннеля (уровни — из ConnectionStatus OpenVPN for Android). */
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
    data object NotInstalled : ConnectResult
    /** Нужно показать системный диалог и потом повторить connect(). */
    data class NeedPermission(val intent: Intent) : ConnectResult
    data class Error(val message: String) : ConnectResult
}

/**
 * Управление туннелем через официальный внешний API приложения
 * «OpenVPN for Android» (de.blinkt.openvpn) по AIDL.
 * Само шифрование и VpnService делает это приложение — нам не нужно
 * собирать нативный OpenVPN и не нужно лицензировать свой код под GPL.
 */
class OpenVpnController(context: Context) {

    companion object {
        const val PACKAGE = "de.blinkt.openvpn"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    private val service = MutableStateFlow<IOpenVPNAPIService?>(null)
    private val _status = MutableStateFlow(
        VpnStatus(serverId = prefs.getString("serverId", null), serverName = prefs.getString("serverName", null))
    )
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    @Volatile private var bound = false
    @Volatile private var callbackRegistered = false

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            _status.update {
                it.copy(level = level ?: it.level, state = state.orEmpty(), message = message.orEmpty())
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = IOpenVPNAPIService.Stub.asInterface(binder)
            callbackRegistered = false
            tryRegisterCallback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
            callbackRegistered = false
        }

        override fun onBindingDied(name: ComponentName?) {
            runCatching { appContext.unbindService(this) }
            service.value = null
            bound = false
            callbackRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun bind() {
        if (bound || !isInstalled()) return
        val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PACKAGE)
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
    }

    /** Без разрешения пользователя OpenVPN for Android бросит SecurityException — это нормально. */
    private fun tryRegisterCallback() {
        if (callbackRegistered) return
        val s = service.value ?: return
        callbackRegistered = try {
            s.registerStatusCallback(callback)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: RemoteException) {
            false
        }
    }

    suspend fun connect(config: String, serverId: String, serverName: String): ConnectResult {
        if (!isInstalled()) return ConnectResult.NotInstalled
        bind()
        val s = withTimeoutOrNull(5_000) { service.filterNotNull().first() }
            ?: return ConnectResult.Error(
                "Не удалось связаться с OpenVPN for Android. Откройте его один раз и попробуйте снова."
            )

        return try {
            // 1) разрешение «этому приложению можно управлять OpenVPN»
            s.prepare(appContext.packageName)?.let { return ConnectResult.NeedPermission(it) }
            tryRegisterCallback()
            // 2) системное разрешение на VPN
            s.prepareVPNService()?.let { return ConnectResult.NeedPermission(it) }

            prefs.edit().putString("serverId", serverId).putString("serverName", serverName).apply()
            _status.update {
                it.copy(level = "LEVEL_START", state = "", message = "", serverId = serverId, serverName = serverName)
            }
            s.startVPN(config)
            ConnectResult.Started
        } catch (e: SecurityException) {
            ConnectResult.Error("OpenVPN for Android отклонил запрос: ${e.message}")
        } catch (e: RemoteException) {
            service.value = null
            bound = false
            ConnectResult.Error("Связь с OpenVPN for Android потеряна, попробуйте ещё раз")
        }
    }

    fun disconnect() {
        try {
            service.value?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
    }
}

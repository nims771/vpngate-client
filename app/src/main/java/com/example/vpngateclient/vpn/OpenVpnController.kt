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
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import com.example.vpngateclient.data.VpnServer
import kittoku.osc.api.SstpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import de.blinkt.openvpn.core.VpnStatus as EngineStatus

/** Текущее состояние туннеля (уровни — из ConnectionStatus движка OpenVPN). */
data class VpnStatus(
    val level: String = "LEVEL_NOTCONNECTED",
    val state: String = "",
    val message: String = "",
    val serverId: String? = null,
    val serverName: String? = null,
    /** Каким протоколом идёт подключение: "SSTP" или "OpenVPN". */
    val protocol: String? = null,
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
}

sealed interface ConnectResult {
    data object Started : ConnectResult
    /** Нужно показать системный запрос на VPN и потом повторить connect(). */
    data class NeedPermission(val intent: Intent) : ConnectResult
    data class Error(val message: String) : ConnectResult
}

/**
 * Управление VPN двумя встроенными движками:
 *  • SSTP — ядро Open SSTP Client (модуль :sstp), трафик выглядит как HTTPS;
 *  • OpenVPN — движок OpenVPN for Android (модуль :openvpn), работает в процессе «:openvpn».
 * В режиме AUTO сначала пробуется SSTP, а если он не подключился — OpenVPN.
 */
class OpenVpnController(context: Context) : EngineStatus.StateListener, EngineStatus.LogListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(
        VpnStatus(serverId = prefs.getString("serverId", null), serverName = prefs.getString("serverName", null))
    )
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** Журнал подключения (последние строки) от обоих движков. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Какой движок сейчас главный (его статус показываем). */
    @Volatile private var active: Protocol? = null
    /** Сервер, к которому идёт подключение — нужен для перехода SSTP → OpenVPN. */
    private var pending: VpnServer? = null
    private var fallbackAllowed = false
    private var sstpReachedConnected = false

    init {
        _log.value = runCatching {
            EngineStatus.getlogbuffer().filter { it.verbosityLevel <= 3 }.map(::formatLog)
        }
            .getOrDefault(emptyList()).takeLast(MAX_LOG)
        EngineStatus.addLogListener(this)
        EngineStatus.addStateListener(this)
        checkCrashFile()

        scope.launch { SstpEngine.log.collect { addLog(it) } }
        scope.launch { SstpEngine.state.collect(::onSstpState) }
    }

    // ---------- журнал ----------

    private fun addLog(text: String) {
        val line = timeFormat.format(Date()) + "  " + text
        _log.update { (it + line).takeLast(MAX_LOG) }
    }

    override fun newLog(logItem: LogItem) {
        // Длинный список параметров OpenVPN (уровень 4+) только мешает
        if (logItem.verbosityLevel > 3) return
        val line = formatLog(logItem)
        _log.update { (it + line).takeLast(MAX_LOG) }
        checkCrashFile()
    }

    /** Если служба VPN упала, App записал текст ошибки в файл — показываем его в журнале. */
    private fun checkCrashFile() {
        val file = java.io.File(appContext.filesDir, com.example.vpngateclient.App.CRASH_FILE)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        if (!text.isNullOrBlank()) {
            val lines = text.lines().filter { it.isNotBlank() }.take(25)
            _log.update { (it + ("!!! СБОЙ:\n" + lines.joinToString("\n"))).takeLast(MAX_LOG) }
        }
    }

    private fun formatLog(item: LogItem): String {
        val text = runCatching { item.getString(appContext) }.getOrElse { "?" }
        return timeFormat.format(Date(item.logtime)) + "  " + text
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ---------- состояние OpenVPN ----------

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?,
    ) {
        if (active == Protocol.SSTP) return // сейчас работает SSTP, OpenVPN не трогаем
        _status.update {
            it.copy(
                level = level?.name ?: it.level,
                state = state.orEmpty(),
                message = logmessage.orEmpty(),
            )
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    // ---------- состояние SSTP ----------

    private fun onSstpState(st: SstpEngine.State) {
        if (active != Protocol.SSTP) return
        when (st.level) {
            SstpEngine.Level.CONNECTING -> _status.update {
                it.copy(level = "LEVEL_CONNECTING_SERVER_REPLIED", state = st.message, message = st.message)
            }
            SstpEngine.Level.CONNECTED -> {
                sstpReachedConnected = true
                _status.update { it.copy(level = "LEVEL_CONNECTED", state = "", message = "") }
            }
            SstpEngine.Level.DISCONNECTED -> {
                val server = pending
                if (fallbackAllowed && !sstpReachedConnected && server != null) {
                    fallbackAllowed = false
                    addLog("SSTP не подключился" + (if (st.message.isNotBlank()) " (${st.message})" else "") +
                        " — пробуем OpenVPN")
                    scope.launch {
                        val r = startOpenVpn(server)
                        if (r is ConnectResult.Error) {
                            addLog(r.message)
                            _status.update { it.copy(level = "LEVEL_NOTCONNECTED") }
                        }
                    }
                } else {
                    if (st.message.isNotBlank() && !sstpReachedConnected) addLog("SSTP: ошибка ${st.message}")
                    _status.update { it.copy(level = "LEVEL_NOTCONNECTED", state = "", message = st.message) }
                }
            }
        }
    }

    // ---------- подключение ----------

    suspend fun connect(
        server: VpnServer,
        protocol: Protocol,
        trustAllSstp: Boolean,
        sendSni: Boolean,
    ): ConnectResult {
        // Системное разрешение на VPN (диалог Android показывается один раз)
        VpnService.prepare(appContext)?.let { return ConnectResult.NeedPermission(it) }

        val name = "${server.flag} ${server.countryLong} · ${server.hostName}"
        prefs.edit().putString("serverId", server.id).putString("serverName", name).apply()
        pending = server
        this.trustAllSstp = trustAllSstp
        this.sendSni = sendSni

        return when (protocol) {
            Protocol.OPENVPN -> {
                active = null
                stopSstp()
                startOpenVpn(server)
            }
            Protocol.SSTP, Protocol.AUTO -> {
                if (active == Protocol.OPENVPN) stopOpenVpn()
                active = null
                // Предыдущий сеанс SSTP нужно сначала полностью закрыть
                if (SstpEngine.state.value.level != SstpEngine.Level.DISCONNECTED) {
                    stopSstp()
                    withTimeoutOrNull(5_000) {
                        SstpEngine.state.first { it.level == SstpEngine.Level.DISCONNECTED }
                    }
                }
                fallbackAllowed = protocol == Protocol.AUTO
                startSstp(server, name)
            }
        }
    }

    private var trustAllSstp = false
    private var sendSni = false

    private fun startSstp(server: VpnServer, name: String): ConnectResult {
        active = Protocol.SSTP
        sstpReachedConnected = false
        _status.update {
            it.copy(
                level = "LEVEL_START", state = "", message = "",
                serverId = server.id, serverName = name, protocol = "SSTP",
            )
        }
        // SoftEther принимает SSTP на тех же TCP-портах, что и OpenVPN-TCP; для UDP-серверов — 443
        val port = if (server.proto == "tcp") server.port else 443
        addLog("SSTP: ${server.ip}:$port (${server.hostName}.opengw.net)")
        return try {
            SstpEngine.connect(
                appContext,
                SstpEngine.Config(
                    host = server.ip,
                    port = port,
                    sni = "${server.hostName}.opengw.net",
                    sendSni = sendSni,
                    trustAllCertificates = trustAllSstp,
                    serverName = name,
                ),
            )
            ConnectResult.Started
        } catch (e: Exception) {
            ConnectResult.Error("Не удалось запустить SSTP: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun startOpenVpn(server: VpnServer): ConnectResult {
        val name = "${server.flag} ${server.countryLong} · ${server.hostName}"
        active = Protocol.OPENVPN
        return withContext(Dispatchers.IO) {
            try {
                val parser = ConfigParser()
                parser.parseConfig(StringReader(OvpnConfig.prepare(server.config)))
                val profile = parser.convertProfile()
                profile.mName = name

                val check = profile.checkProfile(appContext)
                if (check != R.string.no_error_found) {
                    return@withContext ConnectResult.Error(
                        "Конфиг сервера не подходит: ${appContext.getString(check)}"
                    )
                }

                ProfileManager.setTemporaryProfile(appContext, profile)
                _status.update {
                    it.copy(
                        level = "LEVEL_START", state = "", message = "",
                        serverId = server.id, serverName = name, protocol = "OpenVPN",
                    )
                }
                VPNLaunchHelper.startOpenVpn(profile, appContext, "VPN Gate Client", true)
                ConnectResult.Started
            } catch (e: Exception) {
                ConnectResult.Error("Не удалось запустить OpenVPN: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        fallbackAllowed = false
        if (active == null) {
            stopSstp()
            stopOpenVpn()
        }
        pending = null
        when (active) {
            Protocol.OPENVPN -> stopOpenVpn()
            else -> stopSstp()
        }
        _status.update { it.copy(level = "LEVEL_NOTCONNECTED", state = "", message = "") }
    }

    private fun stopSstp() {
        SstpEngine.disconnect(appContext)
    }

    private fun stopOpenVpn() {
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

    private companion object {
        const val MAX_LOG = 800
    }
}

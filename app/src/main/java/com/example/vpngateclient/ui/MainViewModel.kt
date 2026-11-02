package com.example.vpngateclient.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vpngateclient.App
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.FavoritesStore
import com.example.vpngateclient.data.Selection
import com.example.vpngateclient.data.ServerRepository
import com.example.vpngateclient.data.SettingsStore
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.net.PingChecker
import com.example.vpngateclient.net.SpeedTest
import com.example.vpngateclient.vpn.ConnectResult
import com.example.vpngateclient.vpn.OvpnConfig
import com.example.vpngateclient.vpn.Protocol
import com.example.vpngateclient.vpn.VpnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

data class CountryInfo(val code: String, val name: String, val count: Int)

data class UiState(
    val servers: List<VpnServer> = emptyList(),
    val countries: List<CountryInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
    val fromCache: Boolean = false,
    /** id -> мс; -1 = не ответил */
    val pings: Map<String, Long> = emptyMap(),
    val pinging: Set<String> = emptySet(),
    val pingRunning: Boolean = false,
    val pingDone: Int = 0,
    val pingTotal: Int = 0,
    val favorites: Map<String, VpnServer> = emptyMap(),
    val query: String = "",
    val settings: AppSettings = AppSettings(),
    val selection: Selection = Selection.Auto,
    /** Идёт подбор/перебор серверов. */
    val connecting: Boolean = false,
    /** Подпись о текущей попытке: «Сервер 2 из 5: Japan». */
    val attemptInfo: String? = null,
    /** Время, с которого туннель подключён (для таймера). */
    val connectedSince: Long = 0L,
    /** Идёт проверка скорости. */
    val speedTesting: Boolean = false,
    /** Измеренная скорость по серверам: id -> Мбит/с. */
    val speeds: Map<String, Double> = emptyMap(),
    /** Задержка через туннель (мс) последнего теста. */
    val tunnelLatency: Long? = null,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class LaunchIntent(val intent: Intent) : UiEvent
}

/** Страна + её серверы, отсортированные по качеству. */
data class CountryGroup(
    val info: CountryInfo,
    val servers: List<VpnServer>,
    /** Лучший пинг среди серверов страны, null — не проверялись, -1 — никто не ответил. */
    val bestPing: Long?,
)

/** Чем меньше, тем лучше: пинг + штрафы за медленный канал и нагрузку. */
/**
 * Чем меньше, тем лучше. Учитываем не только пинг: у добровольческих серверов
 * VPN Gate узкое место — канал и число пользователей.
 * measuredMbps — скорость, которую мы сами намерили через этот сервер.
 */
fun serverCost(server: VpnServer, ping: Long, measuredMbps: Double? = null): Long {
    val mbps = server.speedBps / 1_000_000
    val linePenalty = when {
        mbps < 10 -> 250
        mbps < 50 -> 120
        mbps < 100 -> 50
        else -> 0
    }
    val loadPenalty = server.sessions.coerceAtMost(80) * 6L
    val measuredPenalty = when {
        measuredMbps == null -> 0
        measuredMbps < 1.0 -> 400
        measuredMbps < 3.0 -> 200
        measuredMbps < 8.0 -> 60
        else -> -40 // проверенный быстрый — чуть выше в списке
    }
    return ping + linePenalty + loadPenalty + measuredPenalty
}

fun pingSortKey(p: Long?): Long = when {
    p == null -> Long.MAX_VALUE - 1 // не проверен
    p < 0 -> Long.MAX_VALUE         // не ответил
    else -> p
}

fun UiState.sortedServers(list: List<VpnServer>): List<VpnServer> =
    list.sortedWith(compareBy<VpnServer> { pingSortKey(pings[it.id]) }.thenByDescending { it.score })

/** Страны с серверами для вкладки «Страны» (с учётом поиска). */
fun UiState.countryGroups(): List<CountryGroup> {
    val q = query.trim()
    return servers.groupBy { it.countryShort.uppercase() }
        .map { (code, list) ->
            val measured = list.mapNotNull { pings[it.id] }
            val best = when {
                measured.isEmpty() -> null
                measured.any { it > 0 } -> measured.filter { it > 0 }.min()
                else -> -1L
            }
            CountryGroup(CountryInfo(code, list.first().countryLong, list.size), sortedServers(list), best)
        }
        .filter { q.isEmpty() || it.info.name.contains(q, true) || it.info.code.equals(q, true) }
        .sortedWith(compareBy<CountryGroup> { pingSortKey(it.bestPing) }.thenByDescending { it.info.count })
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServerRepository(app)
    private val settingsStore = SettingsStore(app)
    private val favoritesStore = FavoritesStore(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val vpn = (app as App).vpn

    private val _state = MutableStateFlow(
        UiState(
            settings = settingsStore.load(),
            favorites = favoritesStore.load(),
            selection = Selection.decode(prefs.getString("selection", null)),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    val vpnStatus: StateFlow<VpnStatus> = vpn.status
    val vpnLog: StateFlow<List<String>> = vpn.log

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pingJob: Job? = null
    private var connectJob: Job? = null
    private var permissionWait: CompletableDeferred<Boolean>? = null
    var pendingExport: VpnServer? = null

    init {
        // Таймер подключения
        viewModelScope.launch {
            vpn.status.collect { st ->
                _state.update {
                    when {
                        st.isConnected && it.connectedSince == 0L -> it.copy(connectedSince = System.currentTimeMillis())
                        !st.isConnected && it.connectedSince != 0L -> it.copy(connectedSince = 0L)
                        else -> it
                    }
                }
            }
        }

        viewModelScope.launch {
            val cached = repo.loadCache()
            if (cached != null) setServers(cached.servers, cached.savedAt, fromCache = true)
            val stale = cached == null ||
                System.currentTimeMillis() - cached.savedAt > CACHE_TTL_MS

            if (_state.value.settings.autoConnect && !vpn.status.value.isActive && cached != null) {
                connectSelected()
            }
            if (cached == null || (stale && _state.value.settings.autoRefresh)) {
                refresh()
            } else if (_state.value.settings.autoPing && connectJob?.isActive != true && !vpn.status.value.isActive) {
                pingAll()
            }
        }
    }

    // ---------- список ----------

    private var refreshJob: Job? = null

    /** Обновить список. Повторное нажатие перезапускает загрузку (старая отменяется). */
    fun refresh() {
        refreshJob?.cancel()
        val job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val own = _state.value.settings.listUrl.trim()
                // Сначала адрес из настроек, при неудаче — стандартный vpngate.net
                val urls = listOf(own, AppSettings.DEFAULT_URL).distinct()
                val result = repo.download(urls)
                val list = result.servers
                setServers(list, System.currentTimeMillis(), fromCache = false)
                _state.update { it.copy(loading = false) }
                msg(
                    if (result.url != own) "Ваш адрес не ответил — список взят с vpngate.net (${list.size} серверов)"
                    else "Список обновлён: ${list.size} серверов"
                )
                // Через работающий VPN пинг неточен и забирает скорость — не проверяем автоматически
                if (_state.value.settings.autoPing && !vpn.status.value.isActive) pingAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hasCache = _state.value.servers.isNotEmpty()
                val text = "Не удалось обновить список: ${e.localizedMessage ?: e.javaClass.simpleName}" +
                    if (hasCache) ". Показан сохранённый список." else ""
                _state.update { it.copy(loading = false, error = text) }
                msg(text)
            } finally {
                // если эту загрузку отменили новым нажатием — индикатор не трогаем
                if (refreshJob == coroutineContext[Job]) _state.update { it.copy(loading = false) }
            }
        }
        refreshJob = job
    }

    private fun setServers(list: List<VpnServer>, time: Long, fromCache: Boolean) {
        val byId = list.associateBy { it.id }
        val countries = list.groupBy { it.countryShort.uppercase() }
            .map { (code, items) -> CountryInfo(code, items.first().countryLong, items.size) }
            .sortedByDescending { it.count }

        // Обновляем данные избранных серверов свежими (IP, конфиг и т.д.)
        val oldFav = _state.value.favorites
        val newFav = oldFav.mapValues { (id, old) -> byId[id] ?: old }
        if (newFav != oldFav) saveFavorites(newFav)

        _state.update { st ->
            st.copy(
                servers = list,
                countries = countries,
                updatedAt = time,
                fromCache = fromCache,
                favorites = newFav,
                // старые результаты сбрасываем: после обновления проверим заново
                pings = if (fromCache) st.pings else st.pings.filterKeys { it in byId || it in newFav },
            )
        }
    }

    // ---------- пинг ----------

    /** Проверить все серверы (и избранные, которых нет в списке). */
    fun pingAll() {
        if (vpn.status.value.isActive) {
            viewModelScope.launch { msg("VPN включён: пинг пойдёт через туннель и будет выше реального") }
        }
        val st = _state.value
        val targets = (st.servers + st.favorites.values).distinctBy { it.id }
        startPing(targets)
    }

    fun pingCountry(code: String) {
        startPing(_state.value.servers.filter { it.countryShort.equals(code, true) })
    }

    private fun startPing(targets: List<VpnServer>) {
        pingJob?.cancel()
        if (targets.isEmpty()) return
        pingJob = viewModelScope.launch {
            _state.update { it.copy(pingRunning = true, pingDone = 0, pingTotal = targets.size) }
            try {
                runPings(targets, countProgress = true)
            } finally {
                _state.update { it.copy(pingRunning = false, pinging = emptySet()) }
            }
        }
    }

    fun stopPing() {
        pingJob?.cancel()
    }

    fun pingOne(server: VpnServer) {
        viewModelScope.launch { runPings(listOf(server), countProgress = false) }
    }

    private suspend fun runPings(targets: List<VpnServer>, countProgress: Boolean) = coroutineScope {
        val settings = _state.value.settings
        val semaphore = Semaphore(settings.pingParallel.coerceIn(1, 64))
        targets.forEach { server ->
            launch {
                semaphore.withPermit {
                    _state.update { it.copy(pinging = it.pinging + server.id) }
                    val ms = PingChecker.ping(server, settings.pingTimeoutMs) ?: -1L
                    _state.update {
                        it.copy(
                            pings = it.pings + (server.id to ms),
                            pinging = it.pinging - server.id,
                            pingDone = if (countProgress) it.pingDone + 1 else it.pingDone,
                        )
                    }
                }
            }
        }
    }

    // ---------- выбор и подключение ----------

    fun findServer(id: String): VpnServer? =
        _state.value.servers.firstOrNull { it.id == id } ?: _state.value.favorites[id]

    /** Выбрать страну/сервер/автовыбор. Если VPN уже работает — переподключиться. */
    fun select(selection: Selection) {
        prefs.edit().putString("selection", selection.encode()).apply()
        _state.update { it.copy(selection = selection) }
        if (vpn.status.value.isActive || connectJob?.isActive == true) {
            connectSelected()
        }
    }

    /** Большая кнопка: подключить / отключить / отменить подключение. */
    fun toggleConnection() {
        if (connectJob?.isActive == true || vpn.status.value.isActive) {
            cancelConnection()
        } else {
            connectSelected()
        }
    }

    fun cancelConnection() {
        connectJob?.cancel()
        connectJob = null
        permissionWait?.complete(false)
        vpn.disconnect()
        _state.update { it.copy(connecting = false, attemptInfo = null) }
    }

    /** Подключение к одному конкретному серверу (из карточки сервера). */
    fun connectTo(server: VpnServer) {
        select(Selection.Server(server.id))
        if (connectJob?.isActive != true) connectSelected()
    }

    fun connectSelected() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, attemptInfo = "Выбираем лучший сервер…") }
            try {
                val sel = _state.value.selection
                val candidates = candidatesFor(sel)
                if (candidates.isEmpty()) {
                    msg(
                        if (_state.value.servers.isEmpty()) "Список серверов пуст — обновите его"
                        else "Нет доступных серверов. Обновите список или выберите другую страну."
                    )
                    return@launch
                }
                val list = candidates.take(_state.value.settings.maxAttempts.coerceIn(1, 15))
                for ((index, server) in list.withIndex()) {
                    _state.update {
                        it.copy(
                            attemptInfo = if (list.size > 1)
                                "Сервер ${index + 1} из ${list.size}: ${server.flag} ${server.countryLong}"
                            else null
                        )
                    }
                    when (tryConnect(server)) {
                        Attempt.CONNECTED -> return@launch
                        Attempt.CANCELLED -> return@launch
                        Attempt.FAILED -> {
                            // помечаем, чтобы автовыбор не брал его снова первым
                            _state.update { it.copy(pings = it.pings + (server.id to -1L)) }
                        }
                    }
                }
                vpn.disconnect()
                msg("Не удалось подключиться. Обновите список серверов или выберите другую страну.")
            } finally {
                _state.update { it.copy(connecting = false, attemptInfo = null) }
            }
        }
    }

    private enum class Attempt { CONNECTED, FAILED, CANCELLED }

    private suspend fun tryConnect(server: VpnServer): Attempt {
        val settings = _state.value.settings
        suspend fun start() =
            vpn.connect(server, settings.protocol, settings.sstpTrustAll, settings.sstpSendSni, settings.sstpMtu)

        var result = start()
        if (result is ConnectResult.NeedPermission) {
            val wait = CompletableDeferred<Boolean>()
            permissionWait = wait
            _events.send(UiEvent.LaunchIntent(result.intent))
            if (!wait.await()) {
                msg("Без разрешения Android на VPN подключиться нельзя")
                return Attempt.CANCELLED
            }
            result = start()
            if (result is ConnectResult.NeedPermission) {
                msg("Android не выдал разрешение на VPN. Проверьте, не включён ли другой VPN «постоянно».")
                return Attempt.CANCELLED
            }
        }
        if (result is ConnectResult.Error) {
            msg(result.message)
            return Attempt.FAILED
        }

        val timeout = when (settings.protocol) {
            Protocol.SSTP -> 40_000L
            Protocol.OPENVPN -> 70_000L
            Protocol.AUTO -> 110_000L
        }
        val final = withTimeoutOrNull(timeout) {
            vpn.status.first { it.isConnected || it.level in FAILED_LEVELS }
        }
        if (final?.isConnected == true) return Attempt.CONNECTED

        vpn.disconnect()
        delay(1_500) // даём службе закрыться перед следующей попыткой
        return Attempt.FAILED
    }

    /** Кандидаты для подключения в порядке «от лучшего». */
    private suspend fun candidatesFor(sel: Selection): List<VpnServer> {
        val st = _state.value
        val base = when (sel) {
            Selection.Auto -> st.servers
            is Selection.Country -> st.servers.filter { it.countryShort.equals(sel.code, true) }
            is Selection.Server -> return listOfNotNull(findServer(sel.id))
        }.filter { st.settings.protocol != Protocol.OPENVPN || it.hasOpenVpn }
        if (base.isEmpty()) return emptyList()

        // Проверяем пинг у тех, у кого его ещё нет (сначала самые «рейтинговые»)
        val unknown = base.filter { _state.value.pings[it.id] == null }
            .sortedByDescending { it.score }
            .take(40)
        if (unknown.isNotEmpty()) {
            _state.update { it.copy(attemptInfo = "Проверяем пинг серверов…") }
            runPings(unknown, countProgress = false)
        }

        val pings = _state.value.pings
        val alive = base.filter { (pings[it.id] ?: -1L) > 0 }
        val speeds = _state.value.speeds
        return if (alive.isNotEmpty()) {
            alive.sortedBy { serverCost(it, pings.getValue(it.id), speeds[it.id]) }
        } else {
            // Пинг мог не пройти (например, через работающий VPN) — пробуем по рейтингу
            base.sortedByDescending { it.score }
        }
    }

    private var speedJob: Job? = null

    /** Проверка скорости через текущее подключение. */
    fun runSpeedTest() {
        val status = vpn.status.value
        if (!status.isConnected || speedJob?.isActive == true) return
        val serverId = status.serverId
        speedJob = viewModelScope.launch {
            _state.update { it.copy(speedTesting = true) }
            try {
                val result = SpeedTest.run()
                if (result == null) {
                    msg("Не удалось измерить скорость — сайты для теста не ответили")
                } else {
                    _state.update {
                        it.copy(
                            speeds = if (serverId != null) it.speeds + (serverId to result.mbps) else it.speeds,
                            tunnelLatency = result.latencyMs,
                        )
                    }
                    if (result.mbps < 3.0 && _state.value.selection !is Selection.Server) {
                        msg("Сервер медленный. Нажмите «Сменить сервер» — автовыбор возьмёт другой.")
                    }
                }
            } finally {
                _state.update { it.copy(speedTesting = false) }
            }
        }
    }

    /** Переподключиться к следующему лучшему серверу (текущий помечается как медленный). */
    fun switchServer() {
        val current = vpn.status.value.serverId
        if (current != null) {
            _state.update { it.copy(speeds = it.speeds + (current to minOf(it.speeds[current] ?: 0.5, 0.5))) }
        }
        if (_state.value.selection is Selection.Server) select(Selection.Auto) else connectSelected()
    }

    fun onPermissionResult(granted: Boolean) {
        permissionWait?.complete(granted)
        permissionWait = null
    }

    fun clearLog() = vpn.clearLog()

    // ---------- избранное / экспорт ----------

    fun toggleFavorite(server: VpnServer) {
        val current = _state.value.favorites
        val updated = if (server.id in current) current - server.id else current + (server.id to server)
        _state.update { it.copy(favorites = updated) }
        saveFavorites(updated)
    }

    private fun saveFavorites(map: Map<String, VpnServer>) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { favoritesStore.save(map) } }
    }

    fun exportTo(uri: Uri) {
        val server = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(OvpnConfig.prepare(server.config).toByteArray())
                    } ?: error("no stream")
                }.isSuccess
            }
            msg(if (ok) "Файл .ovpn сохранён" else "Не удалось сохранить файл")
        }
    }

    // ---------- поиск и настройки ----------

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun updateSettings(s: AppSettings) {
        val urlChanged = s.listUrl.trim() != _state.value.settings.listUrl.trim()
        settingsStore.save(s)
        _state.update { it.copy(settings = s) }
        if (urlChanged) refresh() // новый адрес — сразу пробуем загрузить с него
    }

    private suspend fun msg(text: String) = _events.send(UiEvent.Message(text))

    private companion object {
        const val CACHE_TTL_MS = 30 * 60_000L
        val FAILED_LEVELS = setOf("LEVEL_NOTCONNECTED", "LEVEL_AUTH_FAILED")
    }
}

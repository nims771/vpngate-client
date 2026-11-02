package com.example.vpngateclient.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vpngateclient.App
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.FavoritesStore
import com.example.vpngateclient.data.ServerRepository
import com.example.vpngateclient.data.SettingsStore
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.net.PingChecker
import com.example.vpngateclient.vpn.ConnectResult
import com.example.vpngateclient.vpn.OvpnConfig
import com.example.vpngateclient.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class SortMode(val label: String) {
    SCORE("По рейтингу"),
    PING("По пингу"),
    SPEED("По скорости"),
    SESSIONS("Меньше нагрузка"),
    COUNTRY("По стране"),
}

enum class ListTab { ALL, FAVORITES }

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
    val country: String? = null,
    val proto: String? = null,
    val onlyReachable: Boolean = false,
    val sort: SortMode = SortMode.SCORE,
    val tab: ListTab = ListTab.ALL,
    val settings: AppSettings = AppSettings(),
    val findingBest: Boolean = false,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class LaunchIntent(val intent: Intent) : UiEvent
}

/** Отфильтрованный и отсортированный список для экрана. */
fun UiState.visibleServers(): List<VpnServer> {
    val base = when (tab) {
        ListTab.ALL -> servers
        ListTab.FAVORITES -> favorites.values.toList()
    }
    val q = query.trim()
    val filtered = base.filter { s ->
        (country == null || s.countryShort.equals(country, ignoreCase = true)) &&
            (proto == null || s.proto == proto) &&
            (!onlyReachable || (pings[s.id] ?: -1L) > 0) &&
            (q.isEmpty() ||
                s.hostName.contains(q, true) ||
                s.ip.contains(q) ||
                s.countryLong.contains(q, true) ||
                s.countryShort.equals(q, true) ||
                s.operator.contains(q, true))
    }
    return when (sort) {
        SortMode.SCORE -> filtered.sortedByDescending { it.score }
        SortMode.PING -> filtered.sortedWith(
            compareBy<VpnServer> { pingSortKey(pings[it.id]) }.thenByDescending { it.score }
        )
        SortMode.SPEED -> filtered.sortedByDescending { it.speedBps }
        SortMode.SESSIONS -> filtered.sortedWith(compareBy<VpnServer> { it.sessions }.thenByDescending { it.score })
        SortMode.COUNTRY -> filtered.sortedWith(compareBy<VpnServer> { it.countryLong }.thenByDescending { it.score })
    }
}

private fun pingSortKey(p: Long?): Long = when {
    p == null -> Long.MAX_VALUE - 1 // не проверен
    p < 0 -> Long.MAX_VALUE         // не ответил
    else -> p
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServerRepository(app)
    private val settingsStore = SettingsStore(app)
    private val favoritesStore = FavoritesStore(app)
    private val vpn = (app as App).vpn

    private val _state = MutableStateFlow(
        UiState(settings = settingsStore.load(), favorites = favoritesStore.load())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    val vpnStatus: StateFlow<VpnStatus> = vpn.status
    val vpnLog: StateFlow<List<String>> = vpn.log

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pingJob: Job? = null
    private var pendingConnect: VpnServer? = null
    private var permissionRounds = 0
    var pendingExport: VpnServer? = null

    init {
        viewModelScope.launch {
            val cached = repo.loadCache()
            if (cached != null) setServers(cached.servers, cached.savedAt, fromCache = true)
            val stale = cached == null ||
                System.currentTimeMillis() - cached.savedAt > CACHE_TTL_MS
            if (cached == null || (stale && _state.value.settings.autoRefresh)) refresh()
        }
    }

    // ---------- список ----------

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val list = repo.download(_state.value.settings.listUrl)
                setServers(list, System.currentTimeMillis(), fromCache = false)
                _state.update { it.copy(loading = false) }
                if (_state.value.settings.autoPing) pingVisible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hasCache = _state.value.servers.isNotEmpty()
                val text = "Не удалось обновить список: ${e.localizedMessage ?: e.javaClass.simpleName}" +
                    if (hasCache) ". Показан сохранённый список." else ""
                _state.update { it.copy(loading = false, error = text) }
                msg(text)
            }
        }
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
                pings = if (fromCache) st.pings else st.pings.filterKeys { it in byId || it in newFav },
                country = st.country?.takeIf { c -> countries.any { it.code == c } },
            )
        }
    }

    // ---------- пинг ----------

    fun pingVisible() = pingAll(_state.value.visibleServers())

    fun pingAll(targets: List<VpnServer>) {
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

    // ---------- подключение ----------

    fun connect(server: VpnServer) {
        viewModelScope.launch {
            pendingConnect = server
            val name = "${server.flag} ${server.countryLong} · ${server.hostName}"
            when (val r = vpn.connect(OvpnConfig.prepare(server.config), server.id, name)) {
                is ConnectResult.NeedPermission -> {
                    if (permissionRounds++ < 3) {
                        _events.send(UiEvent.LaunchIntent(r.intent))
                    } else {
                        pendingConnect = null
                        permissionRounds = 0
                        msg("Android не выдал разрешение на VPN. Проверьте, не включён ли другой VPN «постоянно».")
                    }
                }
                ConnectResult.Started -> {
                    pendingConnect = null
                    permissionRounds = 0
                    msg("Подключаемся: ${server.countryLong}, ${server.ip}")
                }
                is ConnectResult.Error -> {
                    pendingConnect = null
                    permissionRounds = 0
                    msg(r.message)
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val server = pendingConnect ?: return
        if (granted) {
            connect(server)
        } else {
            pendingConnect = null
            permissionRounds = 0
            viewModelScope.launch { msg("Разрешение не выдано — подключение отменено") }
        }
    }

    fun disconnect() = vpn.disconnect()

    fun clearLog() = vpn.clearLog()

    /** Проверяет пинг у лучших по рейтингу и подключается к самому быстрому. */
    fun connectBest() {
        if (_state.value.findingBest) return
        viewModelScope.launch {
            _state.update { it.copy(findingBest = true) }
            try {
                val st = _state.value
                val candidates = st.visibleServers().ifEmpty { st.servers }
                    .sortedByDescending { it.score }
                    .take(30)
                if (candidates.isEmpty()) {
                    msg("Список серверов пуст — обновите его")
                    return@launch
                }
                val unknown = candidates.filter { _state.value.pings[it.id] == null }
                if (unknown.isNotEmpty()) runPings(unknown, countProgress = false)

                val pings = _state.value.pings
                val alive = candidates.filter { (pings[it.id] ?: -1L) > 0 }
                if (alive.isEmpty()) {
                    msg("Ни один сервер не ответил. Обновите список или проверьте интернет.")
                    return@launch
                }
                // Из близких по пингу берём самый быстрый по скорости канала
                val minPing = alive.minOf { pings.getValue(it.id) }
                val best = alive
                    .filter { pings.getValue(it.id) <= minPing * 3 / 2 + 20 }
                    .maxBy { it.speedBps }
                connect(best)
            } finally {
                _state.update { it.copy(findingBest = false) }
            }
        }
    }

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

    // ---------- фильтры и настройки ----------

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setCountry(c: String?) = _state.update { it.copy(country = c) }
    fun setProto(p: String?) = _state.update { it.copy(proto = p) }
    fun setOnlyReachable(v: Boolean) = _state.update { it.copy(onlyReachable = v) }
    fun setSort(s: SortMode) = _state.update { it.copy(sort = s) }
    fun setTab(t: ListTab) = _state.update { it.copy(tab = t) }

    fun updateSettings(s: AppSettings) {
        settingsStore.save(s)
        _state.update { it.copy(settings = s) }
    }

    private suspend fun msg(text: String) = _events.send(UiEvent.Message(text))

    private companion object {
        const val CACHE_TTL_MS = 30 * 60_000L
    }
}

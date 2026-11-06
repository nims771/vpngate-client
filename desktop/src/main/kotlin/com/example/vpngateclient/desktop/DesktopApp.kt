package com.example.vpngateclient.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.ui.AppCore
import com.example.vpngateclient.ui.CountriesScreen
import com.example.vpngateclient.ui.HomeScreen
import com.example.vpngateclient.ui.LogDialog
import com.example.vpngateclient.ui.SectionCard
import com.example.vpngateclient.ui.ServerDetailsSheet
import com.example.vpngateclient.ui.SettingsScreen
import com.example.vpngateclient.ui.UiEvent
import com.example.vpngateclient.ui.UpdateCard
import com.example.vpngateclient.ui.UpdateDialog
import com.example.vpngateclient.vpn.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

private const val TAB_VPN = 0
private const val TAB_COUNTRIES = 1
private const val TAB_SETTINGS = 2

const val SOFTETHER_DOWNLOAD = "https://www.softether-download.com/en.aspx?product=softether"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(
    core: AppCore,
    backend: WindowsVpnBackend,
    versionName: String,
    initialTab: Int = TAB_VPN,
) {
    val state by core.state.collectAsState()
    val vpnStatus by core.vpnStatus.collectAsState()
    val vpnLog by core.vpnLog.collectAsState()
    val updateState by core.update.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var tab by remember { mutableIntStateOf(initialTab) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        core.events.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                // на Windows отдельного разрешения на VPN нет
                is UiEvent.NeedPermission -> core.onPermissionResult(true)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (tab == TAB_VPN) {
                CenterAlignedTopAppBar(
                    title = { Text("ProXvpn", style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                )
                NavigationBarItem(
                    selected = tab == TAB_VPN, onClick = { tab = TAB_VPN },
                    icon = { Icon(Icons.Default.Shield, null) }, label = { Text("VPN") }, colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == TAB_COUNTRIES, onClick = { tab = TAB_COUNTRIES },
                    icon = { Icon(Icons.Default.Public, null) }, label = { Text("Страны") }, colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") }, colors = itemColors,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                TAB_VPN -> HomeScreen(
                    state = state,
                    status = vpnStatus,
                    lastLog = vpnLog.lastOrNull()?.substringAfter(' '),
                    findServer = core::findServer,
                    onToggle = core::toggleConnection,
                    onOpenLocations = { tab = TAB_COUNTRIES },
                    onRefresh = core::refresh,
                    onSpeedTest = core::runSpeedTest,
                    onSwitchServer = core::switchServer,
                )

                TAB_COUNTRIES -> CountriesScreen(
                    state = state,
                    connectedServerId = vpnStatus.serverId.takeIf { vpnStatus.isConnected },
                    onSelect = { sel ->
                        core.select(sel)
                        tab = TAB_VPN
                    },
                    onRefresh = core::refresh,
                    onPingAll = core::pingAll,
                    onStopPing = core::stopPing,
                    onQuery = core::setQuery,
                    onServerInfo = { detailsId = it.id },
                    onFavorite = core::toggleFavorite,
                )

                else -> SettingsScreen(
                    settings = state.settings,
                    protocols = core.protocols,
                    versionName = versionName,
                    onChange = core::updateSettings,
                    onOpenLog = { showLog = true },
                    showSstpTuning = false,
                    protocolHint = "SSTP — тот же способ, что в Android-версии: выглядит как обычный HTTPS и проходит " +
                        "блокировки. Нужны права администратора (программа попросит их при запуске). " +
                        "SoftEther — через установленный SoftEther VPN Client. «SSTP через Windows» — встроенный клиент Windows.",
                    aboutText = "Серверы: VPN Gate (Университет Цукубы), их держат добровольцы; серверы ведут журналы подключений.\n" +
                        "SSTP: свой клиент + адаптер Wintun (WireGuard LLC). Флаги: Twemoji (CC-BY 4.0).\n" +
                        "Приложение распространяется по GPL v2, исходный код открыт.",
                    updates = {
                        UpdateCard(updateState, core.currentVersion, { core.checkUpdates(true) }, core::installUpdate)
                    },
                    extraContent = {
                        WindowsCard(backend) { text -> scope.launch { snackbar.showSnackbar(text) } }
                    },
                )
            }
        }
    }

    val details = detailsId?.let { core.findServer(it) }
    if (details != null) {
        val connectedHere = vpnStatus.isActive && vpnStatus.serverId == details.id
        ServerDetailsSheet(
            server = details,
            ping = state.pings[details.id],
            pinging = details.id in state.pinging,
            favorite = details.id in state.favorites,
            connectedHere = connectedHere,
            onDismiss = { detailsId = null },
            onConnect = {
                core.connectTo(details)
                detailsId = null
                tab = TAB_VPN
            },
            onDisconnect = {
                core.cancelConnection()
                detailsId = null
            },
            onPing = { core.pingOne(details) },
            onFavorite = { core.toggleFavorite(details) },
            onExport = { exportOvpn(core, details) },
            onCopyIp = {
                clipboard.setText(AnnotatedString(details.ip))
                scope.launch { snackbar.showSnackbar("IP скопирован: ${details.ip}") }
            },
        )
    }

    UpdateDialog(
        state = updateState,
        onUpdate = {
            core.dismissUpdatePrompt()
            core.installUpdate()
        },
        onLater = core::dismissUpdatePrompt,
    )

    if (showLog) {
        LogDialog(
            lines = vpnLog,
            onDismiss = { showLog = false },
            onCopy = {
                clipboard.setText(AnnotatedString(vpnLog.joinToString("\n")))
                scope.launch { snackbar.showSnackbar("Журнал скопирован") }
            },
            onClear = core::clearLog,
        )
    }
}

/** Карточка в настройках: что есть на этом ПК для подключения. */
@Composable
private fun WindowsCard(backend: WindowsVpnBackend, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Windows", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            if (Elevation.isAdmin) "Права администратора: есть ✔" else "Права администратора: нет — SSTP не подключится",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!Elevation.isAdmin) {
            OutlinedButton(onClick = { restartAsAdmin(backend) }, modifier = Modifier.padding(vertical = 6.dp)) {
                Text("Перезапустить от администратора")
            }
        }
        val exe = backend.vpncmd
        Text(
            if (exe != null) "SoftEther VPN Client найден ✔" else "SoftEther VPN Client не установлен",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            exe?.parentFile?.absolutePath
                ?: "Без него доступен только SSTP Windows. Установите «SoftEther VPN Client» и перезапустите программу.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (exe == null) {
                OutlinedButton(onClick = { openUrl(SOFTETHER_DOWNLOAD) }) { Text("Скачать SoftEther") }
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { backend.fixSstpRevocation() }
                        busy = false
                        onMessage(msg)
                    }
                },
            ) { Text("Отключить проверку отзыва для SSTP") }
        }
        Text(
            "Кнопка нужна, если SSTP Windows пишет ошибку 0x80092013 (попросит права администратора).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
        .onFailure { runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() } }
}

private fun exportOvpn(core: AppCore, server: VpnServer) {
    val dialog = FileDialog(null as Frame?, "Сохранить профиль OpenVPN", FileDialog.SAVE).apply {
        file = "vpngate_${server.countryShort.lowercase()}_${server.hostName}.ovpn"
        isVisible = true
    }
    val name = dialog.file ?: return
    val target = File(dialog.directory, if (name.endsWith(".ovpn")) name else "$name.ovpn")
    runCatching { target.writeText(core.ovpnText(server)) }
        .onSuccess { core.notify("Сохранено: ${target.name}") }
        .onFailure { core.notify("Не удалось сохранить: ${it.message}") }
}

/** Протоколы Windows-версии: свой SSTP (как на Android) — основной. */
@Suppress("UNUSED_PARAMETER")
fun desktopProtocols(backend: WindowsVpnBackend): List<Protocol> =
    listOf(Protocol.SSTP, Protocol.SOFTETHER, Protocol.WIN_SSTP)

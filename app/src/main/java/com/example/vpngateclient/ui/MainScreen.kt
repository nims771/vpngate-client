package com.example.vpngateclient.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private const val TAB_VPN = 0
private const val TAB_COUNTRIES = 1
private const val TAB_SETTINGS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val vpnStatus by vm.vpnStatus.collectAsStateWithLifecycle()
    val vpnLog by vm.vpnLog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(TAB_VPN) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> vm.onPermissionResult(result.resultCode == Activity.RESULT_OK) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-openvpn-profile")
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    // Android 13+: без разрешения не видно уведомления VPN
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is UiEvent.LaunchIntent ->
                    runCatching { permissionLauncher.launch(event.intent) }
                        .onFailure { vm.onPermissionResult(false) }
            }
        }
    }

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (tab == TAB_VPN) {
                CenterAlignedTopAppBar(
                    title = { Text("VPN Gate", style = MaterialTheme.typography.titleLarge) },
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
                    selected = tab == TAB_VPN,
                    onClick = { tab = TAB_VPN },
                    icon = { Icon(Icons.Default.Shield, null) },
                    label = { Text("VPN") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == TAB_COUNTRIES,
                    onClick = { tab = TAB_COUNTRIES },
                    icon = { Icon(Icons.Default.Public, null) },
                    label = { Text("Страны") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Настройки") },
                    colors = itemColors,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                TAB_VPN -> HomeScreen(
                    state = state,
                    status = vpnStatus,
                    lastLog = vpnLog.lastOrNull(),
                    findServer = vm::findServer,
                    onToggle = vm::toggleConnection,
                    onOpenLocations = { tab = TAB_COUNTRIES },
                    onRefresh = vm::refresh,
                )

                TAB_COUNTRIES -> CountriesScreen(
                    state = state,
                    connectedServerId = vpnStatus.serverId.takeIf { vpnStatus.isConnected },
                    onSelect = { sel ->
                        vm.select(sel)
                        tab = TAB_VPN
                    },
                    onRefresh = vm::refresh,
                    onPingAll = vm::pingAll,
                    onStopPing = vm::stopPing,
                    onQuery = vm::setQuery,
                    onServerInfo = { detailsId = it.id },
                    onFavorite = vm::toggleFavorite,
                )

                else -> SettingsScreen(
                    settings = state.settings,
                    versionName = versionName,
                    onChange = vm::updateSettings,
                    onOpenLog = { showLog = true },
                )
            }
        }
    }

    // Подробности о сервере
    val details = detailsId?.let { vm.findServer(it) }
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
                vm.connectTo(details)
                detailsId = null
                tab = TAB_VPN
            },
            onDisconnect = {
                vm.cancelConnection()
                detailsId = null
            },
            onPing = { vm.pingOne(details) },
            onFavorite = { vm.toggleFavorite(details) },
            onExport = {
                vm.pendingExport = details
                exportLauncher.launch("vpngate_${details.countryShort.lowercase()}_${details.hostName}.ovpn")
            },
            onCopyIp = {
                clipboard.setText(AnnotatedString(details.ip))
                scope.launch { snackbar.showSnackbar("IP скопирован: ${details.ip}") }
            },
        )
    }

    if (showLog) {
        LogDialog(
            lines = vpnLog,
            onDismiss = { showLog = false },
            onCopy = {
                clipboard.setText(AnnotatedString(vpnLog.joinToString("\n")))
                scope.launch { snackbar.showSnackbar("Журнал скопирован") }
            },
            onClear = vm::clearLog,
        )
    }
}

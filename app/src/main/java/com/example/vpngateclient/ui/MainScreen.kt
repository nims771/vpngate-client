package com.example.vpngateclient.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val vpnStatus by vm.vpnStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> vm.onPermissionResult(result.resultCode == Activity.RESULT_OK) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-openvpn-profile")
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    // Android 13+: без разрешения не видно уведомления VPN с кнопкой «Отключить»
    val context = LocalContext.current
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

    val visible = remember(state) { state.visibleServers() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VPN Gate")
                        val info = buildString {
                            append("${state.servers.size} серверов · ")
                            append(if (state.loading) "обновляется…" else "обновлено ${formatAgo(state.updatedAt)}")
                        }
                        Text(
                            info,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить список")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            StatusCard(
                status = vpnStatus,
                findingBest = state.findingBest,
                onDisconnect = vm::disconnect,
                onConnectBest = vm::connectBest,
            )

            TabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == ListTab.ALL,
                    onClick = { vm.setTab(ListTab.ALL) },
                    text = { Text("Все серверы") },
                )
                Tab(
                    selected = state.tab == ListTab.FAVORITES,
                    onClick = { vm.setTab(ListTab.FAVORITES) },
                    text = { Text("Избранное · ${state.favorites.size}") },
                )
            }

            Spacer(Modifier.height(4.dp))
            FilterBar(
                state = state,
                onQuery = vm::setQuery,
                onCountry = vm::setCountry,
                onProto = vm::setProto,
                onOnlyReachable = vm::setOnlyReachable,
                onSort = vm::setSort,
                onPingAll = vm::pingVisible,
                onStopPing = vm::stopPing,
            )

            if (state.pingRunning) {
                LinearProgressIndicator(
                    progress = {
                        if (state.pingTotal == 0) 0f else state.pingDone.toFloat() / state.pingTotal
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                ) {
                    if (visible.isEmpty()) {
                        item {
                            EmptyState(
                                loading = state.loading && state.servers.isEmpty(),
                                text = when {
                                    state.error != null && state.servers.isEmpty() -> state.error!!
                                    state.tab == ListTab.FAVORITES && state.favorites.isEmpty() ->
                                        "Здесь будут серверы, отмеченные звёздочкой"
                                    state.servers.isEmpty() -> "Список пуст. Потяните вниз, чтобы обновить."
                                    else -> "Нет серверов под выбранные фильтры"
                                },
                                showRetry = state.servers.isEmpty() && !state.loading,
                                onRetry = vm::refresh,
                            )
                        }
                    } else {
                        items(visible, key = { it.id }) { server ->
                            ServerItem(
                                server = server,
                                ping = state.pings[server.id],
                                pinging = server.id in state.pinging,
                                favorite = server.id in state.favorites,
                                connected = vpnStatus.isActive && vpnStatus.serverId == server.id,
                                onClick = { selectedId = server.id },
                                onFavorite = { vm.toggleFavorite(server) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Подробности о сервере
    val selected = selectedId?.let { id ->
        state.servers.firstOrNull { it.id == id } ?: state.favorites[id]
    }
    if (selected != null) {
        val connectedHere = vpnStatus.isActive && vpnStatus.serverId == selected.id
        ServerDetailsSheet(
            server = selected,
            ping = state.pings[selected.id],
            pinging = selected.id in state.pinging,
            favorite = selected.id in state.favorites,
            connectedHere = connectedHere,
            onDismiss = { selectedId = null },
            onConnect = {
                vm.connect(selected)
                selectedId = null
            },
            onDisconnect = {
                vm.disconnect()
                selectedId = null
            },
            onPing = { vm.pingOne(selected) },
            onFavorite = { vm.toggleFavorite(selected) },
            onExport = {
                vm.pendingExport = selected
                exportLauncher.launch("vpngate_${selected.countryShort.lowercase()}_${selected.hostName}.ovpn")
            },
            onCopyIp = {
                clipboard.setText(AnnotatedString(selected.ip))
                scope.launch { snackbar.showSnackbar("IP скопирован: ${selected.ip}") }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = state.settings,
            onDismiss = { showSettings = false },
            onSave = {
                vm.updateSettings(it)
                showSettings = false
            },
        )
    }
}

@Composable
private fun EmptyState(loading: Boolean, text: String, showRetry: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loading) {
            CircularProgressIndicator()
            Text("Загружаем список серверов…", textAlign = TextAlign.Center)
        } else {
            Text(
                text,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRetry) Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}

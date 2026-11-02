package com.example.vpngateclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vpngateclient.data.Selection
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.ui.theme.Green

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountriesScreen(
    state: UiState,
    connectedServerId: String?,
    onSelect: (Selection) -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onStopPing: () -> Unit,
    onQuery: (String) -> Unit,
    onServerInfo: (VpnServer) -> Unit,
    onFavorite: (VpnServer) -> Unit,
) {
    val groups = remember(state.servers, state.pings, state.query) { state.countryGroups() }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize()) {
        // Заголовок с действиями
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Страны", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        state.loading -> "Обновляем список…"
                        state.pingRunning -> "Проверка пинга ${state.pingDone} из ${state.pingTotal}"
                        else -> "${state.servers.size} серверов · обновлено ${formatAgo(state.updatedAt)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.pingRunning) {
                IconButton(onClick = onStopPing) { Icon(Icons.Default.Stop, "Остановить проверку") }
            } else {
                IconButton(onClick = onPingAll, enabled = state.servers.isNotEmpty()) {
                    Icon(Icons.Default.NetworkCheck, "Проверить пинг")
                }
            }
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Default.Refresh, "Обновить список")
            }
        }
        if (state.pingRunning) {
            LinearProgressIndicator(
                progress = { if (state.pingTotal == 0) 0f else state.pingDone.toFloat() / state.pingTotal },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Поиск страны") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Очистить") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.query.isEmpty()) {
                    item(key = "auto") {
                        AutoRow(selected = state.selection == Selection.Auto, onClick = { onSelect(Selection.Auto) })
                    }
                }

                val favorites = state.favorites.values.toList()
                if (favorites.isNotEmpty() && state.query.isEmpty()) {
                    item(key = "favHeader") { SectionHeader("Избранное") }
                    items(state.sortedServers(favorites), key = { "fav-" + it.id }) { s ->
                        ServerRow(
                            server = s,
                            state = state,
                            selected = state.selection == Selection.Server(s.id),
                            connected = connectedServerId == s.id,
                            onClick = { onSelect(Selection.Server(s.id)) },
                            onInfo = { onServerInfo(s) },
                            onFavorite = { onFavorite(s) },
                        )
                    }
                }

                item(key = "allHeader") { SectionHeader("Все страны · ${groups.size}") }

                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            when {
                                state.loading -> "Загружаем список серверов…"
                                state.error != null && state.servers.isEmpty() -> state.error
                                state.servers.isEmpty() -> "Список пуст. Потяните вниз, чтобы обновить."
                                else -> "Ничего не найдено"
                            },
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                groups.forEach { group ->
                    val code = group.info.code
                    val isOpen = code in expanded
                    item(key = "c-$code") {
                        CountryRow(
                            group = group,
                            selected = state.selection == Selection.Country(code),
                            expanded = isOpen,
                            onClick = { onSelect(Selection.Country(code)) },
                            onToggleExpand = {
                                expanded = if (isOpen) expanded - code else expanded + code
                            },
                        )
                    }
                    if (isOpen) {
                        items(group.servers, key = { "s-" + it.id }) { s ->
                            ServerRow(
                                server = s,
                                state = state,
                                selected = state.selection == Selection.Server(s.id),
                                connected = connectedServerId == s.id,
                                onClick = { onSelect(Selection.Server(s.id)) },
                                onInfo = { onServerInfo(s) },
                                onFavorite = { onFavorite(s) },
                                modifier = Modifier.padding(start = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun selectableBorder(selected: Boolean) =
    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Green else MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun AutoRow(selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = selectableBorder(selected),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Bolt, null, tint = Green, modifier = Modifier.padding(13.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Автовыбор", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Самый быстрый сервер; если не подключится — следующий",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) Icon(Icons.Default.CheckCircle, "Выбрано", tint = Green)
        }
    }
}

@Composable
private fun CountryRow(
    group: CountryGroup,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = selectableBorder(selected),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagBox(com.example.vpngateclient.data.countryFlag(group.info.code))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.info.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val alive = group.servers.size
                Text(
                    "$alive " + serversWord(alive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PingBadge(group.bestPing, pinging = false)
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.CheckCircle, "Выбрано", tint = Green, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Показать серверы",
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: VpnServer,
    state: UiState,
    selected: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = selectableBorder(selected),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${server.flag} ${server.hostName}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (connected) "подключено · " else "") +
                        "${formatSpeed(server.speedBps)} · ${server.sessions} сесс. · " + (if (state.settings.protocol == com.example.vpngateclient.vpn.Protocol.OPENVPN) "${server.proto.uppercase()} ${server.port}" else "SSTP ${server.sstpPort}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PingBadge(state.pings[server.id], server.id in state.pinging)
            IconButton(onClick = onFavorite) { StarIcon(server.id in state.favorites) }
            IconButton(onClick = onInfo) {
                Icon(Icons.Outlined.Info, "Подробнее", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun serversWord(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "сервер"
        mod10 in 2..4 && mod100 !in 12..14 -> "сервера"
        else -> "серверов"
    }
}

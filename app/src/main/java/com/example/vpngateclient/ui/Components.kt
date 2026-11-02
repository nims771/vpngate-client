package com.example.vpngateclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.data.countryFlag
import com.example.vpngateclient.vpn.VpnStatus

private val Gold = Color(0xFFFFB300)

// ---------- Карточка состояния VPN ----------

@Composable
fun StatusCard(
    status: VpnStatus,
    lastLog: String?,
    findingBest: Boolean,
    onDisconnect: () -> Unit,
    onConnectBest: () -> Unit,
) {
    val dotColor = when {
        status.isConnected -> Color(0xFF2E7D32)
        status.isActive -> Color(0xFFE08A00)
        else -> MaterialTheme.colorScheme.outline
    }
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status.title, style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    status.isActive && status.serverName != null ->
                        status.serverName + if (status.isConnecting && lastLog != null) "\n${lastLog.substringAfter("  ")}" else ""
                    status.level == "LEVEL_AUTH_FAILED" -> "Сервер отклонил подключение — выберите другой"
                    else -> "Выберите сервер или нажмите «Лучший»"
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (status.isActive) {
                OutlinedButton(onClick = onDisconnect) { Text("Отключить") }
            } else {
                Button(onClick = onConnectBest, enabled = !findingBest) {
                    if (findingBest) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Лучший")
                }
            }
        }
    }
}

// ---------- Поиск и фильтры ----------

@Composable
fun FilterBar(
    state: UiState,
    onQuery: (String) -> Unit,
    onCountry: (String?) -> Unit,
    onProto: (String?) -> Unit,
    onOnlyReachable: (Boolean) -> Unit,
    onSort: (SortMode) -> Unit,
    onPingAll: () -> Unit,
    onStopPing: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Поиск: страна, IP, имя сервера") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.country == null,
                    onClick = { onCountry(null) },
                    label = { Text("🌐 Все страны") },
                )
            }
            items(state.countries, key = { it.code }) { c ->
                FilterChip(
                    selected = state.country == c.code,
                    onClick = { onCountry(if (state.country == c.code) null else c.code) },
                    label = { Text("${countryFlag(c.code)} ${c.name} · ${c.count}") },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.pingRunning) {
                AssistChip(
                    onClick = onStopPing,
                    label = { Text("Стоп ${state.pingDone}/${state.pingTotal}") },
                    leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                )
            } else {
                AssistChip(
                    onClick = onPingAll,
                    label = { Text("Проверить пинг") },
                    leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) },
                )
            }
            SortMenu(state.sort, onSort)
            FilterChip(
                selected = state.onlyReachable,
                onClick = { onOnlyReachable(!state.onlyReachable) },
                label = { Text("Только доступные") },
            )
            FilterChip(
                selected = state.proto == "tcp",
                onClick = { onProto(if (state.proto == "tcp") null else "tcp") },
                label = { Text("TCP") },
            )
            FilterChip(
                selected = state.proto == "udp",
                onClick = { onProto(if (state.proto == "udp") null else "udp") },
                label = { Text("UDP") },
            )
        }
    }
}

@Composable
private fun SortMenu(current: SortMode, onSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current.label) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        onSort(mode)
                        open = false
                    },
                    leadingIcon = {
                        if (mode == current) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

// ---------- Элемент списка ----------

@Composable
fun ServerItem(
    server: VpnServer,
    ping: Long?,
    pinging: Boolean,
    favorite: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (connected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(server.flag, fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.countryLong + if (connected) "  • подключено" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${server.hostName} · ${server.ip}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatSpeed(server.speedBps)} · ${server.sessions} сесс. · ${server.proto.uppercase()} ${server.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                PingBadge(ping, pinging, Modifier.padding(end = 8.dp))
                IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
                        tint = if (favorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun PingBadge(ping: Long?, pinging: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    when {
        pinging -> CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
        ping == null -> Text(
            "— мс",
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ping < 0 -> Surface(modifier, color = MaterialTheme.colorScheme.errorContainer, shape = shape) {
            Text(
                "нет ответа",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        else -> Surface(modifier, color = pingColor(ping).copy(alpha = 0.15f), shape = shape) {
            Text(
                "$ping мс",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = pingColor(ping),
            )
        }
    }
}

@Composable
fun StarIcon(favorite: Boolean) {
    Icon(
        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
        contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
        tint = if (favorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text(text) }
}

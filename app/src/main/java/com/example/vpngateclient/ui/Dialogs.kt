package com.example.vpngateclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.vpn.Protocol
import kotlin.math.roundToInt

// ---------- Подробности о сервере ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailsSheet(
    server: VpnServer,
    ping: Long?,
    pinging: Boolean,
    favorite: Boolean,
    connectedHere: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPing: () -> Unit,
    onFavorite: () -> Unit,
    onExport: () -> Unit,
    onCopyIp: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.flag, fontSize = 40.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.countryLong, style = MaterialTheme.typography.titleLarge)
                    Text(
                        server.hostName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) { StarIcon(favorite) }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            InfoRow("IP-адрес", server.ip)
            InfoRow("Протокол / порт", "${server.proto.uppercase()} / ${server.port}")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Пинг (от вас)",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PingBadge(ping, pinging)
            }
            InfoRow("Скорость канала", formatSpeed(server.speedBps))
            InfoRow("Сессий сейчас", server.sessions.toString())
            InfoRow("Аптайм", formatUptime(server.uptimeMs))
            InfoRow("Всего пользователей", formatNumber(server.totalUsers))
            InfoRow("Передано трафика", formatBytes(server.totalTraffic))
            InfoRow("Рейтинг VPN Gate", formatNumber(server.score))
            InfoRow("Журналирование", server.logType.ifBlank { "—" })
            if (server.operator.isNotBlank()) InfoRow("Оператор", server.operator)
            if (server.message.isNotBlank()) InfoRow("Сообщение", server.message)

            Spacer(Modifier.height(16.dp))

            if (connectedHere) {
                DangerButton("Отключиться", onDisconnect, Modifier.fillMaxWidth())
            } else {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Подключиться")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPing, enabled = !pinging, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Пинг")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(".ovpn")
                }
                OutlinedButton(onClick = onCopyIp, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("IP")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

// ---------- Настройки ----------

@Composable
fun SettingsDialog(
    current: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    var url by remember { mutableStateOf(current.listUrl) }
    var timeout by remember { mutableFloatStateOf(current.pingTimeoutMs.toFloat()) }
    var parallel by remember { mutableFloatStateOf(current.pingParallel.toFloat()) }
    var autoPing by remember { mutableStateOf(current.autoPing) }
    var autoRefresh by remember { mutableStateOf(current.autoRefresh) }
    var protocol by remember { mutableStateOf(current.protocol) }
    var sstpTrustAll by remember { mutableStateOf(current.sstpTrustAll) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Протокол подключения", style = MaterialTheme.typography.titleSmall)
                Protocol.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = protocol == p, onClick = { protocol = p })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = protocol == p, onClick = { protocol = p })
                        Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "SSTP маскируется под обычный HTTPS и чаще проходит через блокировки. " +
                        "OpenVPN во многих сетях блокируется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("SSTP: доверять самоподписанным сертификатам", sstpTrustAll) { sstpTrustAll = it }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Адрес списка серверов") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Если vpngate.net у вас заблокирован, укажите адрес зеркала " +
                        "(…/api/iphone/) — или обновите список, уже подключившись к VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { url = AppSettings.DEFAULT_URL }) { Text("Вернуть стандартный адрес") }

                Spacer(Modifier.height(8.dp))
                Text("Таймаут пинга: ${timeout.roundToInt()} мс")
                Slider(
                    value = timeout,
                    onValueChange = { timeout = (it / 500f).roundToInt() * 500f },
                    valueRange = 500f..5000f,
                    steps = 8,
                )
                Text("Проверять одновременно: ${parallel.roundToInt()} серверов")
                Slider(
                    value = parallel,
                    onValueChange = { parallel = (it / 4f).roundToInt() * 4f },
                    valueRange = 4f..64f,
                    steps = 14,
                )
                SwitchRow("Проверять пинг после обновления", autoPing) { autoPing = it }
                SwitchRow("Обновлять список при запуске (если старше 30 мин)", autoRefresh) { autoRefresh = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    AppSettings(
                        listUrl = url.trim().ifBlank { AppSettings.DEFAULT_URL },
                        pingTimeoutMs = timeout.roundToInt(),
                        pingParallel = parallel.roundToInt(),
                        autoPing = autoPing,
                        autoRefresh = autoRefresh,
                        protocol = protocol,
                        sstpTrustAll = sstpTrustAll,
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- Журнал подключения ----------

@Composable
fun LogDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Журнал подключения") },
        text = {
            if (lines.isEmpty()) {
                Text(
                    "Пока пусто. Нажмите «Подключиться» — здесь появятся сообщения OpenVPN.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onClear) { Text("Очистить") }
                TextButton(onClick = onCopy) { Text("Копировать") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

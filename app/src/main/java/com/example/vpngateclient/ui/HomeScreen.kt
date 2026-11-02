package com.example.vpngateclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpngateclient.data.Selection
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.data.countryFlag
import com.example.vpngateclient.ui.theme.Amber
import com.example.vpngateclient.ui.theme.Green
import com.example.vpngateclient.vpn.VpnStatus
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: UiState,
    status: VpnStatus,
    lastLog: String?,
    findServer: (String) -> VpnServer?,
    onToggle: () -> Unit,
    onOpenLocations: () -> Unit,
    onRefresh: () -> Unit,
) {
    val powerState = when {
        status.isConnected -> PowerState.CONNECTED
        state.connecting || status.isActive -> PowerState.CONNECTING
        else -> PowerState.IDLE
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        PowerButton(powerState, onToggle)

        Text(
            when (powerState) {
                PowerState.CONNECTED -> "Подключено"
                PowerState.CONNECTING -> "Подключение…"
                PowerState.IDLE -> "Не подключено"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = when (powerState) {
                PowerState.CONNECTED -> Green
                PowerState.CONNECTING -> Amber
                PowerState.IDLE -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(6.dp))

        // Сервер, к которому подключены/подключаемся
        val serverLine = when {
            status.isActive && status.serverName != null -> status.serverName ?: ""
            else -> selectionTitle(state, findServer).let { "${it.first} ${it.second}" }
        }
        Text(
            serverLine,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (status.isConnected && state.connectedSince > 0) {
            Spacer(Modifier.height(14.dp))
            ConnectionTimer(state.connectedSince)
        }

        Spacer(Modifier.height(10.dp))
        val detail = when {
            powerState == PowerState.CONNECTING ->
                listOfNotNull(state.attemptInfo, lastLog?.substringAfter("  ")).joinToString("\n")
            status.isConnected -> {
                val ping = status.serverId?.let { state.pings[it] }?.takeIf { it > 0 }
                listOfNotNull(status.protocol, ping?.let { "пинг $it мс" }).joinToString(" · ")
            }
            status.level == "LEVEL_AUTH_FAILED" -> "Сервер отклонил подключение — выберите другой"
            else -> "Нажмите кнопку, чтобы подключиться"
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(24.dp))
        LocationCard(state, findServer, onOpenLocations)

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Text("Серверов", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${state.servers.size}", style = MaterialTheme.typography.headlineSmall)
                val alive = state.pings.values.count { it > 0 }
                Text(
                    if (state.pingRunning) "проверка ${state.pingDone}/${state.pingTotal}"
                    else "доступно: $alive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = if (state.loading) null else onRefresh,
            ) {
                Text("Список", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (state.loading) "обновляется…" else formatAgo(state.updatedAt),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Green,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("обновить", style = MaterialTheme.typography.bodySmall, color = Green)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Заголовок и подпись для текущего выбора. Первая часть — флаг. */
private fun selectionTitle(state: UiState, findServer: (String) -> VpnServer?): Pair<String, String> =
    when (val sel = state.selection) {
        Selection.Auto -> "⚡" to "Автовыбор"
        is Selection.Country -> {
            val name = state.servers.firstOrNull { it.countryShort.equals(sel.code, true) }?.countryLong ?: sel.code
            countryFlag(sel.code) to name
        }
        is Selection.Server -> {
            val s = findServer(sel.id)
            if (s != null) s.flag to "${s.countryLong} · ${s.hostName}" else "🌐" to sel.id
        }
    }

@Composable
private fun LocationCard(state: UiState, findServer: (String) -> VpnServer?, onClick: () -> Unit) {
    val (flag, title) = selectionTitle(state, findServer)
    val subtitle = when (val sel = state.selection) {
        Selection.Auto -> "Лучший сервер выбирается сам"
        is Selection.Country -> {
            val n = state.servers.count { it.countryShort.equals(sel.code, true) }
            "Лучший из $n серверов страны"
        }
        is Selection.Server -> findServer(sel.id)?.let {
            "${formatSpeed(it.speedBps)} · ${it.sessions} сесс."
        } ?: "Сервер пропал из списка"
    } + " · " + state.settings.protocol.label.substringBefore(" (").substringBefore(":")

    SectionCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.selection == Selection.Auto) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Green,
                        modifier = Modifier.padding(13.dp),
                    )
                }
            } else {
                FlagBox(flag)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Выбрать страну",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionTimer(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val total = ((now - since) / 1000).coerceAtLeast(0)
    val text = "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = Green,
        )
    }
}

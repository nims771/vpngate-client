package com.example.vpngateclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.vpn.Protocol
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    onChange: (AppSettings) -> Unit,
    onOpenLog: () -> Unit,
    protocols: List<Protocol> = listOf(Protocol.AUTO, Protocol.SSTP, Protocol.OPENVPN),
    /** Настройки SSTP-движка Android (на Windows SSTP встроен в систему). */
    showSstpTuning: Boolean = true,
    protocolHint: String = "SSTP выглядит как обычный HTTPS и проходит через большинство блокировок. " +
        "OpenVPN во многих сетях блокируется.",
    aboutText: String = "Серверы: VPN Gate (Университет Цукубы), их держат добровольцы; серверы ведут журналы подключений.\n" +
        "SSTP: Open SSTP Client (MIT). OpenVPN: OpenVPN for Android (GPL v2).\n" +
        "Приложение распространяется по GPL v2, исходный код открыт.",
    extraContent: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )

        // ---- Подключение ----
        SectionCard(Modifier.fillMaxWidth()) {
            CardTitle("Протокол")
            protocols.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.protocol == p, onClick = { onChange(settings.copy(protocol = p)) })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.protocol == p, onClick = { onChange(settings.copy(protocol = p)) })
                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Hint(protocolHint)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                "Подключаться при запуске",
                settings.autoConnect,
                subtitle = "Сразу подключиться к выбранной стране/автовыбору",
            ) { onChange(settings.copy(autoConnect = it)) }

            Text(
                "Перебирать серверов при неудаче: ${settings.maxAttempts}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            Slider(
                value = settings.maxAttempts.toFloat(),
                onValueChange = { onChange(settings.copy(maxAttempts = it.roundToInt())) },
                valueRange = 1f..10f,
                steps = 8,
            )
        }

        extraContent()

        // ---- SSTP ----
        if (showSstpTuning) SectionCard(Modifier.fillMaxWidth()) {
            CardTitle("SSTP")
            SwitchRow(
                "Отправлять имя сервера (SNI)",
                settings.sstpSendSni,
                subtitle = "Выключено: фильтры провайдера видят только IP-адрес",
            ) { onChange(settings.copy(sstpSendSni = it)) }
            SwitchRow(
                "Доверять самоподписанным сертификатам",
                settings.sstpTrustAll,
                subtitle = "Включайте, если в журнале CERT_PATH: ERR_VERIFICATION_FAILED",
            ) { onChange(settings.copy(sstpTrustAll = it)) }
            Text("MTU туннеля: ${settings.sstpMtu}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            Slider(
                value = settings.sstpMtu.toFloat(),
                onValueChange = { onChange(settings.copy(sstpMtu = ((it / 50f).roundToInt() * 50).coerceIn(1200, 1500))) },
                valueRange = 1200f..1500f,
                steps = 5,
            )
            Hint("1400 подходит почти всем. Если сайты открываются частично или зависают — попробуйте 1300.")
        }

        // ---- Серверы ----
        SectionCard(Modifier.fillMaxWidth()) {
            CardTitle("Список серверов и пинг")
            var url by remember(settings.listUrl) { mutableStateOf(settings.listUrl) }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Адрес списка") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Row {
                TextButton(
                    onClick = { onChange(settings.copy(listUrl = url.trim().ifBlank { AppSettings.DEFAULT_URL })) },
                    enabled = url.trim() != settings.listUrl,
                ) { Text("Сохранить адрес") }
                TextButton(onClick = {
                    url = AppSettings.DEFAULT_URL
                    onChange(settings.copy(listUrl = AppSettings.DEFAULT_URL))
                }) { Text("По умолчанию") }
            }
            Hint("Если vpngate.net заблокирован, укажите зеркало (…/api/iphone/) или обновите список, уже подключившись к VPN.")

            Spacer(Modifier.height(8.dp))
            Text("Ожидание ответа при пинге: ${settings.pingTimeoutMs} мс", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = settings.pingTimeoutMs.toFloat(),
                onValueChange = { onChange(settings.copy(pingTimeoutMs = ((it / 500f).roundToInt() * 500))) },
                valueRange = 1000f..6000f,
                steps = 9,
            )
            Text("Проверять одновременно: ${settings.pingParallel}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = settings.pingParallel.toFloat(),
                onValueChange = { onChange(settings.copy(pingParallel = ((it / 4f).roundToInt() * 4).coerceAtLeast(4))) },
                valueRange = 4f..32f,
                steps = 6,
            )
            Hint("Если рабочие серверы иногда показывают «нет ответа», увеличьте ожидание и уменьшите число одновременных проверок.")
            SwitchRow("Проверять пинг после обновления", settings.autoPing) { onChange(settings.copy(autoPing = it)) }
            SwitchRow("Обновлять список при запуске", settings.autoRefresh, subtitle = "Если ему больше 30 минут") {
                onChange(settings.copy(autoRefresh = it))
            }
        }

        // ---- Диагностика ----
        SectionCard(Modifier.fillMaxWidth(), onClick = onOpenLog) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Журнал подключения", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Что происходило при последних подключениях",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- О приложении ----
        SectionCard(Modifier.fillMaxWidth()) {
            CardTitle("О приложении")
            Hint("VPN Gate Client $versionName\n$aboutText")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

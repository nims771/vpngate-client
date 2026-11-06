package com.example.vpngateclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vpngateclient.data.UpdateInfo
import com.example.vpngateclient.data.UpdateState

private fun sizeText(info: UpdateInfo) =
    if (info.size > 0) " · ${"%.1f".format(info.size / 1024.0 / 1024.0)} МБ" else ""

/** Окно «Доступна новая версия» — показывается после автоматической проверки. */
@Composable
fun UpdateDialog(state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val info = (state as? UpdateState.Available)?.takeIf { it.prompt }?.info ?: return
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Доступна версия ${info.version}") },
        text = {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Новая версия ProXvpn готова к установке${sizeText(info)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Text(info.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onUpdate) { Text("Обновить") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Позже") } },
    )
}

/** Карточка «Обновления» в настройках: проверка вручную и установка. */
@Composable
fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Обновления", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text("Установлена версия $currentVersion", style = MaterialTheme.typography.bodyLarge)

        val (line, busy) = when (state) {
            UpdateState.Idle -> "Новые версии проверяются при запуске" to false
            UpdateState.Checking -> "Проверяю…" to true
            UpdateState.UpToDate -> "У вас последняя версия ✔" to false
            is UpdateState.Available -> "Доступна версия ${state.info.version}${sizeText(state.info)}" to false
            is UpdateState.Downloading -> "Скачиваю ${state.info.version}: ${(state.progress * 100).toInt()}%" to true
            is UpdateState.Error -> "Ошибка: ${state.message}" to false
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            if (busy && state !is UpdateState.Downloading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is UpdateState.Error -> MaterialTheme.colorScheme.error
                    is UpdateState.Available -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            val installable = state is UpdateState.Available || (state is UpdateState.Error && state.info != null)
            if (installable) {
                Button(onClick = onInstall) { Text("Обновить") }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Проверить обновления") }
        }
    }
}

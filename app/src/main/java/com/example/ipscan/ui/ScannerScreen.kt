package com.example.ipscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ipscan.model.DeviceType
import com.example.ipscan.model.ScanDevice

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onSubnetChange: (String) -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "IP Scanner",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = networkSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.permissionMissing) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Нет доступа к локальной сети",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Android 17 требует отдельное разрешение, чтобы приложение могло обращаться к устройствам в вашей сети.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRequestPermission) { Text("Разрешить") }
                    }
                }
            }

            OutlinedTextField(
                value = state.subnet,
                onValueChange = onSubnetChange,
                label = { Text("Подсеть") },
                placeholder = { Text("192.168.1.0/24") },
                singleLine = true,
                enabled = !state.scanning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onScan() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.scanning) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Остановить", maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = onScan,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text("Сканировать сеть", maxLines = 1)
                    }
                }
            }

            if (state.scanning) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        text = progressLine(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Устройства · ${state.devices.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (state.scanning) {
                    Text(
                        text = "поиск…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.devices.isEmpty() && !state.scanning) {
                    item { EmptyState(state) }
                }
                items(state.devices, key = { it.ip }) { device ->
                    DeviceCard(device)
                }
                item { Spacer(Modifier.height(2.dp)) }
            }

            Text(
                text = INFO_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState(state: ScannerUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (state.finished) "Устройства не найдены" else "Готов к сканированию",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Проверьте, что телефон подключён к той же сети Wi-Fi, что и устройства. " +
                    "Часть устройств не отвечает на запросы даже при включённой защите сети.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: ScanDevice) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.type.icon, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: device.ip,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.name != null) {
                        Text(
                            text = device.ip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = device.type.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            if (device.services.isNotEmpty()) {
                Text(
                    text = "Сервисы: ${device.services.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (device.type == DeviceType.UNKNOWN) {
                Text(
                    text = "Устройство отвечает, но не сообщает имя и службы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = if (device.ports.isEmpty()) {
                    "Открытые порты не обнаружены"
                } else {
                    device.ports.joinToString("  ·  ") { "${it.port} ${it.service}" }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val INFO_NOTE =
    "Имя и тип определяются по UPnP, mDNS, веб-интерфейсу и открытым портам. " +
    "Устройства с включённым брандмауэром могут не отвечать на запросы."

private fun networkSummary(state: ScannerUiState): String {
    val ip = state.localIp
    val transport = state.transport
    return if (ip != null && transport != null) {
        "$transport · ваш IP $ip"
    } else {
        "Нет подключения к Wi-Fi или Ethernet"
    }
}

private fun progressLine(state: ScannerUiState): String =
    if (state.total > 0) "${state.phase}: ${state.done} из ${state.total}" else state.phase

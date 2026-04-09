package io.bearound.bearoundscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bearound.bearoundscan.viewmodel.BeAroundScanState
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.ScanPrecision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: BeAroundScanState,
    onDismiss: () -> Unit,
    onApply: (
        ScanPrecision, MaxQueuedPayloads,
        String, String, String, String,
        Boolean, String, String, String
    ) -> Unit
) {
    var selectedPrecision by remember { mutableStateOf(state.scanPrecision) }
    var selectedQueue by remember { mutableStateOf(state.maxQueuedPayloads) }
    var internalId by remember { mutableStateOf(state.userPropertyInternalId) }
    var email by remember { mutableStateOf(state.userPropertyEmail) }
    var name by remember { mutableStateOf(state.userPropertyName) }
    var custom by remember { mutableStateOf(state.userPropertyCustom) }
    var fgEnabled by remember { mutableStateOf(state.foregroundServiceEnabled) }
    var fgTitle by remember { mutableStateOf(state.foregroundNotificationTitle) }
    var fgText by remember { mutableStateOf(state.foregroundNotificationText) }
    var fgContextual by remember { mutableStateOf(state.foregroundContextualText) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configurações",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("Fechar")
                }
            }

            // SDK Version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Versão do SDK",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "2.3.6",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider()

            // Scan Precision Section
            Text(
                text = "Precisão do Scan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            ScanPrecision.entries.forEach { precision ->
                val description = when (precision) {
                    ScanPrecision.HIGH -> "Contínuo, sync a cada 15s"
                    ScanPrecision.MEDIUM -> "3x (10s scan + 10s pausa) / min"
                    ScanPrecision.LOW -> "1x (10s scan + 50s pausa) / min"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPrecision = precision },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedPrecision == precision,
                        onClick = { selectedPrecision = precision }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            text = precision.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // Queue Section
            Text(
                text = "Fila de Retry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            QueueSizeSelector(
                currentValue = selectedQueue,
                onSelect = { selectedQueue = it }
            )

            HorizontalDivider()

            // Foreground Service Section
            Text(
                text = "Foreground Service",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notificação persistente",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (fgEnabled) "Ativo — mantém scan em OEMs agressivos"
                               else "Desativado — scan silencioso via PendingIntent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = fgEnabled,
                    onCheckedChange = { fgEnabled = it }
                )
            }

            if (fgEnabled) {
                OutlinedTextField(
                    value = fgTitle,
                    onValueChange = { fgTitle = it },
                    label = { Text("Título da notificação") },
                    placeholder = { Text("Vazio = nome do app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = fgText,
                    onValueChange = { fgText = it },
                    label = { Text("Texto padrão (enquanto busca)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = fgContextual,
                    onValueChange = { fgContextual = it },
                    label = { Text("Texto contextual (ao detectar beacons)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            HorizontalDivider()

            // User Properties Section
            Text(
                text = "Propriedades do Usuário",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = internalId,
                onValueChange = { internalId = it },
                label = { Text("ID do usuário") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-Mail do usuário") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do usuário") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("Propriedade customizada") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Apply Button
            Button(
                onClick = {
                    onApply(
                        selectedPrecision, selectedQueue,
                        internalId, email, name, custom,
                        fgEnabled, fgTitle, fgText, fgContextual
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Aplicar Configurações",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
fun QueueSizeSelector(
    currentValue: MaxQueuedPayloads,
    onSelect: (MaxQueuedPayloads) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Tamanho da Fila",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = queueLabel(currentValue),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .width(160.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                MaxQueuedPayloads.entries.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(queueLabel(size)) },
                        onClick = {
                            onSelect(size)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun queueLabel(size: MaxQueuedPayloads): String = when (size) {
    MaxQueuedPayloads.SMALL -> "Small (50)"
    MaxQueuedPayloads.MEDIUM -> "Medium (100)"
    MaxQueuedPayloads.LARGE -> "Large (200)"
    MaxQueuedPayloads.XLARGE -> "XLarge (500)"
}

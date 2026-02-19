package io.bearound.bearoundscan.ui

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
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: BeAroundScanState,
    onDismiss: () -> Unit,
    onApply: (
        ForegroundScanInterval,
        BackgroundScanInterval,
        MaxQueuedPayloads,
        String, String, String, String
    ) -> Unit
) {
    var selectedFg by remember { mutableStateOf(state.foregroundInterval) }
    var selectedBg by remember { mutableStateOf(state.backgroundInterval) }
    var selectedQueue by remember { mutableStateOf(state.maxQueuedPayloads) }
    var internalId by remember { mutableStateOf(state.userPropertyInternalId) }
    var email by remember { mutableStateOf(state.userPropertyEmail) }
    var name by remember { mutableStateOf(state.userPropertyName) }
    var custom by remember { mutableStateOf(state.userPropertyCustom) }

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
                    text = "2.3.0",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider()

            // Sync Intervals Section
            Text(
                text = "Intervalos de Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Foreground Interval
            IntervalSelector(
                label = "Foreground",
                currentValue = "${selectedFg.milliseconds / 1000}s",
                options = ForegroundScanInterval.entries.map { it to "${it.milliseconds / 1000}s" },
                onSelect = { selectedFg = it }
            )

            // Background Interval
            IntervalSelector(
                label = "Background",
                currentValue = "${selectedBg.milliseconds / 1000}s",
                options = BackgroundScanInterval.entries.map { it to "${it.milliseconds / 1000}s" },
                onSelect = { selectedBg = it }
            )

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
                    onApply(selectedFg, selectedBg, selectedQueue, internalId, email, name, custom)
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
fun <T> IntervalSelector(
    label: String,
    currentValue: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentValue,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .width(120.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
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

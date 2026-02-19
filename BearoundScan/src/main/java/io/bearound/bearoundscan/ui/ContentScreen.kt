package io.bearound.bearoundscan.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.bearound.bearoundscan.viewmodel.BeaconSortOption
import io.bearound.bearoundscan.viewmodel.BeaconViewModel
import io.bearound.bearoundscan.viewmodel.BeAroundScanState
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ContentScreen(viewModel: BeaconViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.updatePermissionStatus()
        viewModel.checkBluetoothStatus()
        viewModel.checkNotificationStatus()
        if (permissions.values.all { it }) {
            viewModel.startScanning()
        }
    }

    LaunchedEffect(Unit) {
        if (!viewModel.hasRequiredPermissions()) {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // Settings bottom sheet
    if (state.showSettings) {
        SettingsScreen(
            state = state,
            onDismiss = { viewModel.hideSettings() },
            onApply = { fg, bg, queue, internalId, email, name, custom ->
                viewModel.applySettings(fg, bg, queue, internalId, email, name, custom)
                viewModel.hideSettings()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "BeAroundScan",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions Section
            item {
                PermissionsCard(state = state)
            }

            // Scan Info Section (only when scanning)
            if (state.isScanning) {
                item {
                    ScanInfoCard(state = state, viewModel = viewModel)
                }

                // Sync Info Section
                item {
                    SyncInfoCard(state = state)
                }
            }

            // Controls Section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Start/Stop Button
                    Button(
                        onClick = {
                            if (state.isScanning) {
                                viewModel.stopScanning()
                            } else {
                                if (!viewModel.hasRequiredPermissions()) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    viewModel.startScanning()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isScanning) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        Text(
                            text = if (state.isScanning) "Parar Scan" else "Iniciar Scan",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Settings Button
                    OutlinedButton(
                        onClick = { viewModel.showSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configurações do SDK",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // Sort + Last update
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ordenar:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = state.sortOption.displayName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .width(150.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                BeaconSortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            viewModel.changeSortOption(option)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Last update time
            state.lastScanTime?.let { lastScan ->
                item {
                    Text(
                        text = "Última atualização: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(lastScan)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Beacons List
            if (state.beacons.isEmpty()) {
                item {
                    EmptyBeaconsState(isScanning = state.isScanning)
                }
            } else {
                items(state.beacons) { beacon ->
                    BeaconRow(
                        beacon = beacon,
                        isPinned = state.pinnedBeaconIds.contains(beacon.identifier),
                        onTogglePin = { viewModel.togglePinBeacon(beacon) }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(state: BeAroundScanState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Permissões",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PermissionRow(
                icon = Icons.Default.LocationOn,
                label = "Localização:",
                value = state.locationPermissionStatus,
                color = getLocationPermissionColor(state.locationPermissionStatus)
            )

            PermissionRow(
                icon = Icons.Default.Bluetooth,
                label = "Bluetooth:",
                value = state.bluetoothStatus,
                color = getBluetoothColor(state.bluetoothStatus)
            )

            PermissionRow(
                icon = Icons.Default.Notifications,
                label = "Notificações:",
                value = state.notificationStatus,
                color = getNotificationColor(state.notificationStatus)
            )
        }
    }
}

@Composable
fun PermissionRow(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
fun ScanInfoCard(state: BeAroundScanState, viewModel: BeaconViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Informações do Scan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            InfoRow(label = "Modo:", value = viewModel.scanMode)
            InfoRow(
                label = "Intervalo:",
                value = "${state.currentSyncInterval}s"
            )
            InfoRow(label = "Duração:", value = "${viewModel.scanDuration}s")
        }
    }
}

@Composable
fun SyncInfoCard(state: BeAroundScanState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Informações do Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            InfoRow(
                label = "Último sync:",
                value = state.lastSyncTime?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(it)
                } ?: "---"
            )

            InfoRow(
                label = "Beacons sync:",
                value = if (state.lastSyncTime != null) "${state.lastSyncBeaconCount}" else "---"
            )

            val syncResultColor = when (state.lastSyncSuccess) {
                true -> Color(0xFF4CAF50)
                false -> Color(0xFFF44336)
                null -> MaterialTheme.colorScheme.onSurface
            }

            InfoRow(
                label = "Resposta ingest:",
                value = state.lastSyncResult,
                valueColor = syncResultColor
            )
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
fun EmptyBeaconsState(isScanning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Aguardando próximos scans",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isScanning) {
            Text(
                text = "O sistema está monitorando beacons",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Helper functions for colors
fun getLocationPermissionColor(status: String): Color = when {
    status.contains("Negada") -> Color(0xFFF44336)
    status.contains("Sempre") -> Color(0xFF4CAF50)
    status.contains("Quando em uso") || status.contains("Aguardando") -> Color(0xFFFF9800)
    else -> Color.Gray
}

fun getBluetoothColor(status: String): Color = when {
    status.contains("Ligado") -> Color(0xFF4CAF50)
    status.contains("Desligado") || status.contains("Não autorizado") || status.contains("Não suportado") -> Color(0xFFF44336)
    else -> Color(0xFFFF9800)
}

fun getNotificationColor(status: String): Color = when {
    status.contains("Autorizada") -> Color(0xFF4CAF50)
    status.contains("Negada") -> Color(0xFFF44336)
    else -> Color(0xFFFF9800)
}

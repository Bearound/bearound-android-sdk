package io.bearound.scan

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BeAroundScanApp(viewModel: BeaconViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // Auto-refresh retry queue when switching to that tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            viewModel.refreshRetryQueue()
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.updatePermissionStatus()
        if (permissions.values.all { it }) {
            viewModel.startScanning()
        }
    }

    // Request permissions on first composition
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedTab == 0) "BeAroundScan" else "Retry Queue",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedTab == 0) state.statusMessage else "${state.retryBatchCount} batch(es) pendente(s)",
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
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                    label = { Text("Beacons") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (state.retryBatchCount > 0) {
                                    Badge { Text("${state.retryBatchCount}") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.SyncProblem, contentDescription = null)
                        }
                    },
                    label = { Text("Retry Queue") }
                )
            }
        }
    ) { paddingValues ->
        if (selectedTab == 0) {
            BeaconsContent(
                state = state,
                viewModel = viewModel,
                context = context,
                paddingValues = paddingValues
            )
        } else {
            RetryQueueScreen(
                state = state,
                onRefresh = { viewModel.refreshRetryQueue() },
                paddingValues = paddingValues
            )
        }
    }
}

@Composable
fun BeaconsContent(
    state: BeAroundScanState,
    viewModel: BeaconViewModel,
    context: android.content.Context,
    paddingValues: PaddingValues
) {
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
        }

        // Controls Section
        item {
            ControlsCard(
                state = state,
                onStartStop = {
                    if (state.isScanning) {
                        viewModel.stopScanning()
                    } else {
                        if (!viewModel.hasRequiredPermissions()) {
                            // Open app settings
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else {
                            viewModel.startScanning()
                        }
                    }
                },
                onConfigurationChange = { fg, bg, queue ->
                    viewModel.updateConfiguration(fg, bg, queue)
                },
                onSortOptionChange = { viewModel.changeSortOption(it) }
            )
        }

        // Last scan time
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
                val isPinned = beacon.identifier in state.pinnedBeaconIds
                BeaconCard(
                    beacon = beacon,
                    isPinned = isPinned,
                    onClick = { viewModel.togglePin(beacon.identifier) }
                )
            }
        }
    }
}

@Composable
fun RetryQueueScreen(
    state: BeAroundScanState,
    onRefresh: () -> Unit,
    paddingValues: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Refresh button
        item {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Atualizar fila")
            }
        }

        if (state.retryBatches.isEmpty()) {
            // Empty state
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Text(
                        text = "Fila vazia",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Nenhum batch pendente de envio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // List of batches
            itemsIndexed(state.retryBatches) { index, batch ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF44336).copy(alpha = 0.08f)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF44336).copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Batch header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = null,
                                tint = Color(0xFFF44336),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Batch #${index + 1} — ${batch.size} beacon${if (batch.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF44336)
                            )
                        }

                        // Beacons inside the batch
                        batch.forEach { beacon ->
                            BeaconCard(beacon = beacon)
                        }
                    }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

            InfoRow(label = "Estado do app:", value = if (state.isInBackground) "Background" else "Foreground")
            InfoRow(label = "Modo:", value = viewModel.scanMode)
            InfoRow(
                label = "Intervalo ativo:",
                value = "${state.currentSyncInterval}s (${if (state.isInBackground) "BG" else "FG"})",
                valueColor = MaterialTheme.colorScheme.primary
            )
            InfoRow(label = "Duração do scan:", value = "${viewModel.scanDuration}s")

            if (viewModel.pauseDuration > 0) {
                InfoRow(label = "Tempo de pausa:", value = "${viewModel.pauseDuration}s")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            
            InfoRow(label = "FG Interval:", value = "${state.foregroundInterval.milliseconds / 1000}s")
            InfoRow(label = "BG Interval:", value = "${state.backgroundInterval.milliseconds / 1000}s")
            InfoRow(label = "Fila de retry:", value = "${state.maxQueuedPayloads.value} batches")
            InfoRow(label = "Bluetooth Metadata:", value = "Automático")
            InfoRow(label = "Scan Periódico:", value = if (state.isInBackground) "Desligado (BG)" else "Ligado (FG)")

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (state.isScanning) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (state.isScanning) "Escaneando" else "Parado",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.isScanning) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
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
fun ControlsCard(
    state: BeAroundScanState,
    onStartStop: () -> Unit,
    onConfigurationChange: (ForegroundScanInterval, BackgroundScanInterval, MaxQueuedPayloads) -> Unit,
    onSortOptionChange: (BeaconSortOption) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Start/Stop Button
            Button(
                onClick = onStartStop,
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

            // Foreground Interval Selector
            Text(
                text = "Configuração de Intervalos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Foreground:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = "${state.foregroundInterval.milliseconds / 1000}s",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .width(100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        ForegroundScanInterval.entries.forEach { interval ->
                            DropdownMenuItem(
                                text = { Text("${interval.milliseconds / 1000}s") },
                                onClick = {
                                    onConfigurationChange(interval, state.backgroundInterval, state.maxQueuedPayloads)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Background Interval Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Background:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = "${state.backgroundInterval.milliseconds / 1000}s",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .width(100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        BackgroundScanInterval.entries.forEach { interval ->
                            DropdownMenuItem(
                                text = { Text("${interval.milliseconds / 1000}s") },
                                onClick = {
                                    onConfigurationChange(state.foregroundInterval, interval, state.maxQueuedPayloads)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Max Queued Payloads Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fila de retry:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = when(state.maxQueuedPayloads) {
                            MaxQueuedPayloads.SMALL -> "Small (50)"
                            MaxQueuedPayloads.MEDIUM -> "Medium (100)"
                            MaxQueuedPayloads.LARGE -> "Large (200)"
                            MaxQueuedPayloads.XLARGE -> "XLarge (500)"
                        },
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
                        MaxQueuedPayloads.entries.forEach { size ->
                            val label = when(size) {
                                MaxQueuedPayloads.SMALL -> "Small (50)"
                                MaxQueuedPayloads.MEDIUM -> "Medium (100)"
                                MaxQueuedPayloads.LARGE -> "Large (200)"
                                MaxQueuedPayloads.XLARGE -> "XLarge (500)"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onConfigurationChange(state.foregroundInterval, state.backgroundInterval, size)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Note: Bluetooth Metadata and Periodic Scanning are now automatic in v2.2.0
            // - Bluetooth: Always enabled when permissions granted
            // - Periodic: Enabled in foreground, disabled in background
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Sort Option Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
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
                                    onSortOptionChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
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

@Composable
fun BeaconCard(beacon: Beacon, isPinned: Boolean = false, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        border = if (isPinned) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: title + RSSI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Fixado",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Beacon ${beacon.major}.${beacon.minor}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = beacon.uuid.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${beacon.rssi}dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Badges: iBeacon/Service UUID + proximity + distance
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeLabel = if (beacon.proximity == Beacon.Proximity.BT) "Service UUID" else "iBeacon"
                val badgeColor = if (beacon.proximity == Beacon.Proximity.BT) Color(0xFF2196F3) else Color(0xFF9C27B0)
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Text(
                    text = if (beacon.alreadySynced) "Synced" else "Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(
                            if (beacon.alreadySynced) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                beacon.syncedAt?.let { syncTime ->
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(syncTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = getProximityColor(beacon.proximity),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = getProximityText(beacon.proximity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (beacon.accuracy > 0) {
                    Text(
                        text = String.format("%.1fm", beacon.accuracy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                beacon.txPower?.let { tx ->
                    Text(
                        text = "TX: ${tx}dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Metadata: battery, movements, temperature, firmware
            beacon.metadata?.let { meta ->
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetadataItem(icon = Icons.Default.BatteryFull, label = "${meta.batteryLevel}mV")
                    MetadataItem(icon = Icons.Default.DirectionsWalk, label = "${meta.movements} mov")
                    MetadataItem(icon = Icons.Default.Thermostat, label = "${meta.temperature}\u00B0C")
                    MetadataItem(icon = Icons.Default.Info, label = "v${meta.firmwareVersion}")
                }
            }
        }
    }
}

@Composable
fun MetadataItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

fun getProximityText(proximity: Beacon.Proximity): String = when (proximity) {
    Beacon.Proximity.IMMEDIATE -> "Imediato"
    Beacon.Proximity.NEAR -> "Perto"
    Beacon.Proximity.FAR -> "Longe"
    Beacon.Proximity.BT -> "BT"
    Beacon.Proximity.UNKNOWN -> "Desconhecido"
}

fun getProximityColor(proximity: Beacon.Proximity): Color = when (proximity) {
    Beacon.Proximity.IMMEDIATE -> Color(0xFF4CAF50)
    Beacon.Proximity.NEAR -> Color(0xFFFF9800)
    Beacon.Proximity.FAR -> Color(0xFFF44336)
    Beacon.Proximity.BT -> Color(0xFF2196F3)
    Beacon.Proximity.UNKNOWN -> Color.Gray
}


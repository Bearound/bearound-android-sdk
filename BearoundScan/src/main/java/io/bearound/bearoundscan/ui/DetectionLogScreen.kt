package io.bearound.bearoundscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bearound.bearoundscan.model.DetectionLogEntry
import io.bearound.bearoundscan.viewmodel.BeaconViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class LogViewMode(val label: String) {
    DETAIL("Detalhado"),
    GROUPED("Por Minuto")
}

enum class LogModeFilter(val label: String) {
    ALL("Tudo"),
    FOREGROUND("FG"),
    BACKGROUND("BG")
}

enum class LogTypeFilter(val label: String) {
    ALL("Tudo"),
    SERVICE_UUID("Service UUID"),
    IBEACON("iBeacon")
}

private data class MinuteGroup(
    val date: Date,
    val total: Int,
    val fgCount: Int,
    val bgCount: Int,
    val uniqueBeacons: Int
)

@Composable
fun DetectionLogScreen(
    viewModel: BeaconViewModel,
    paddingValues: PaddingValues
) {
    val foregroundLog by viewModel.foregroundLog.collectAsState()
    val backgroundLog by viewModel.backgroundLog.collectAsState()

    var viewMode by remember { mutableStateOf(LogViewMode.DETAIL) }
    var modeFilter by remember { mutableStateOf(LogModeFilter.ALL) }
    var typeFilter by remember { mutableStateOf(LogTypeFilter.ALL) }

    val sourceLog = when (modeFilter) {
        LogModeFilter.ALL -> (foregroundLog + backgroundLog).sortedByDescending { it.timestamp }
        LogModeFilter.FOREGROUND -> foregroundLog
        LogModeFilter.BACKGROUND -> backgroundLog
    }

    val filteredLog = sourceLog.filter { entry ->
        when (typeFilter) {
            LogTypeFilter.ALL -> true
            LogTypeFilter.SERVICE_UUID -> entry.discoverySource == "Service UUID" || entry.discoverySource == "Both"
            LogTypeFilter.IBEACON -> entry.discoverySource == "iBeacon" || entry.discoverySource == "Both"
        }
    }

    val groupedByMinute = remember(filteredLog) {
        val calendar = Calendar.getInstance()
        filteredLog.groupBy { entry ->
            calendar.time = entry.timestamp
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }.map { (millis, entries) ->
            MinuteGroup(
                date = Date(millis),
                total = entries.size,
                fgCount = entries.count { !it.isBackground },
                bgCount = entries.count { it.isBackground },
                uniqueBeacons = entries.map { "${it.major}.${it.minor}" }.toSet().size
            )
        }.sortedByDescending { it.date }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Filter controls
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // View mode segmented
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LogViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = viewMode == mode,
                        onClick = { viewMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, LogViewMode.entries.size)
                    ) {
                        Text(mode.label, fontSize = 12.sp)
                    }
                }
            }

            // Mode filter segmented
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LogModeFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = modeFilter == filter,
                        onClick = { modeFilter = filter },
                        shape = SegmentedButtonDefaults.itemShape(index, LogModeFilter.entries.size)
                    ) {
                        Text(filter.label, fontSize = 12.sp)
                    }
                }
            }

            // Type filter segmented
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LogTypeFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = typeFilter == filter,
                        onClick = { typeFilter = filter },
                        shape = SegmentedButtonDefaults.itemShape(index, LogTypeFilter.entries.size)
                    ) {
                        Text(filter.label, fontSize = 12.sp)
                    }
                }
            }

            // Counts + Clear button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FG:${foregroundLog.size} BG:${backgroundLog.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { viewModel.clearDetectionLog() },
                    enabled = foregroundLog.isNotEmpty() || backgroundLog.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Limpar", fontSize = 12.sp)
                }
            }
        }

        // Content
        if (filteredLog.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ListAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Nenhuma detecção registrada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else if (viewMode == LogViewMode.GROUPED) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(groupedByMinute, key = { it.date.time }) { group ->
                    MinuteGroupRow(group)
                    HorizontalDivider()
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(filteredLog, key = { it.id }) { entry ->
                    LogEntryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MinuteGroupRow(group: MinuteGroup) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateFormat.format(group.date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${group.total} detecções",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.fgCount > 0) {
                    BadgeCount("FG", group.fgCount, Color(0xFF4CAF50))
                }
                if (group.bgCount > 0) {
                    BadgeCount("BG", group.bgCount, Color(0xFFFF9800))
                }
            }

            Text(
                text = "${group.uniqueBeacons} beacon${if (group.uniqueBeacons == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BadgeCount(label: String, count: Int, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .background(color, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogEntryRow(entry: DetectionLogEntry) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ID + timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${entry.major}.${entry.minor}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = dateFormat.format(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Badges row: RSSI, proximity, source, mode
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RSSI: ${entry.rssi}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Proximity badge
            val proximityColor = when (entry.proximity) {
                "Imediato" -> Color(0xFF4CAF50)
                "Perto" -> Color(0xFF2196F3)
                "Longe" -> Color(0xFFFF9800)
                "Bluetooth" -> Color(0xFF9C27B0)
                else -> Color.Gray
            }
            LogBadge(entry.proximity, proximityColor)

            // Source badge(s)
            when (entry.discoverySource) {
                "Service UUID" -> LogBadge("SU", Color(0xFF9C27B0))
                "iBeacon" -> LogBadge("iB", Color(0xFF3F51B5))
                "Both" -> {
                    LogBadge("SU", Color(0xFF9C27B0))
                    LogBadge("iB", Color(0xFF3F51B5))
                }
                else -> LogBadge("N", Color(0xFF009688))
            }

            // Mode badge
            if (entry.isBackground) {
                LogBadge("BG", Color(0xFFFF9800))
            } else {
                LogBadge("FG", Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
private fun LogBadge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

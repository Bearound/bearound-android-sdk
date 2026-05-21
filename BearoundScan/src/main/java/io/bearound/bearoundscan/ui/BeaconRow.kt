package io.bearound.bearoundscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bearound.sdk.models.Beacon
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BeaconRow(beacon: Beacon, isPinned: Boolean = false, onTogglePin: () -> Unit = {}) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val rowAlpha = if (beacon.isStale) 0.5f else 1.0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePin() }
            .padding(vertical = 8.dp)
            .alpha(rowAlpha),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header: Beacon ID + pin + RSSI (smoothed + raw)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Fixado",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFFF9800)
                    )
                }
                Text(
                    text = "Beacon ${beacon.major}.${beacon.minor}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (beacon.isStale) {
                    Text(
                        text = "STALE",
                        modifier = Modifier
                            .background(Color(0xFF9E9E9E), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // RSSI: smoothed (primary) + raw (small)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${beacon.rssi}dB",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                beacon.rssiRaw?.let { raw ->
                    Text(
                        text = "(raw ${raw})",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // UUID
        Text(
            text = beacon.uuid.toString().uppercase(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            fontSize = 10.sp
        )

        // Proximity + Distance + Source badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Proximity indicator
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Distance
            if (beacon.accuracy > 0) {
                Text(
                    text = String.format("%.1fm", beacon.accuracy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Discovery source badges
            SourceBadge(text = "Service UUID", color = Color(0xFF7B1FA2))
            beacon.txPower?.let {
                SourceBadge(text = "iBeacon", color = Color(0xFF3F51B5))
            }
        }

        // Metadata row
        beacon.metadata?.let { meta ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetadataChip(label = "Bat", value = "${meta.batteryLevel}mV")
                MetadataChip(label = "Temp", value = "${meta.temperature}\u00B0C")
                MetadataChip(label = "Mov", value = "${meta.movements}")
                MetadataChip(label = "FW", value = "v${meta.firmwareVersion}")
            }
        }

        // RSSI window stats \u2014 visible quality of the smoothed signal
        beacon.rssiSamples?.let { stats ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEDF7EE), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetadataChip(label = "n", value = "${stats.count}")
                MetadataChip(label = "min", value = "${stats.min}")
                MetadataChip(label = "max", value = "${stats.max}")
                MetadataChip(label = "avg", value = String.format("%.1f", stats.avg))
                MetadataChip(label = "\u03C3", value = String.format("%.1f", stats.stdDev))
            }
        }

        // Debug: detection timestamp + age + sync time
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Detection time
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Det: ${dateFormat.format(beacon.timestamp)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Age
            val ageSeconds = ((System.currentTimeMillis() - beacon.timestamp.time) / 1000).toInt()
            val ageColor = when {
                ageSeconds < 10 -> Color(0xFF4CAF50)
                ageSeconds < 30 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
            Text(
                text = "(${ageSeconds}s ago)",
                fontSize = 10.sp,
                color = ageColor
            )

            // Sync time
            beacon.syncedAt?.let { syncTime ->
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color(0xFF4CAF50)
                )
                Text(
                    text = "Sync: ${dateFormat.format(syncTime)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SourceBadge(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp
    )
}

@Composable
fun MetadataChip(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
}

fun getProximityText(proximity: Beacon.Proximity): String = when (proximity) {
    Beacon.Proximity.IMMEDIATE -> "Imediato"
    Beacon.Proximity.NEAR -> "Perto"
    Beacon.Proximity.FAR -> "Longe"
    Beacon.Proximity.BT -> "Bluetooth"
    Beacon.Proximity.UNKNOWN -> "Desconhecido"
}

fun getProximityColor(proximity: Beacon.Proximity): Color = when (proximity) {
    Beacon.Proximity.IMMEDIATE -> Color(0xFF4CAF50)
    Beacon.Proximity.NEAR -> Color(0xFFFF9800)
    Beacon.Proximity.FAR -> Color(0xFFF44336)
    Beacon.Proximity.BT -> Color(0xFF2196F3)
    Beacon.Proximity.UNKNOWN -> Color.Gray
}

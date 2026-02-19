package io.bearound.bearoundscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bearound.sdk.models.Beacon

@Composable
fun BeaconRow(beacon: Beacon, isPinned: Boolean = false, onTogglePin: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePin() },
        border = if (isPinned) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
        ) else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Beacon ID + pin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Beacon ${beacon.major}.${beacon.minor}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Fixado",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // UUID
            Text(
                text = beacon.uuid.toString().uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            // Proximity + Distance row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                            style = MaterialTheme.typography.bodySmall,
                            color = getProximityColor(beacon.proximity),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Distance
                    if (beacon.accuracy > 0) {
                        Text(
                            text = String.format("%.1fm", beacon.accuracy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // RSSI
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
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

            // Discovery Source Badges
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Service UUID badge (all Android beacons)
                SourceBadge(
                    text = "Service UUID",
                    color = Color(0xFF7B1FA2) // Purple
                )

                // If beacon has metadata from iBeacon parse as well
                beacon.txPower?.let {
                    SourceBadge(
                        text = "iBeacon",
                        color = Color(0xFF3F51B5) // Indigo
                    )
                }
            }

            // Metadata row (if available)
            beacon.metadata?.let { meta ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetadataChip(label = "Bat", value = "${meta.batteryLevel}mV")
                    MetadataChip(label = "FW", value = meta.firmwareVersion)
                    MetadataChip(label = "Mov", value = "${meta.movements}")
                    MetadataChip(label = "Temp", value = "${meta.temperature}°C")
                }
            }
        }
    }
}

@Composable
fun SourceBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
    }
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
    Beacon.Proximity.IMMEDIATE -> Color(0xFF4CAF50)  // Green
    Beacon.Proximity.NEAR -> Color(0xFFFF9800)        // Orange
    Beacon.Proximity.FAR -> Color(0xFFF44336)         // Red
    Beacon.Proximity.BT -> Color(0xFF2196F3)          // Blue
    Beacon.Proximity.UNKNOWN -> Color.Gray
}

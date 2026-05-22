package io.bearound.bearoundscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bearound.bearoundscan.viewmodel.BeAroundScanState
import io.bearound.bearoundscan.viewmodel.GeofenceEvent
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun GeofenceDebugCard(
    state: BeAroundScanState,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Tick once per second so we can render live "X seg atrás" ages.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug Geofence",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (state.geofenceEvents.isNotEmpty()) {
                    IconButton(onClick = onClearLog) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpar log")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live status rows
            StatusRow(
                icon = { Icon(Icons.Default.LocationOn, contentDescription = null,
                    tint = if (state.isInBeaconRegion) Color(0xFF2E7D32) else Color.Gray) },
                label = "Zona do beacon:",
                value = if (state.isInBeaconRegion) "DENTRO" else "fora",
                emphasized = state.isInBeaconRegion,
                color = if (state.isInBeaconRegion) Color(0xFF2E7D32) else Color.Gray
            )

            // Entered/exited timestamps with live age (poll via nowMs 1Hz)
            state.lastEnteredRegionAt?.let { ts ->
                val ageSec = ((nowMs - ts.time) / 1000).coerceAtLeast(0)
                DetailRow(
                    "Entrou às:",
                    "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(ts)}  (${formatAge(ageSec)})"
                )
            }
            state.lastExitedRegionAt?.let { ts ->
                val ageSec = ((nowMs - ts.time) / 1000).coerceAtLeast(0)
                DetailRow(
                    "Saiu às:",
                    "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(ts)}  (${formatAge(ageSec)})"
                )
            }

            StatusRow(
                icon = { Icon(Icons.Default.Wifi, contentDescription = null,
                    tint = if (state.isActiveScanRunning) Color(0xFF2E7D32) else Color.Gray) },
                label = "Scan ativo:",
                value = if (state.isActiveScanRunning) "LIGADO" else "desligado",
                emphasized = state.isActiveScanRunning,
                color = if (state.isActiveScanRunning) Color(0xFF2E7D32) else Color.Gray
            )

            StatusRow(
                icon = { Icon(Icons.Default.MyLocation, contentDescription = null,
                    tint = if (state.isCapturingLocation) Color(0xFF1565C0) else Color.Gray) },
                label = "Captura GPS:",
                value = if (state.isCapturingLocation) "EM ANDAMENTO…" else "idle",
                emphasized = state.isCapturingLocation,
                color = if (state.isCapturingLocation) Color(0xFF1565C0) else Color.Gray
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow("Última abertura:", state.lastCaptureOpenReason)
            DetailRow("Último fechamento:", state.lastCaptureOutcome)

            val lastLoc = state.lastCapturedLocation
            if (lastLoc != null) {
                val acc = if (lastLoc.hasAccuracy()) lastLoc.accuracy.toInt() else -1
                DetailRow(
                    "Última coord:",
                    "%.5f, %.5f ±%dm".format(lastLoc.latitude, lastLoc.longitude, acc)
                )
            } else {
                DetailRow("Última coord:", "—")
            }

            state.lastCaptureCompletedAt?.let { ts ->
                val ageSec = ((nowMs - ts.time) / 1000).coerceAtLeast(0)
                val ageLabel = formatAge(ageSec)
                DetailRow(
                    "Concluído em:",
                    "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(ts)}  ($ageLabel)"
                )
            }

            if (state.geofenceEvents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Eventos recentes",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.geofenceEvents.take(10).forEach { event ->
                        GeofenceEventRow(event, nowMs = nowMs)
                    }
                }
            }
        }
    }
}

private fun formatAge(ageSec: Long): String = when {
    ageSec < 60 -> "${ageSec}s atrás"
    ageSec < 3600 -> "${ageSec / 60}min ${ageSec % 60}s atrás"
    else -> "${ageSec / 3600}h ${(ageSec % 3600) / 60}min atrás"
}

@Composable
private fun StatusRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    emphasized: Boolean,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(28.dp).size(20.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun GeofenceEventRow(event: GeofenceEvent, nowMs: Long) {
    val ageSec = ((nowMs - event.timestamp.time) / 1000).coerceAtLeast(0)
    val (color, title) = when (event.kind) {
        GeofenceEvent.Kind.REGION_ENTER -> Color(0xFF2E7D32) to "ENTROU NA ZONA"
        GeofenceEvent.Kind.REGION_EXIT -> Color(0xFFE65100) to "SAIU DA ZONA"
        GeofenceEvent.Kind.SCAN_ACTIVE -> Color(0xFF00897B) to "SCAN LIGADO"
        GeofenceEvent.Kind.SCAN_PAUSED -> Color(0xFF616161) to "SCAN PAUSADO"
        GeofenceEvent.Kind.CAPTURE_STARTED -> Color(0xFF1565C0) to "GPS DISPARADO"
        GeofenceEvent.Kind.CAPTURE_FIX -> Color(0xFF2E7D32) to "FIX OK"
        GeofenceEvent.Kind.CAPTURE_NO_FIX -> Color(0xFFC62828) to "SEM FIX"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(event.timestamp)} · ${formatAge(ageSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Text(
                text = event.detail,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp
            )
        }
    }
}

package io.bearound.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Date

class NotificationManager(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "beacon_detection"
        private const val CHANNEL_NAME = "Beacon Detection"
        private const val NOTIFICATION_COOLDOWN = 300000L // 5 minutes in milliseconds
    }

    private var lastNotificationDate: Date? = null

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações quando beacons são detectados"
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun notifyBeaconRegionEntered(beaconCount: Int) {
        // Avoid too frequent notifications
        lastNotificationDate?.let { lastDate ->
            val timeSinceLastNotification = Date().time - lastDate.time
            if (timeSinceLastNotification < NOTIFICATION_COOLDOWN) {
                println("[NotificationManager] Notification ignored - too recent (${timeSinceLastNotification / 1000}s ago)")
                return
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beacon Detectado")
            .setContentText("Você entrou na zona de $beaconCount beacon${if (beaconCount == 1) "" else "s"}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(beaconCount, notification)
            lastNotificationDate = Date()
            println("[NotificationManager] Notification sent: $beaconCount beacon(s) detected")
        } catch (e: SecurityException) {
            println("[NotificationManager] Failed to send notification: ${e.message}")
        }
    }
}


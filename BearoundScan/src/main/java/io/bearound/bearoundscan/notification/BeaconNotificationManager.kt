package io.bearound.bearoundscan.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Date

class BeaconNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "BeaconNotifManager"
        private const val CHANNEL_ID = "BEAROUND_SDK"
        private const val CHANNEL_NAME = "BeAroundSDK"

        private const val NOTIFICATION_ID_SCANNING = 1001
        private const val NOTIFICATION_ID_BEACON = 1002
        private const val NOTIFICATION_ID_SYNC = 1003
        private const val NOTIFICATION_ID_BACKGROUND = 1004
    }

    private enum class NotificationIdentifier {
        SCANNING_STARTED,
        SCANNING_STOPPED,
        BEACON_DETECTED,
        BEACON_DETECTED_BACKGROUND,
        API_SYNC_STARTED,
        API_SYNC_SUCCESS,
        API_SYNC_FAILED,
        APP_RELAUNCHED
    }

    private val lastNotificationDates = mutableMapOf<NotificationIdentifier, Date>()
    private val cooldowns = mapOf(
        NotificationIdentifier.SCANNING_STARTED to 10_000L,
        NotificationIdentifier.SCANNING_STOPPED to 10_000L,
        NotificationIdentifier.BEACON_DETECTED to 300_000L,
        NotificationIdentifier.BEACON_DETECTED_BACKGROUND to 60_000L,
        NotificationIdentifier.API_SYNC_STARTED to 30_000L,
        NotificationIdentifier.API_SYNC_SUCCESS to 60_000L,
        NotificationIdentifier.API_SYNC_FAILED to 30_000L,
        NotificationIdentifier.APP_RELAUNCHED to 60_000L
    )

    var enableScanningNotifications = true
    var enableBeaconNotifications = true
    var enableAPISyncNotifications = true
    var enableBackgroundNotifications = true

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notificações do BeAround SDK"
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun notifyScanningStarted() {
        if (!enableScanningNotifications) return
        if (!canSendNotification(NotificationIdentifier.SCANNING_STARTED)) return

        sendNotification(
            id = NOTIFICATION_ID_SCANNING,
            identifier = NotificationIdentifier.SCANNING_STARTED,
            title = "Escaneamento Iniciado",
            body = "BeAroundSDK está escaneando beacons",
            withSound = true
        )
    }

    fun notifyScanningStopped() {
        if (!enableScanningNotifications) return
        if (!canSendNotification(NotificationIdentifier.SCANNING_STOPPED)) return

        sendNotification(
            id = NOTIFICATION_ID_SCANNING,
            identifier = NotificationIdentifier.SCANNING_STOPPED,
            title = "Escaneamento Parado",
            body = "BeAroundSDK parou de escanear",
            withSound = true
        )
    }

    fun notifyBeaconDetected(beaconCount: Int, isBackground: Boolean = false) {
        if (!enableBeaconNotifications) return

        val identifier = if (isBackground) {
            NotificationIdentifier.BEACON_DETECTED_BACKGROUND
        } else {
            NotificationIdentifier.BEACON_DETECTED
        }

        if (!canSendNotification(identifier)) return

        val title = if (isBackground) "Beacon Detectado (Background)" else "Beacon Detectado"
        val body = "$beaconCount beacon${if (beaconCount == 1) "" else "s"} detectado${if (beaconCount == 1) "" else "s"}"

        sendNotification(
            id = if (isBackground) NOTIFICATION_ID_BACKGROUND else NOTIFICATION_ID_BEACON,
            identifier = identifier,
            title = title,
            body = body,
            withSound = true
        )
    }

    fun notifyAPISyncStarted(beaconCount: Int) {
        if (!enableAPISyncNotifications) return
        if (!canSendNotification(NotificationIdentifier.API_SYNC_STARTED)) return

        sendNotification(
            id = NOTIFICATION_ID_SYNC,
            identifier = NotificationIdentifier.API_SYNC_STARTED,
            title = "Sincronizando",
            body = "Enviando $beaconCount beacon${if (beaconCount == 1) "" else "s"} para o servidor",
            withSound = false
        )
    }

    fun notifyAPISyncCompleted(beaconCount: Int, success: Boolean) {
        if (!enableAPISyncNotifications) return

        val identifier = if (success) {
            NotificationIdentifier.API_SYNC_SUCCESS
        } else {
            NotificationIdentifier.API_SYNC_FAILED
        }

        if (!canSendNotification(identifier)) return

        val title = if (success) "Sync Completo" else "Sync Falhou"
        val body = if (success) {
            "$beaconCount beacon${if (beaconCount == 1) "" else "s"} enviado${if (beaconCount == 1) "" else "s"} com sucesso"
        } else {
            "Falha ao enviar $beaconCount beacon${if (beaconCount == 1) "" else "s"}. Tentando novamente."
        }

        sendNotification(
            id = NOTIFICATION_ID_SYNC,
            identifier = identifier,
            title = title,
            body = body,
            withSound = !success
        )
    }

    fun notifyAppRelaunchedInBackground() {
        if (!enableBackgroundNotifications) return
        if (!canSendNotification(NotificationIdentifier.APP_RELAUNCHED)) return

        sendNotification(
            id = NOTIFICATION_ID_BACKGROUND,
            identifier = NotificationIdentifier.APP_RELAUNCHED,
            title = "App Reativado",
            body = "BeAroundSDK detectou região de beacons em segundo plano",
            withSound = true
        )
    }

    private fun canSendNotification(identifier: NotificationIdentifier): Boolean {
        val lastDate = lastNotificationDates[identifier] ?: return true
        val cooldown = cooldowns[identifier] ?: return true

        val elapsed = Date().time - lastDate.time
        if (elapsed < cooldown) {
            Log.d(TAG, "Cooldown ativo para ${identifier.name} (${(cooldown - elapsed) / 1000}s restantes)")
            return false
        }

        return true
    }

    private fun sendNotification(
        id: Int,
        identifier: NotificationIdentifier,
        title: String,
        body: String,
        withSound: Boolean
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                if (withSound) {
                    setDefaults(NotificationCompat.DEFAULT_SOUND)
                }
            }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
            lastNotificationDates[identifier] = Date()
            Log.d(TAG, "Notificação enviada: $title - $body")
        } catch (e: SecurityException) {
            Log.e(TAG, "Falha ao enviar notificação: ${e.message}")
        }
    }
}

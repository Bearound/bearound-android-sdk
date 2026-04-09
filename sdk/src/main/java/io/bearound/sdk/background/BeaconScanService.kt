package io.bearound.sdk.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.bearound.sdk.models.ForegroundScanConfig

/**
 * Foreground Service that keeps the app process alive in background.
 * Does NOT manage BLE scan itself — just prevents the OS from killing the process
 * so that BeaconManager continues working.
 *
 * Opt-in only: the consuming app must call enableForegroundScanning() on BeAroundSDK.
 */
class BeaconScanService : Service() {

    companion object {
        private const val TAG = "BeAroundSDK-FgService"
        private const val DEFAULT_CHANNEL_ID = "bearound_scan_service"
        private const val NOTIFICATION_ID = 19850

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, config: ForegroundScanConfig) {
            val intent = Intent(context, BeaconScanService::class.java).apply {
                putExtra(EXTRA_TITLE, config.notificationTitle)
                putExtra(EXTRA_TEXT, config.notificationText)
                putExtra(EXTRA_ICON, config.notificationIcon ?: 0)
                putExtra(EXTRA_CHANNEL_ID, config.notificationChannelId)
                putExtra(EXTRA_CHANNEL_NAME, config.notificationChannelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BeaconScanService::class.java))
        }

        fun updateNotification(context: Context, title: String, text: String) {
            if (!isRunning) return
            val intent = Intent(context, BeaconScanService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_UPDATE_ONLY, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_UPDATE_ONLY = "update_only"
    }

    private var currentIcon: Int? = null
    private var currentChannelId: String? = null
    private var currentChannelName: String = "Serviço de monitoramento da região"

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "BeaconScanService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawTitle = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val title = rawTitle.ifEmpty { resolveAppName() }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Encontrando promoções"
        val isUpdateOnly = intent?.getBooleanExtra(EXTRA_UPDATE_ONLY, false) ?: false

        if (isUpdateOnly) {
            val nm = getSystemService(NotificationManager::class.java)
            val notification = buildNotification(title, text, currentIcon, currentChannelId, currentChannelName)
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification updated: $title — $text")
            return START_STICKY
        }

        val icon = intent?.getIntExtra(EXTRA_ICON, 0)?.takeIf { it != 0 }
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Serviço de monitoramento da região"

        currentIcon = icon
        currentChannelId = channelId
        currentChannelName = channelName

        val notification = buildNotification(title, text, icon, channelId, channelName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "BeaconScanService started in foreground")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "BeaconScanService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveAppName(): String {
        return try {
            applicationInfo.loadLabel(packageManager).toString()
        } catch (_: Exception) {
            "Bearound"
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        icon: Int?,
        channelId: String?,
        channelName: String
    ): Notification {
        val resolvedChannelId = channelId ?: DEFAULT_CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                resolvedChannelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val resolvedIcon = icon ?: android.R.drawable.stat_sys_data_bluetooth

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, resolvedChannelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(resolvedIcon)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(resolvedIcon)
                .setOngoing(true)
                .build()
        }
    }
}

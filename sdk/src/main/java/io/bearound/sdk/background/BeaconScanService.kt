package io.bearound.sdk.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            // Android 14+ recusa um FGS do tipo connectedDevice se o app não tiver
            // nenhuma das permissões Bluetooth (SecurityException → crash do processo).
            // Sem permissão de Bluetooth não há scan de qualquer forma, então não faz
            // sentido subir o serviço: pulamos e evitamos o crash.
            if (!hasBluetoothForegroundServicePermission(context)) {
                Log.w(TAG, "Skipping foreground service start — no Bluetooth permission (FGS connectedDevice would crash on Android 14+)")
                return
            }
            val intent = Intent(context, BeaconScanService::class.java).apply {
                putExtra(EXTRA_TITLE, config.notificationTitle)
                putExtra(EXTRA_TEXT, config.notificationText)
                putExtra(EXTRA_ICON, config.notificationIcon ?: 0)
                putExtra(EXTRA_CHANNEL_ID, config.notificationChannelId)
                putExtra(EXTRA_CHANNEL_NAME, config.notificationChannelName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // Android 12+ lança ForegroundServiceStartNotAllowedException (subclasse de
                // IllegalStateException) quando um FGS é iniciado DE BACKGROUND sem uma
                // exceção de background-start válida. O fix do 3.4.4 só cobria a
                // SecurityException (falta de permissão), lançada dentro do onStartCommand;
                // este caso é lançado já aqui, no startForegroundService. Sem o FGS o scan
                // em background segue via o PendingIntent scan, então não vale crashar.
                Log.w(TAG, "FGS start blocked (started from background) — skipping instead of crashing", e)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service — skipping", e)
            }
        }

        /**
         * Verdadeiro se o app pode subir o FGS connectedDevice. No Android 14+
         * (UPSIDE_DOWN_CAKE) o SO exige pelo menos uma das permissões Bluetooth;
         * abaixo disso não há essa exigência.
         */
        fun hasBluetoothForegroundServicePermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val bluetoothPermissions = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            return bluetoothPermissions.any {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Mesma proteção do start(): não crashar se o SO recusar (re)iniciar o FGS
                // de background (ForegroundServiceStartNotAllowed / SecurityException).
                Log.w(TAG, "Could not update foreground service notification — skipping", e)
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
    private var currentChannelName: String = "Region monitoring service"

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "BeaconScanService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawTitle = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val title = rawTitle.ifEmpty { resolveAppName() }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Scanning for nearby content"
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
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Region monitoring service"

        currentIcon = icon
        currentChannelId = channelId
        currentChannelName = channelName

        val notification = buildNotification(title, text, icon, channelId, channelName)

        // Defesa em profundidade: mesmo que o serviço tenha sido iniciado antes da
        // permissão ser revogada (ex.: retry do watchdog/boot), promover a foreground
        // com type connectedDevice sem permissão Bluetooth lança SecurityException no
        // Android 14+ e derruba o app. Nesse caso paramos o serviço em vez de crashar.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot start FGS connectedDevice without Bluetooth permission — stopping service instead of crashing", e)
            stopSelf()
            return START_NOT_STICKY
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

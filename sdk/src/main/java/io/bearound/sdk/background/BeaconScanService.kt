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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        /** Everything the rendered foreground notification carries; equality drives dedupe. */
        internal data class FgsNotificationContent(
            val title: String,
            val text: String,
            val icon: Int?,
            val channelId: String?,
            val channelName: String
        )

        private val notificationThrottle = NotificationUpdateThrottle<FgsNotificationContent>()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val trailingUpdate = Runnable { postTrailingNotification() }

        @Volatile
        private var appContext: Context? = null

        fun updateNotification(context: Context, title: String, text: String) {
            if (!isRunning) return
            // The service is ALREADY foreground: a notification refresh only needs
            // NotificationManager.notify. Refreshes are deduped and throttled (see
            // NotificationUpdateThrottle): the scan callback fires several times per
            // second, and re-posting at that rate makes Android shed the app's other
            // notifications, rich pushes included.
            try {
                appContext = context.applicationContext
                val cfg = io.bearound.sdk.utilities.SDKConfigStorage.loadForegroundScanConfig(context)
                val content = FgsNotificationContent(
                    title = title.ifEmpty { resolveAppName(context) },
                    text = text,
                    icon = cfg?.notificationIcon,
                    channelId = cfg?.notificationChannelId,
                    channelName = cfg?.notificationChannelName ?: "Region monitoring service"
                )
                when (val decision = notificationThrottle.offer(content)) {
                    NotificationUpdateThrottle.Decision.PostNow -> postNotification(context, content)
                    NotificationUpdateThrottle.Decision.Skip -> Unit
                    is NotificationUpdateThrottle.Decision.ScheduleTrailing ->
                        mainHandler.postDelayed(trailingUpdate, decision.delayMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update foreground service notification, skipping", e)
            }
        }

        private fun postTrailingNotification() {
            val context = appContext
            if (!isRunning || context == null) {
                notificationThrottle.reset()
                return
            }
            val content = notificationThrottle.onTrailingDue() ?: return
            try {
                postNotification(context, content)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update foreground service notification, skipping", e)
            }
        }

        private fun postNotification(context: Context, content: FgsNotificationContent) {
            val notification = buildNotification(
                context,
                content.title,
                content.text,
                content.icon,
                content.channelId,
                content.channelName
            )
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }

        private fun resolveAppName(context: Context): String {
            return try {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            } catch (_: Exception) {
                "Bearound"
            }
        }

        private fun buildNotification(
            context: Context,
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
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            val resolvedIcon = icon ?: android.R.drawable.stat_sys_data_bluetooth

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, resolvedChannelId)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(resolvedIcon)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(resolvedIcon)
                    .setOngoing(true)
                    .build()
            }
        }

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "BeaconScanService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY revive: the system recreates the service with intent == null,
        // which used to drop the host's custom title/icon/channel (in-memory only) and
        // fall back to defaults. Reload the persisted ForegroundScanConfig instead.
        val persistedConfig = if (intent == null) {
            io.bearound.sdk.utilities.SDKConfigStorage.loadForegroundScanConfig(this)
        } else {
            null
        }

        val rawTitle = intent?.getStringExtra(EXTRA_TITLE)
            ?: persistedConfig?.notificationTitle
            ?: ""
        val title = rawTitle.ifEmpty { resolveAppName(this) }
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: persistedConfig?.notificationText
            ?: "Scanning for nearby content"

        val icon = (intent?.getIntExtra(EXTRA_ICON, 0)?.takeIf { it != 0 })
            ?: persistedConfig?.notificationIcon
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
            ?: persistedConfig?.notificationChannelId
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME)
            ?: persistedConfig?.notificationChannelName
            ?: "Region monitoring service"

        val notification = buildNotification(this, title, text, icon, channelId, channelName)

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
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BeaconScanService.onStartCommand")
            stopSelf()
            return START_NOT_STICKY
        }

        notificationThrottle.recordPosted(
            FgsNotificationContent(title, text, icon, channelId, channelName)
        )
        Log.d(TAG, "BeaconScanService started in foreground")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(trailingUpdate)
        notificationThrottle.reset()
        Log.d(TAG, "BeaconScanService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}

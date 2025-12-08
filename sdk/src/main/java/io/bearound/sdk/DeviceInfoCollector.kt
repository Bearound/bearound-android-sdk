package io.bearound.sdk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.json.JSONObject
import java.util.TimeZone

/**
 * Collects device information for the BeAround SDK payload.
 */
class DeviceInfoCollector(private val context: Context) {

    /**
     * Collects all user device information and returns it as a JSONObject.
     */
    fun collectUserDeviceInfo(
        advertisingId: String?,
        adTrackingEnabled: Boolean,
        appStartTime: Long
    ): JSONObject {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        return JSONObject().apply {
            // Device manufacturer and model
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("os", "android")
            put("osVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)

            // Timestamp and timezone
            put("timestamp", System.currentTimeMillis())
            put("timezone", TimeZone.getDefault().id)

            // Battery information
            put("batteryLevel", getBatteryLevel(batteryManager))
            put("isCharging", isCharging(batteryManager))
            put("powerSaveMode", powerManager.isPowerSaveMode)
            put("lowPowerMode", null) // iOS only

            // Bluetooth state
            put("bluetoothState", getBluetoothState(bluetoothAdapter))

            // Location permissions
            put("locationPermission", getLocationPermission())
            put("locationAccuracy", getLocationAccuracy())

            // Notification permission
            put("notificationsPermission", getNotificationPermission())

            // Network information
            put("networkType", getNetworkType())
            put("wifiSSID", getWifiSSID())
            put("wifiBSSID", getWifiBSSID())
            put("cellularGeneration", getCellularGeneration())
            put("isRoaming", isRoaming())
            put("connectionMetered", isConnectionMetered())
            put("connectionExpensive", null) // iOS only

            // Memory information
            put("ramTotalMb", getRamTotalMb())
            put("ramAvailableMb", getRamAvailableMb())

            // Screen information
            put("screenWidth", getScreenWidth())
            put("screenHeight", getScreenHeight())

            // Advertising ID
            put("advertisingId", advertisingId)
            put("adTrackingEnabled", adTrackingEnabled)

            // App state
            put("appInForeground", isAppInForeground())
            put("appUptimeMs", System.currentTimeMillis() - appStartTime)
            put("coldStart", appStartTime == 0L) // Will be set properly in BeAround
        }
    }

    /**
     * Gets battery level as a float between 0.0 and 1.0.
     */
    private fun getBatteryLevel(batteryManager: BatteryManager): Float {
        return try {
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level >= 0) level / 100.0f else -1.0f
        } catch (_: Exception) {
            -1.0f
        }
    }

    /**
     * Checks if the device is charging.
     */
    private fun isCharging(batteryManager: BatteryManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gets Bluetooth state.
     */
    private fun getBluetoothState(bluetoothAdapter: BluetoothAdapter?): String {
        return when {
            bluetoothAdapter == null -> "unknown"
            bluetoothAdapter.isEnabled -> "on"
            else -> "off"
        }
    }

    /**
     * Gets location permission status.
     */
    private fun getLocationPermission(): String {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            PackageManager.PERMISSION_GRANTED
        }

        return when {
            fineLocation == PackageManager.PERMISSION_GRANTED &&
            backgroundLocation == PackageManager.PERMISSION_GRANTED -> "authorized_always"
            fineLocation == PackageManager.PERMISSION_GRANTED ||
            coarseLocation == PackageManager.PERMISSION_GRANTED -> "authorized_when_in_use"
            else -> "denied"
        }
    }

    /**
     * Gets location accuracy (always "full" for Android if permission granted).
     */
    private fun getLocationAccuracy(): String? {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        return if (fineLocation == PackageManager.PERMISSION_GRANTED) "full" else null
    }

    /**
     * Gets notification permission status.
     */
    private fun getNotificationPermission(): String {
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "authorized"
        } else {
            "denied"
        }
    }

    /**
     * Gets network type.
     */
    @SuppressLint("MissingPermission")
    private fun getNetworkType(): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return "unknown"
            }
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "none"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "none"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Gets WiFi SSID.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun getWifiSSID(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo?.ssid?.replace("\"", "")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets WiFi BSSID.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun getWifiBSSID(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo?.bssid
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets cellular generation.
     */
    @SuppressLint("MissingPermission")
    private fun getCellularGeneration(): String {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephonyManager.dataNetworkType
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.networkType
            }

            @Suppress("DEPRECATION")
            when (networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2g"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3g"
                TelephonyManager.NETWORK_TYPE_LTE -> "4g"
                TelephonyManager.NETWORK_TYPE_NR -> "5g"
                else -> "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Checks if the device is roaming.
     */
    private fun isRoaming(): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.isNetworkRoaming
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the connection is metered.
     */
    @SuppressLint("MissingPermission")
    private fun isConnectionMetered(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.isActiveNetworkMetered
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gets total RAM in MB.
     */
    private fun getRamTotalMb(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Gets available RAM in MB.
     */
    private fun getRamAvailableMb(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Gets screen width in pixels.
     */
    @Suppress("DEPRECATION")
    private fun getScreenWidth(): Int {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Gets screen height in pixels.
     */
    @Suppress("DEPRECATION")
    private fun getScreenHeight(): Int {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.heightPixels
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Checks if the app is in foreground.
     */
    private fun isAppInForeground(): Boolean {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return state.isAtLeast(Lifecycle.State.RESUMED)
    }
}


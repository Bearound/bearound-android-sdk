package io.bearound.sdk.utilities

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.UserDevice
import kotlinx.coroutines.runBlocking
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects comprehensive device information for API requests
 */
class DeviceInfoCollector(
    private val context: Context,
    private val isColdStart: Boolean = true
) {
    private val appStartTime = System.currentTimeMillis()

    fun collectDeviceInfo(
        locationPermission: String,
        bluetoothState: String,
        appInForeground: Boolean,
        location: Location? = null
    ): UserDevice {
        val deviceLocation = location?.let { createDeviceLocation(it) }

        return UserDevice(
            deviceId = DeviceIdentifier.getDeviceId(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            timestamp = System.currentTimeMillis(),
            timezone = TimeZone.getDefault().id,
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            bluetoothState = bluetoothState,
            locationPermission = locationPermission,
            notificationsPermission = getNotificationPermission(),
            networkType = getNetworkType(),
            cellularGeneration = getCellularGeneration(),
            ramTotalMb = getRamTotalMb(),
            ramAvailableMb = getRamAvailableMb(),
            screenWidth = getScreenWidth(),
            screenHeight = getScreenHeight(),
            adTrackingEnabled = DeviceIdentifier.isAdTrackingEnabled(context),
            appInForeground = appInForeground,
            appUptimeMs = System.currentTimeMillis() - appStartTime,
            coldStart = isColdStart,
            advertisingId = runBlocking { DeviceIdentifier.getAdvertisingId(context) },
            lowPowerMode = isLowPowerMode(),
            locationAccuracy = getLocationAccuracy(locationPermission),
            wifiSSID = getWifiSSID(),
            connectionMetered = isConnectionMetered(),
            connectionExpensive = isConnectionExpensive(),
            deviceLocation = deviceLocation,
            deviceName = getDeviceName(),
            carrierName = getCarrierName(),
            availableStorageMb = getAvailableStorageMb(),
            systemLanguage = Locale.getDefault().language,
            thermalState = getThermalState(),
            systemUptimeMs = SystemClock.elapsedRealtime(),
            sdkVersion = Build.VERSION.SDK_INT
        )
    }

    private fun createDeviceLocation(location: Location): DeviceLocation {
        return DeviceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            altitudeAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.toDouble()
            } else null,
            heading = if (location.hasBearing()) location.bearing.toDouble() else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond.toDouble()
            } else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees.toDouble()
            } else null,
            timestamp = Date(location.time),
            provider = location.provider
        )
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.isCharging
    }

    private fun isLowPowerMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }

    private fun getNotificationPermission(): String {
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "authorized"
        } else {
            "denied"
        }
    }

    private fun getLocationAccuracy(locationPermission: String): String? {
        if (!locationPermission.contains("authorized")) return null
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (hasFine) "full" else if (hasCoarse) "reduced" else null
        } else {
            "full"
        }
    }

    private fun getNetworkType(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return "none"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return "none"
            return when (networkInfo.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "unknown"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCellularGeneration(): String? {
        if (getNetworkType() != "cellular") return null
        
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        return when (telephonyManager.networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (telephonyManager.networkType == TelephonyManager.NETWORK_TYPE_NR) {
                        "5G"
                    } else null
                } else null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getWifiSSID(): String? {
        // Note: Requires ACCESS_FINE_LOCATION permission on Android 10+
        // Returns null if permission not granted
        return null // Simplified for now
    }

    private fun isConnectionMetered(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.isActiveNetworkMetered
    }

    private fun isConnectionExpensive(): Boolean? {
        return when (getNetworkType()) {
            "cellular" -> true
            "wifi" -> false
            else -> null
        }
    }

    private fun getRamTotalMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / 1024 / 1024).toInt()
    }

    private fun getRamAvailableMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / 1024 / 1024).toInt()
    }

    private fun getScreenWidth(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            return displayMetrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            return displayMetrics.heightPixels
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceName(): String {
        val fallback = "${Build.MANUFACTURER} ${Build.MODEL}"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: fallback
        } else {
            fallback
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCarrierName(): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.networkOperatorName?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun getAvailableStorageMb(): Long? {
        return try {
            val dataDir = context.filesDir
            val availableBytes = dataDir.usableSpace
            availableBytes / 1024 / 1024
        } catch (e: Exception) {
            null
        }
    }

    private fun getThermalState(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        } else {
            "not_available"
        }
    }
}


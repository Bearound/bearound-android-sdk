package io.bearound.sdk.utilities

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
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
import io.bearound.sdk.models.DataCollectionPolicyStore
import io.bearound.sdk.models.UserDevice
import java.util.Locale
import java.util.TimeZone

/**
 * Collects comprehensive device information for API requests
 */
class DeviceInfoCollector(
    private val context: Context
) {
    private val wifiCollector by lazy { WifiCollector(context) }

    /**
     * Nudges the system Wi-Fi scan cache — see [WifiCollector.nudgeScan]. No-op when the host
     * turned Wi-Fi collection off: refreshing a cache nothing will read is pure cost.
     */
    internal fun nudgeWifiScan() {
        if (!DataCollectionPolicyStore.current.wifi) return
        wifiCollector.nudgeScan()
    }
    private val locationCollector by lazy { LocationCollector(context) }

    /**
     * Is there anything worth reporting when the scan found no beacon and no peer?
     *
     * Cheap on purpose: the empty-scan decision runs on every sync tick, and building a whole
     * [UserDevice] just to find out there is nothing to say would be the expensive way to
     * answer it. Reads the same two sources the payload would carry.
     */
    internal fun hasPresenceSignal(): Boolean {
        // Mirrors the policy the payload builder applies: a signal the host turned off is not
        // a reason to spend a request. With both off the heartbeat has nothing to carry and
        // correctly stops firing.
        val policy = DataCollectionPolicyStore.current
        if (policy.location && locationCollector.lastKnown() != null) return true
        return policy.wifi && wifiCollector.collect().isNotEmpty()
    }

    companion object {
        /**
         * True only for the FIRST payload each process builds. The old constructor flag
         * was hardcoded `true` for the singleton's whole life, so every sync reported
         * coldStart=true and the field carried no signal.
         */
        private val firstPayloadOfProcess = java.util.concurrent.atomic.AtomicBoolean(true)
    }

    private val appStartTime = System.currentTimeMillis()

    fun collectDeviceInfo(
        locationPermission: String,
        bluetoothState: String,
        appInForeground: Boolean
    ): UserDevice {
        // What the host allows us to collect. Read once per payload so a reconfigure lands on
        // the next one instead of mid-build.
        val policy = DataCollectionPolicyStore.current

        // Cada payload é a nossa chance de recuperar um ID que faltou — mas não quando o
        // host desligou a coleta: aí não há ID para recuperar.
        if (policy.advertisingId) AdvertisingIdCollector.ensureFresh(context)

        // Collected once and reused: the connected AP is just the entry flagged as
        // such, so there is no reason to hit the Wi-Fi stack twice. Skipped entirely when
        // Wi-Fi collection is off — nothing to withhold later if the value is never read.
        val wifis = if (policy.wifi) wifiCollector.collect() else emptyList()

        return UserDevice(
            deviceId = DeviceIdentifier.getDeviceId(context),
            pushToken = PushTokenStore.tokenForPayload(),
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
            appInForeground = appInForeground,
            appUptimeMs = System.currentTimeMillis() - appStartTime,
            coldStart = firstPayloadOfProcess.getAndSet(false),
            lowPowerMode = isLowPowerMode(),
            locationAccuracy = getLocationAccuracy(locationPermission),
            backgroundLocation = hasBackgroundLocation(),
            apId = wifis.firstOrNull { it.connected }?.apId,
            // Temporary companion to apId while the collection is being validated.
            wifiSSID = wifis.firstOrNull { it.connected }?.ssid,
            connectionMetered = isConnectionMetered(),
            connectionExpensive = isConnectionExpensive(),
            deviceName = getDeviceName(),
            carrierName = getCarrierName(),
            availableStorageMb = getAvailableStorageMb(),
            systemLanguage = Locale.getDefault().language,
            thermalState = getThermalState(),
            systemUptimeMs = SystemClock.elapsedRealtime(),
            sdkVersion = Build.VERSION.SDK_INT,
            wifis = wifis,
            location = if (policy.location) locationCollector.lastKnown() else null,
            // ensureFresh (acima) só dispara leitura quando falta ou venceu o TTL, então é
            // barato chamar por payload.
            advertisingId = if (policy.advertisingId) AdvertisingIdCollector.current() else null,
            limitAdTracking =
                if (policy.advertisingId) AdvertisingIdCollector.isLimitAdTrackingEnabled() else null
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

    /**
     * Whether the host holds `ACCESS_BACKGROUND_LOCATION`.
     *
     * The SDK deliberately does **not** declare this permission. It is a dangerous
     * permission with a Play Store policy review attached, so declaring it here would drag
     * every host app through that review — including the ones that never collect Wi-Fi.
     * Declaring it is the host's decision; making its absence visible is ours.
     *
     * Measured in production on 2026-08-05: the same device, same session, every permission
     * it asked for granted, went from 25 access points to zero the instant it was
     * backgrounded — and nothing anywhere said why.
     */
    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

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

        // Host apps may or may not hold READ_PHONE_STATE (the SDK does not declare it).
        // Without it, TelephonyManager.getNetworkType() throws SecurityException on
        // Android 11+/targetSdk 30+ — the SDK must never crash the host.
        val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneStatePermission) {
            return null
        }

        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            when (telephonyManager.networkType) {
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
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getWifiSSID(): String? {
        // WiFi SSID requires ACCESS_FINE_LOCATION on Android 10+
        // Check if we have location permission
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasLocationPermission) {
            return null
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31): Use ConnectivityManager with NetworkCapabilities
                getWifiSSIDFromConnectivityManager()
            } else {
                // Android 10-11 (API 29-30): Use WifiManager
                getWifiSSIDFromWifiManager()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun getWifiSSIDFromWifiManager(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            
            if (wifiInfo != null && wifiInfo.networkId != -1) {
                // SSID comes with quotes, remove them
                val ssid = wifiInfo.ssid
                if (ssid != null && ssid != "<unknown ssid>") {
                    ssid.removeSurrounding("\"")
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun getWifiSSIDFromConnectivityManager(): String? {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On Android 10+, try to get WifiInfo from TransportInfo
                val transportInfo = capabilities.transportInfo
                if (transportInfo is android.net.wifi.WifiInfo) {
                    val ssid = transportInfo.ssid
                    if (ssid != null && ssid != "<unknown ssid>") {
                        return ssid.removeSurrounding("\"")
                    }
                }
            }
            
            // Fallback to WifiManager
            getWifiSSIDFromWifiManager()
        } catch (e: Exception) {
            null
        }
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

    @SuppressLint("UsableSpace")
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

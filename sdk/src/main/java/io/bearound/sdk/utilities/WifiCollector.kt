package io.bearound.sdk.utilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.WifiObservation

/**
 * Collects the Wi-Fi access points visible to the device.
 *
 * Two deliberate restraints:
 *
 * - **Never triggers a scan.** We read the system's cached results (`scanResults`)
 *   instead of calling `startScan()`. Android throttles scans to 4 per 2 minutes
 *   anyway, and every other app on the phone is already refreshing that cache for
 *   us — asking for our own costs battery and buys nothing. It also keeps
 *   `CHANGE_WIFI_STATE` out of the manifest.
 *
 * - **Opt-in by permission.** Nothing is collected unless the host app already holds
 *   the permissions. A host that never asks for location simply sends no `wifis[]`,
 *   and the rest of the SDK behaves exactly as before.
 */
internal class WifiCollector(private val context: Context) {

    companion object {
        private const val TAG = "WifiCollector"

        /**
         * Industry opt-out convention, honoured by Google and Mozilla: an SSID ending
         * in `_nomap` means the owner does not want the access point mapped. We drop
         * it on the device — it never reaches the network.
         */
        private const val NOMAP_SUFFIX = "_nomap"

        /** Ceiling on how many neighbours travel in one payload. */
        private const val MAX_OBSERVATIONS = 25

        /** Results older than this are stale enough that the device likely moved. */
        private const val MAX_RESULT_AGE_MS = 5 * 60 * 1000L
    }

    /**
     * @return the visible access points, strongest first, or an empty list when the
     *         host app lacks the permissions or Wi-Fi is off. Never throws.
     */
    fun collect(): List<WifiObservation> {
        if (!hasWifiStatePermission()) return emptyList()

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()

        val now = System.currentTimeMillis()
        val observations = LinkedHashMap<String, WifiObservation>()

        // The connected access point comes first: it is the strongest signal of where
        // the device actually is, and it is the only one iOS can offer.
        connectedObservation(wifiManager, now)?.let { observations[it.apId] = it }

        // Neighbours need the scan permission on top of the Wi-Fi one.
        if (hasScanPermission()) {
            neighbourObservations(wifiManager, now).forEach { observation ->
                observations.putIfAbsentCompat(observation.apId, observation)
            }
        }

        return observations.values
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            .take(MAX_OBSERVATIONS)
    }

    // ── permissions ──────────────────────────────────────────────────────────────

    /** Install-time permission — granted without a prompt, but a host can strip it. */
    private fun hasWifiStatePermission(): Boolean = granted(Manifest.permission.ACCESS_WIFI_STATE)

    /**
     * Reading neighbours requires location on every version, plus `NEARBY_WIFI_DEVICES`
     * as an alternative on Android 13+. Either one is enough.
     */
    private fun hasScanPermission(): Boolean {
        val hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (hasLocation) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            granted("android.permission.NEARBY_WIFI_DEVICES")
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── collection ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun connectedObservation(wifiManager: WifiManager, now: Long): WifiObservation? = try {
        val info = wifiManager.connectionInfo
        val apId = ApIdentifier.from(info?.bssid)
        when {
            apId == null -> null
            info!!.networkId == -1 && info.bssid == null -> null
            else -> WifiObservation(
                apId = apId,
                // Temporary, for validating the collection — see WifiObservation.ssid.
                ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" },
                rssi = info.rssi.takeIf { it in -100..0 },
                connected = true,
                frequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    info.frequency.takeIf { it > 0 }
                } else null,
                timestamp = now
            )
        }
    } catch (e: SecurityException) {
        Log.d(TAG, "connected AP unavailable: ${e.message}")
        null
    } catch (e: Exception) {
        Log.d(TAG, "connected AP failed: ${e.message}")
        null
    }

    private fun neighbourObservations(wifiManager: WifiManager, now: Long): List<WifiObservation> = try {
        wifiManager.scanResults.orEmpty()
            .asSequence()
            .filterNot { it.SSID?.endsWith(NOMAP_SUFFIX, ignoreCase = true) == true }
            .mapNotNull { result ->
                val apId = ApIdentifier.from(result.BSSID) ?: return@mapNotNull null
                val ageMs = resultAgeMs(result)
                if (ageMs != null && ageMs > MAX_RESULT_AGE_MS) return@mapNotNull null
                WifiObservation(
                    apId = apId,
                    // Temporary, for validating the collection — see WifiObservation.ssid.
                    ssid = result.SSID?.takeIf { it.isNotBlank() },
                    rssi = result.level.takeIf { it in -100..0 },
                    connected = false,
                    frequencyMhz = result.frequency.takeIf { it > 0 },
                    // The observation happened when the radio saw it, not when we read it.
                    timestamp = if (ageMs != null) now - ageMs else now
                )
            }
            .toList()
    } catch (e: SecurityException) {
        Log.d(TAG, "scan results unavailable: ${e.message}")
        emptyList()
    } catch (e: Exception) {
        Log.d(TAG, "scan results failed: ${e.message}")
        emptyList()
    }

    /** `timestamp` on a scan result is microseconds since boot, not epoch. */
    private fun resultAgeMs(result: android.net.wifi.ScanResult): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && result.timestamp > 0) {
            (SystemClock.elapsedRealtime() - result.timestamp / 1000).coerceAtLeast(0)
        } else null

    private fun <K, V> LinkedHashMap<K, V>.putIfAbsentCompat(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }
}

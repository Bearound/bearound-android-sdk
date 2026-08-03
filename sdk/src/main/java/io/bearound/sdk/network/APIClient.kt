package io.bearound.sdk.network

import android.util.Log
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.UserDevice
import io.bearound.sdk.models.UserProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thrown when the backend responds with a non-2xx status. Carries the HTTP [statusCode] and
 * the (best-effort) response [body] so the SDK can surface actionable failures — e.g. a 401
 * with the token-rejection reason — through [io.bearound.sdk.interfaces.BeAroundSDKListener.onError]
 * instead of discarding them in a log line.
 */
class HttpException(
    val statusCode: Int,
    val body: String
) : Exception(
    if (body.isBlank()) "HTTP error: $statusCode" else "HTTP error: $statusCode — $body"
)

/**
 * API client for sending beacon data to the backend
 */
class APIClient(private val configuration: SDKConfiguration) {
    companion object {
        private const val TAG = "BeAroundSDK-APIClient"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    /**
     * Sends a register event to /ingest with an empty beacon list and syncTrigger="register".
     * Used on startScanning() to ensure the device appears in the Control Hub even when no
     * beacons are detected. Payload is identical to sendBeacons except beacons=[] and the
     * extra syncTrigger field.
     */
    suspend fun sendRegister(
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?,
        onComplete: (Result<Unit>) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${configuration.apiBaseURL}/ingest")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", configuration.businessToken)
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.doOutput = true

                // beacons=[] + syncTrigger="register" (iOS parity)
                val payload = buildPayload(emptyList(), sdkInfo, userDevice, userProperties)
                payload.put("syncTrigger", "register")

                Log.d(TAG, "Sending register event to $url")

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode

                if (responseCode in 200..299) {
                    Log.d(TAG, "Register succeeded (HTTP $responseCode)")
                    onComplete(Result.success(Unit))
                } else {
                    val errorBody = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    Log.e(TAG, "Register failed: HTTP $responseCode - $errorBody")
                    onComplete(Result.failure(HttpException(responseCode, errorBody)))
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Register request failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    suspend fun sendBeacons(
        beacons: List<Beacon>,
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (beacons.isEmpty() && userDevice.encounters.isEmpty()) {
            onComplete(Result.success(Unit))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val url = URL("${configuration.apiBaseURL}/ingest")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", configuration.businessToken)
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.doOutput = true

                // Build JSON payload
                val payload = buildPayload(beacons, sdkInfo, userDevice, userProperties)

                // Send request
                Log.d(TAG, "Sending ${beacons.size} beacon(s) to $url")

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                // Read response
                val responseCode = connection.responseCode
                
                if (responseCode in 200..299) {
                    val responseBody = try {
                        BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    Log.d(TAG, "Successfully sent ${beacons.size} beacon(s)")
                    onComplete(Result.success(Unit))
                } else {
                    val errorBody = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    Log.e(TAG, "API error: HTTP $responseCode - $errorBody")
                    onComplete(Result.failure(HttpException(responseCode, errorBody)))
                }

                connection.disconnect()
                
            } catch (e: Exception) {
                Log.e(TAG, "Request failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    private fun buildPayload(
        beacons: List<Beacon>,
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?
    ): JSONObject {
        val payload = JSONObject()

        // Beacons array
        val beaconsArray = JSONArray()
        beacons.forEach { beacon ->
            val beaconObj = JSONObject().apply {
                put("uuid", beacon.uuid.toString())
                put("major", beacon.major)
                put("minor", beacon.minor)
                put("rssi", beacon.rssi)
                put("accuracy", beacon.accuracy)
                put("proximity", beacon.proximity.toApiString())
                put("timestamp", beacon.timestamp.time)

                beacon.txPower?.let { put("txPower", it) }
                beacon.rssiRaw?.let { put("rssiRaw", it) }

                beacon.rssiSamples?.let { stats ->
                    val statsObj = JSONObject().apply {
                        put("count", stats.count)
                        put("min", stats.min)
                        put("max", stats.max)
                        put("avg", stats.avg)
                        put("stdDev", stats.stdDev)
                        put("firstSeen", stats.firstSeen)
                        put("lastSeen", stats.lastSeen)
                    }
                    put("rssiSamples", statsObj)
                }

                beacon.metadata?.let { metadata ->
                    val metadataObj = JSONObject().apply {
                        put("battery", metadata.batteryLevel)
                        put("firmware", metadata.firmwareVersion)
                        put("movements", metadata.movements)
                        put("temperature", metadata.temperature)
                        metadata.txPower?.let { put("txPower", it) }
                        metadata.rssiFromBLE?.let { put("rssiFromBLE", it) }
                        metadata.isConnectable?.let { put("isConnectable", it) }
                    }
                    put("metadata", metadataObj)
                }
            }
            beaconsArray.put(beaconObj)
        }
        payload.put("beacons", beaconsArray)

        // SDK info
        val sdkObj = JSONObject().apply {
            put("version", sdkInfo.version)
            put("platform", sdkInfo.platform)
            put("appId", sdkInfo.appId)
            put("build", sdkInfo.build)
            put("technology", sdkInfo.technology)
            // Which Bearound SDK produced this event — hardcoded, never overridable
            // by integrators (unlike `technology`, which wrappers rebrand).
            put("type", "tracking")
        }
        payload.put("sdk", sdkObj)

        // Device info
        payload.put("device", buildDevicePayload(userDevice))

        // Wi-Fi observations and location ride at the TOP of the envelope, beside
        // `beacons`, because they describe the sighting — not the device. Both are
        // omitted entirely when unavailable, so a host without the permissions sends
        // exactly the payload it sends today.
        if (userDevice.wifis.isNotEmpty()) {
            val wifisArray = JSONArray()
            userDevice.wifis.forEach { wifi ->
                wifisArray.put(JSONObject().apply {
                    put("apId", wifi.apId)
                    put("connected", wifi.connected)
                    put("timestamp", wifi.timestamp)
                    wifi.rssi?.let { put("rssi", it) }
                    wifi.frequencyMhz?.let { put("frequencyMhz", it) }
                    // Temporary, for validating the collection — see WifiObservation.ssid.
                    wifi.ssid?.let { put("ssid", it) }
                })
            }
            payload.put("wifis", wifisArray)
        }

        // Encounters (same additive contract as `wifis`): omitted entirely when empty.
        if (userDevice.encounters.isNotEmpty()) {
            val encountersArray = JSONArray()
            userDevice.encounters.forEach { e ->
                encountersArray.put(JSONObject().apply {
                    put("rpi", e.rpi)
                    put("rssi", e.rssi)
                    put("rssiSamples", JSONObject().apply {
                        put("count", e.sampleCount)
                        put("min", e.rssiMin)
                        put("max", e.rssiMax)
                        put("avg", e.rssiAvg)
                    })
                    put("firstSeen", e.firstSeen)
                    put("lastSeen", e.lastSeen)
                })
            }
            payload.put("encounters", encountersArray)
        }
        if (userDevice.encounterIds.isNotEmpty()) {
            payload.put("encounterIds", JSONArray(userDevice.encounterIds))
        }

        userDevice.location?.let { location ->
            payload.put("location", JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                // Timestamp OF THE FIX, not of the payload — a fix can be minutes old
                // and the backend has to be able to tell.
                put("timestamp", location.timestamp)
                put("source", location.source)
                location.accuracy?.let { put("accuracy", it) }
                location.altitude?.let { put("altitude", it) }
                location.isMocked?.let { put("isMocked", it) }
            })
        }

        // User properties (if any)
        if (userProperties?.hasProperties == true) {
            val userPropsObj = JSONObject(userProperties.toDictionary())
            payload.put("userProperties", userPropsObj)
        }

        return payload
    }

    private fun buildDevicePayload(device: UserDevice): JSONObject {
        val payload = JSONObject()

        payload.put("deviceId", device.deviceId)
        device.pushToken?.let { payload.put("pushToken", it) }
        payload.put("timestamp", device.timestamp)
        payload.put("timezone", device.timezone)

        // Hardware
        val hardware = JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("os", device.os)
            put("osVersion", device.osVersion)
        }
        payload.put("hardware", hardware)

        // Screen
        val screen = JSONObject().apply {
            put("width", device.screenWidth)
            put("height", device.screenHeight)
        }
        payload.put("screen", screen)

        // Battery
        val battery = JSONObject().apply {
            put("level", device.batteryLevel)
            put("isCharging", device.isCharging)
            device.lowPowerMode?.let { put("lowPowerMode", it) }
        }
        payload.put("battery", battery)

        val network = JSONObject().apply {
            put("type", device.networkType)
            device.cellularGeneration?.let { put("cellularGeneration", it) }
            device.apId?.let { put("apId", it) }
            // Temporary, for validating the collection — see WifiObservation.ssid.
            device.wifiSSID?.let { put("wifiSSID", it) }
        }
        payload.put("network", network)

        // Permissions
        val permissions = JSONObject().apply {
            put("location", device.locationPermission)
            put("notifications", device.notificationsPermission)
            put("bluetooth", device.bluetoothState)
            device.locationAccuracy?.let { put("locationAccuracy", it) }
            // Advertising ID lives here because that is where the ingest already reads it
            // from (`device.permissions.advertisingId`) — it is provided by the SDK so the
            // backend can skip the matchmaker round-trip.
            device.advertisingId?.let { put("advertisingId", it) }
            device.limitAdTracking?.let { put("limitAdTracking", it) }
        }
        payload.put("permissions", permissions)

        // Memory
        val memory = JSONObject().apply {
            put("totalMb", device.ramTotalMb)
            put("availableMb", device.ramAvailableMb)
        }
        payload.put("memory", memory)

        // App state
        val appState = JSONObject().apply {
            put("inForeground", device.appInForeground)
            put("uptimeMs", device.appUptimeMs)
            put("coldStart", device.coldStart)
        }
        payload.put("appState", appState)

        payload.put("deviceName", device.deviceName)
        payload.put("systemLanguage", device.systemLanguage)
        payload.put("thermalState", device.thermalState)
        payload.put("systemUptimeMs", device.systemUptimeMs)

        device.carrierName?.let { payload.put("carrierName", it) }
        device.availableStorageMb?.let { payload.put("availableStorageMb", it) }

        return payload
    }
}

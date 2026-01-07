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
 * API client for sending beacon data to the backend
 */
class APIClient(private val configuration: SDKConfiguration) {
    companion object {
        private const val TAG = "BeAroundSDK-APIClient"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    suspend fun sendBeacons(
        beacons: List<Beacon>,
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (beacons.isEmpty()) {
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
                Log.d(TAG, "========================================")
                Log.d(TAG, "INGEST API CALL")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Sending ${beacons.size} beacon(s)")
                Log.d(TAG, "Payload: $payload")
                Log.d(TAG, "========================================")

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
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "INGEST API RESPONSE - SUCCESS")
                    Log.d(TAG, "HTTP Status: $responseCode")
                    Log.d(TAG, "Response Body: $responseBody")
                    Log.d(TAG, "Successfully sent ${beacons.size} beacon(s)")
                    Log.d(TAG, "========================================")
                    onComplete(Result.success(Unit))
                } else {
                    val errorMessage = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (e: Exception) {
                        "HTTP error $responseCode"
                    }
                    Log.e(TAG, "========================================")
                    Log.e(TAG, "INGEST API RESPONSE - ERROR")
                    Log.e(TAG, "HTTP Status: $responseCode")
                    Log.e(TAG, "Error Message: $errorMessage")
                    Log.e(TAG, "========================================")
                    onComplete(Result.failure(Exception("HTTP error: $responseCode")))
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
        }
        payload.put("sdk", sdkObj)

        // Device info
        payload.put("device", buildDevicePayload(userDevice))

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

        // Network
        val network = JSONObject().apply {
            put("type", device.networkType)
            device.cellularGeneration?.let { put("cellularGeneration", it) }
            device.wifiSSID?.let { put("wifiSSID", it) }
        }
        payload.put("network", network)

        // Permissions
        val permissions = JSONObject().apply {
            put("location", device.locationPermission)
            put("notifications", device.notificationsPermission)
            put("bluetooth", device.bluetoothState)
            device.locationAccuracy?.let { put("locationAccuracy", it) }
            device.advertisingId?.let { put("advertisingId", it) }
            put("adTrackingEnabled", device.adTrackingEnabled)
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

        // Device location
        device.deviceLocation?.let { location ->
            val locationObj = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("timestamp", location.timestamp.time)
                location.accuracy?.let { put("accuracy", it) }
                location.altitude?.let { put("altitude", it) }
                location.altitudeAccuracy?.let { put("altitudeAccuracy", it) }
                location.heading?.let { put("heading", it) }
                location.speed?.let { put("speed", it) }
                location.speedAccuracy?.let { put("speedAccuracy", it) }
                location.bearing?.let { put("bearing", it) }
                location.bearingAccuracy?.let { put("bearingAccuracy", it) }
                location.provider?.let { put("provider", it) }
            }
            payload.put("deviceLocation", locationObj)
        }

        return payload
    }
}

sealed class APIError : Exception() {
    object InvalidURL : APIError() {
        override val message: String = "Invalid API URL"
    }

    object InvalidResponse : APIError() {
        override val message: String = "Invalid server response"
    }

    data class HTTPError(val statusCode: Int) : APIError() {
        override val message: String = "HTTP error: $statusCode"
    }
}


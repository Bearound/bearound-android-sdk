package io.bearound.sdk.network

import android.util.Log
import android.os.SystemClock
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DataCollectionPolicyStore
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.UserDevice
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.sampling.SamplingCapability
import io.bearound.sdk.sampling.isInertialMotionTuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

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
internal data class NegotiatedSamplingCapability(
    val policy: SamplingCapability,
    val liveEvidenceMaxAgeMs: Long,
    val maxPayloadBytes: Int,
    val expiresAtElapsedMs: Long,
    val serverOffsetMs: Double,
    val offsetUncertaintyMs: Double,
    val synchronizedAtElapsedMs: Long,
    val deviceId: String,
    val synchronizedAtWallMs: Long,
    val requestGzipEnabled: Boolean = false
)

internal data class SamplingAcknowledgment(val batchId: String, val projection: String, val invalidatedCapability: Boolean)

internal data class EncodedRequestBody(val data: ByteArray, val contentType: String,
    val contentEncoding: String?, val originalJsonBytes: Int)

internal fun encodeRequestBody(json: ByteArray, gzipAllowed: Boolean,
    compressor: (ByteArray) -> ByteArray = { original ->
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(original) } }.toByteArray()
    }): EncodedRequestBody {
    if (gzipAllowed) {
        val compressed = runCatching { compressor(json) }.getOrNull()
        if (compressed != null && compressed.size < json.size)
            return EncodedRequestBody(compressed, "application/octet-stream", "gzip", json.size)
    }
    return EncodedRequestBody(json, "application/json", null, json.size)
}

class APIClient(
    private val configuration: SDKConfiguration,
    private val endpoint: String = configuration.apiBaseURL,
    private val policyProvider: () -> DataCollectionPolicy = { DataCollectionPolicyStore.current },
    private val nativeActivityAllowed: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "BeAroundSDK-APIClient"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    @Volatile private var negotiated: NegotiatedSamplingCapability? = null
    @Volatile private var generationGuard: () -> Boolean = { true }
    private val gzipSuppression = mutableMapOf<String, Long>()
    private data class IngestResponse(val payload: JSONObject, val body: String, val status: Int)

    internal fun bindGeneration(guard: () -> Boolean) { generationGuard = guard }

    internal fun withEndpoint(value: String): APIClient =
        APIClient(configuration, value, policyProvider, nativeActivityAllowed, elapsedRealtime, clock)

    internal fun invalidateSamplingCapability() { negotiated = null }

    internal fun samplingCapability(deviceId: String): NegotiatedSamplingCapability? {
        val current = negotiated ?: return null
        val now = elapsedRealtime()
        if (current.deviceId != deviceId || now < current.synchronizedAtElapsedMs || now >= current.expiresAtElapsedMs ||
            kotlin.math.abs(clock().toDouble() - (current.synchronizedAtWallMs.toDouble() + now - current.synchronizedAtElapsedMs)) > 120_000.0) {
            negotiated = null
            return null
        }
        return current
    }

    internal fun observeResponse(body: String, deviceId: String, requestWall: Long, requestElapsed: Long) {
        val response = runCatching { JSONObject(body) }.getOrNull() ?: return
        val ack = response.optJSONObject("samplingAck")
        if (ack?.optBoolean("invalidateCapability") == true || ack?.optString("fallback") == "legacy_now") {
            invalidateSamplingCapability()
            return
        }
        val value = response.optJSONObject("capabilities")?.optJSONObject("adaptiveSampling") ?: return
        fun positive(key: String): Long? {
            val number = value.opt(key) as? Number ?: return null
            val double = number.toDouble()
            return number.toLong().takeIf { double.isFinite() && double > 0 && double == it.toDouble() }
        }
        val now = elapsedRealtime()
        val roundTrip = now - requestElapsed
        val lease = positive("leaseMs")
        val serverTime = positive("serverTimeMs")
        val services = positive("servicesCadenceMs")
        val mapTtl = positive("mapTtlMs")
        val session = positive("sessionTimeoutMs")
        val maxAge = positive("liveEvidenceMaxAgeMs")
        val skew = positive("clockSkewToleranceMs")
        val maxBytes = positive("maxPayloadBytes")
        val modes = value.optJSONArray("modes")
        val validModes = modes != null && (0 until modes.length()).map { modes.optString(it) }.toSet()
            .containsAll(listOf("live", "summary", "transition"))
        if (positive("version") != 1L || !validModes || deviceId.isBlank() ||
            lease == null || lease > 300_000L || serverTime == null || services == null || mapTtl == null ||
            session == null || session / 3 < configuration.syncInterval || maxAge == null || maxAge > 30_000L ||
            skew == null || skew > 120_000L || maxBytes == null || maxBytes > 524_288L ||
            roundTrip !in 0..9_998L || requestWall <= 0 || now > Long.MAX_VALUE - lease ||
            kotlin.math.abs(clock().toDouble() - (requestWall.toDouble() + roundTrip)) > 120_000.0 ||
            kotlin.math.abs(serverTime - (requestWall + roundTrip / 2.0)) > 86_400_000.0
        ) {
            invalidateSamplingCapability()
            return
        }
        negotiated = NegotiatedSamplingCapability(SamplingCapability(session, services, mapTtl), maxAge,
            maxBytes.toInt(), now + lease, serverTime - (requestWall + roundTrip / 2.0),
            roundTrip / 2.0 + 1.0, now, deviceId, clock(),
            value.optJSONArray("requestContentEncodings")?.let { it.length() == 1 && it.opt(0) == "gzip" } == true)
    }

    internal fun filterPayload(original: JSONObject): JSONObject {
        val payload = JSONObject(original.toString())
        val policy = policyProvider()
        if (!policy.location) payload.remove("location")
        if (!policy.wifi) {
            payload.remove("wifis")
            payload.optJSONObject("device")?.optJSONObject("network")?.apply {
                remove("apId"); remove("wifiSSID")
            }
        }
        if (!policy.advertisingId) payload.optJSONObject("device")?.optJSONObject("permissions")?.apply {
            remove("advertisingId"); remove("limitAdTracking")
        }
        val motion = payload.optJSONObject("sampling")?.optJSONObject("motion")
        val source = motion?.optString("source")
        val inertial = isInertialMotionTuple(motion?.optString("state"), source,
            motion?.optString("confidence"), motion?.optString("reason"))
        if ((source == "fresh_location" && !policy.location) ||
            (inertial && !policy.motionSensorsEnabled) ||
            (source == "context_fusion" && !inertial && (!policy.location || !policy.wifi)) ||
            (source == "native_activity" && (!policy.motionSensorsEnabled || !nativeActivityAllowed()))
        ) motion?.apply {
            put("state", "unknown"); put("source", "none"); put("confidence", "none")
            put("reason", "source_disabled"); put("observedAtMs", JSONObject.NULL); put("ageMs", JSONObject.NULL)
            put("contextStable", false)
        }
        return payload
    }

    /** Sends frozen original context; the caller removes only a matching durable ACK. */
    internal suspend fun sendStoredPayload(payloadJson: String, onSerializedAttempt: (Int) -> Unit = {}): Result<SamplingAcknowledgment?> = withContext(Dispatchers.IO) {
        try {
            val response = postPayload(JSONObject(payloadJson), true, onSerializedAttempt)
            val sampling = response.payload.optJSONObject("sampling")
            if (sampling == null) return@withContext Result.success(null)
            val ack = JSONObject(response.body).optJSONObject("samplingAck")
                ?: return@withContext Result.failure(IllegalStateException("Missing sampling acknowledgment"))
            val expected = sampling.getString("batchId")
            if (ack.optString("batchId") != expected || ack.optString("archive") != "durable") {
                return@withContext Result.failure(IllegalStateException("Sampling acknowledgment does not confirm this batch"))
            }
            Result.success(SamplingAcknowledgment(expected, ack.optString("projection"), ack.optBoolean("invalidateCapability")))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun postPayload(original: JSONObject, enforceCapturedBudget: Boolean = false,
                            onSerializedAttempt: (Int) -> Unit = {}): IngestResponse {
        var authorized = original
        var forceIdentity = false
        var originalBudget: Int? = null
        repeat(2) {
            checkGeneration()
            authorized = filterPayload(authorized)
            val deviceId = authorized.getJSONObject("device").getString("deviceId")
            val capability = samplingCapability(deviceId)
            if (enforceCapturedBudget) {
                check(authorized.getJSONObject("sdk").getString("appId") == configuration.appId) {
                    "Captured application binding does not match configuration"
                }
                check(forceIdentity || authorized.optJSONObject("sampling") == null || capability != null) {
                    "Sampling capability unavailable for historical delivery"
                }
            }
            val json = authorized.toString().toByteArray(Charsets.UTF_8)
            val limit = capability?.maxPayloadBytes ?: originalBudget ?: 524_288
            if (originalBudget == null) originalBudget = limit
            if (enforceCapturedBudget && json.size > limit) {
                invalidateSamplingCapability()
                error("Captured payload exceeds the negotiated budget")
            }
            val suppressed = synchronized(gzipSuppression) {
                val now = elapsedRealtime()
                gzipSuppression.entries.removeAll { now >= it.value }
                now < (gzipSuppression[deviceId] ?: Long.MIN_VALUE)
            }
            val encoded = encodeRequestBody(json, !forceIdentity && !suppressed && capability?.requestGzipEnabled == true &&
                json.size <= limit && json.size <= 524_288)
            checkGeneration()
            val requestWall = clock()
            val requestElapsed = elapsedRealtime()
            val connection = URL("$endpoint/ingest").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", encoded.contentType)
                encoded.contentEncoding?.let { connection.setRequestProperty("Content-Encoding", it) }
                connection.setRequestProperty("Authorization", configuration.businessToken)
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.doOutput = true
                val output = connection.outputStream
                checkGeneration()
                val currentPayload = filterPayload(authorized)
                if (currentPayload.toString() != authorized.toString()) {
                    authorized = currentPayload
                    forceIdentity = true
                    return@repeat
                }
                if (encoded.contentEncoding == "gzip" && samplingCapability(deviceId) !== capability) {
                    forceIdentity = true
                    return@repeat
                }
                output.use {
                    onSerializedAttempt(encoded.data.size)
                    it.write(encoded.data)
                }
                val status = connection.responseCode
                val body = runCatching { (if (status in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
                if (status == 415 && encoded.contentEncoding == "gzip") {
                    synchronized(gzipSuppression) {
                        val previous = gzipSuppression[deviceId] ?: Long.MIN_VALUE
                        gzipSuppression[deviceId] = maxOf(previous, capability!!.expiresAtElapsedMs)
                    }
                    forceIdentity = true
                } else {
                    if (status !in 200..299) {
                        if (enforceCapturedBudget && status in setOf(400, 401, 413, 422, 503)) invalidateSamplingCapability()
                        throw HttpException(status, body)
                    }
                    observeResponse(body, deviceId, requestWall, requestElapsed)
                    return IngestResponse(authorized, body, status)
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Encoding fallback exhausted")
    }

    private fun checkGeneration() {
        if (!generationGuard()) throw kotlinx.coroutines.CancellationException("Upload generation changed")
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
                val payload = buildPayload(emptyList(), sdkInfo, userDevice, userProperties, "register")
                Log.d(TAG, "Sending register event to $endpoint/ingest")
                val response = postPayload(payload)
                Log.d(TAG, "Register succeeded (HTTP ${response.status})")
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                if (e is HttpException) Log.e(TAG, "Register failed: HTTP ${e.statusCode} - ${e.body}")
                else Log.e(TAG, "Register request failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    suspend fun sendBeacons(
        beacons: List<Beacon>,
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?,
        /**
         * Why this payload is sent with no beacon: `encounter_mesh` (only peers nearby) or
         * `presence_heartbeat` (periodic report with no beacon or peer). Omitted for an
         * ordinary beacon sync.
         */
        syncTrigger: String? = null,
        onComplete: (Result<Unit>) -> Unit
    ) {
        // Nothing to report (no beacon, no peer, no location or Wi-Fi): skip the request.
        // Whether an empty scan is uploaded is decided upstream (BeAroundSDK.shouldReportEmptyScan).
        if (beacons.isEmpty() &&
            userDevice.encounters.isEmpty() &&
            userDevice.location == null &&
            userDevice.wifis.isEmpty()
        ) {
            onComplete(Result.success(Unit))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val payload = buildPayload(beacons, sdkInfo, userDevice, userProperties, syncTrigger)
                Log.d(TAG, "Sending ${beacons.size} beacon(s) to $endpoint/ingest")
                postPayload(payload)
                Log.d(TAG, "Successfully sent ${beacons.size} beacon(s)")
                onComplete(Result.success(Unit))
            } catch (e: Exception) {
                if (e is HttpException) Log.e(TAG, "API error: HTTP ${e.statusCode} - ${e.body}")
                else Log.e(TAG, "Request failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    internal fun buildPayload(
        beacons: List<Beacon>,
        sdkInfo: SDKInfo,
        userDevice: UserDevice,
        userProperties: UserProperties?,
        syncTrigger: String? = null
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
        syncTrigger?.let { payload.put("syncTrigger", it) }

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
                    // Part of the contract, not a leftover — see WifiObservation.ssid.
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
                // Timestamp of the fix, not of the payload (a fix can be minutes old).
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

        return filterPayload(payload)
    }

    internal fun buildDevicePayload(device: UserDevice): JSONObject {
        val payload = JSONObject()

        payload.put("deviceId", device.deviceId)
        device.pushToken?.let {
            payload.put("pushToken", it)
            // Token registration reports the native SDK version: the backend derives from it
            // whether the device can render rich push formats.
            payload.put("sdkVersion", io.bearound.sdk.BuildConfig.SDK_VERSION.take(32))
        }
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
            // Operator codes of the SIM and of the registered network.
            device.simMccMnc?.let { put("simMccMnc", it) }
            device.simOperatorName?.let { put("simOperatorName", it) }
            device.networkMccMnc?.let { put("networkMccMnc", it) }
            device.apId?.let { put("apId", it) }
            // Network name — part of the contract, see WifiObservation.ssid.
            device.wifiSSID?.let { put("wifiSSID", it) }
        }
        payload.put("network", network)

        // Permissions
        val permissions = JSONObject().apply {
            put("location", device.locationPermission)
            put("notifications", device.notificationsPermission)
            put("bluetooth", device.bluetoothState)
            device.locationAccuracy?.let { put("locationAccuracy", it) }
            // Sem isto, Wi-Fi vazio em background e Wi-Fi vazio por falta de permissao sao
            // indistinguiveis, e a segunda causa nao aparece em log nenhum.
            put("backgroundLocation", device.backgroundLocation)
            // The advertising ID is sent in `device.permissions.advertisingId`.
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

package io.bearound.sdk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.sampling.*
import io.bearound.sdk.utilities.OfflineBatchStorage
import io.bearound.sdk.utilities.SecureStorage
import io.bearound.sdk.utilities.SDKConfigStorage
import io.bearound.sdk.utilities.DiagnosticsStore
import java.util.Date
import java.io.File
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.visit.VisitTestFixtures
import io.bearound.sdk.visit.saveVisitEvent
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class APIClientSamplingTest {
    private var elapsed = 1_000_000L
    private var wall = 1_800_000_000_000L
    private val config = SDKConfiguration("fixture-token", "io.fixture")
    private fun client(endpoint: String = "http://127.0.0.1:1", policy: () -> DataCollectionPolicy = { DataCollectionPolicy.ALL_ENABLED }) =
        APIClient(config, endpoint, policy, { true }, { elapsed }, { wall })
    private fun capability() = JSONObject().apply {
        put("version", 1); put("modes", JSONArray(listOf("live", "summary", "transition")))
        put("leaseMs", 300_000L); put("serverTimeMs", wall); put("servicesCadenceMs", 5_000L)
        put("mapTtlMs", 300_000L); put("sessionTimeoutMs", 1_800_000L)
        put("liveEvidenceMaxAgeMs", 30_000L); put("clockSkewToleranceMs", 120_000L); put("maxPayloadBytes", 524_288)
    }
    private fun response(cap: JSONObject = capability()) = JSONObject().put("capabilities", JSONObject().put("adaptiveSampling", cap)).toString()
    private fun negotiate(client: APIClient) = client.observeResponse(response(), "device-1", wall, elapsed)
    private fun captured(client: APIClient, batch: String = UUID.randomUUID().toString()) = client.buildPayload(
        emptyList(), SDKInfo(appId = config.appId, build = 1), VisitTestFixtures.device().copy(deviceId = "device-1"), null
    ).put("sampling", JSONObject().apply {
        put("version", 1); put("kind", "summary"); put("batchId", batch)
        put("sentAt", wall - 1_000L); put("observedFrom", wall - 60_000L); put("observedTo", wall - 1_000L)
        put("clock", JSONObject().put("wallTimeMs", wall - 1_000L).put("monotonicTimeMs", elapsed - 1_000L))
        put("motion", JSONObject().put("state", "unknown").put("source", "none"))
    })
    private fun withServer(respond: (JSONObject) -> String, onRawRequest: (ByteArray) -> Unit = {},
                           onRequestHeaders: (Map<String, String>) -> Unit = {}, statusFor: (JSONObject) -> Int = { 200 },
                           block: suspend (String, MutableList<JSONObject>) -> Unit) = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) { val value = input.read(); if (value == -1 || value == 10) break; if (value != 13) bytes.write(value) }
                        return bytes.toString("UTF-8")
                    }
                    line()
                    var length = 0
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    if (length == 0) return@use
                    val raw = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val read = input.read(raw, offset, length - offset); check(read > 0); offset += read }
                    onRawRequest(raw)
                    onRequestHeaders(headers)
                    val decoded = if (headers["content-encoding"] == "gzip")
                        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() } else raw
                    val request = JSONObject(String(decoded, Charsets.UTF_8)); requests += request
                    val status = statusFor(request)
                    val response = respond(request).toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response); output.flush()
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) errors.set(error) }
        }
        try { block("http://127.0.0.1:${server.localPort}", requests) } finally {
            server.close(); worker.join(1_000); errors.get()?.let { throw it }
        }
    }

    private fun sdkForStoredDrain(context: Context, configuration: SDKConfiguration, client: APIClient,
                                 storage: OfflineBatchStorage, cachedDevice: io.bearound.sdk.models.UserDevice,
                                 properties: UserProperties, currentSources: Boolean = false): BeAroundSDK {
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        // R8 retains field types and the existing public coroutine accessor in both variants.
        fun set(type: Class<*>, value: Any) = BeAroundSDK::class.java.declaredFields.single { it.type == type }
            .apply { isAccessible = true }.set(sdk, value)
        set(Context::class.java, context)
        set(SDKConfiguration::class.java, configuration)
        set(APIClient::class.java, client)
        set(SDKInfo::class.java, SDKInfo(appId = configuration.appId, build = 1))
        set(OfflineBatchStorage::class.java, storage)
        set(io.bearound.sdk.models.UserDevice::class.java, cachedDevice)
        set(UserProperties::class.java, properties)
        if (currentSources) {
            set(io.bearound.sdk.utilities.DeviceInfoCollector::class.java, io.bearound.sdk.utilities.DeviceInfoCollector(context))
            set(io.bearound.sdk.BluetoothManager::class.java, io.bearound.sdk.BluetoothManager(context))
        }
        return sdk
    }

    private suspend fun drainSdkStoredBatches(sdk: BeAroundSDK): Boolean? = suspendCoroutine { continuation ->
        val method = BeAroundSDK::class.java.getDeclaredMethod("access\$drainStoredBatches", BeAroundSDK::class.java,
            Boolean::class.javaPrimitiveType, String::class.java, kotlin.coroutines.Continuation::class.java)
        val result = method.invoke(null, sdk, false, null, continuation)
        if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result as Boolean?)
    }

    @Test fun `normal registration negotiates capability without another discovery call`() = withServer({ response() }) { endpoint, requests ->
        val client = client(endpoint)
        var success = false
        client.sendRegister(SDKInfo(appId = config.appId, build = 1), VisitTestFixtures.device().copy(deviceId = "device-1"), null) { success = it.isSuccess }
        assertTrue(success)
        assertEquals(1, requests.size)
        assertEquals("register", requests.single().getString("syncTrigger"))
        assertFalse(requests.single().has("sampling"))
        assertEquals(5_000L, client.samplingCapability("device-1")!!.policy.advertisedServicesCadenceMs)
    }

    @Test fun `capability is bound to device expires monotonically and rejects incompatible contracts`() {
        val client = client()
        negotiate(client)
        assertNull(client.samplingCapability("other-device"))
        negotiate(client)
        elapsed += 300_000L
        assertNull(client.samplingCapability("device-1"))
        listOf(capability().put("version", 1.5), capability().put("version", 2), capability().put("modes", JSONArray(listOf("live"))),
            capability().put("sessionTimeoutMs", 1L), capability().put("mapTtlMs", 0),
            capability().put("maxPayloadBytes", 524_289L), capability().put("leaseMs", 300_001L)).forEach {
            client.observeResponse(response(it), "device-1", wall, elapsed)
            assertNull(client.samplingCapability("device-1"))
        }
    }

    @Test fun `large round trip uncertainty and clock regression disable separation`() {
        val client = client()
        client.observeResponse(response(), "device-1", wall, elapsed - 10_001L)
        assertNull(client.samplingCapability("device-1"))
        negotiate(client)
        elapsed--
        assertNull(client.samplingCapability("device-1"))
    }

    @Test fun `matching durable acknowledgment preserves original retry clock and context`() = withServer({ request ->
        JSONObject().put("samplingAck", JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
            .put("archive", "durable").put("projection", "duplicate").put("fallback", "keep")).toString()
    }) { endpoint, requests ->
        val client = client(endpoint)
        negotiate(client)
        val original = captured(client)
        val before = original.toString()
        wall += 120_000L; elapsed += 120_000L
        val result = client.sendStoredPayload(before)
        assertTrue(result.isSuccess)
        assertEquals(original.getJSONObject("sampling").getLong("sentAt"), requests.single().getJSONObject("sampling").getLong("sentAt"))
        assertEquals(original.getJSONObject("sampling").getJSONObject("clock").toString(), requests.single().getJSONObject("sampling").getJSONObject("clock").toString())
        assertEquals(before, original.toString())
    }

    @Test fun `HTTP success without a matching durable acknowledgment leaves the batch unconfirmed`() = withServer({
        JSONObject().put("samplingAck", JSONObject().put("batchId", UUID.randomUUID().toString()).put("archive", "durable")).toString()
    }) { endpoint, _ ->
        val client = client(endpoint)
        negotiate(client)
        assertTrue(client.sendStoredPayload(captured(client).toString()).isFailure)
    }

    @Test fun `degraded acknowledgment invalidates capability immediately and retains original batch identity`() = withServer({ request ->
        JSONObject(response()).put("samplingAck", JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
            .put("archive", "durable").put("projection", "archive_only").put("fallback", "legacy_now")
            .put("invalidateCapability", true)).toString()
    }) { endpoint, _ ->
        val client = client(endpoint)
        negotiate(client)
        assertTrue(client.sendStoredPayload(captured(client).toString()).isSuccess)
        assertNull(client.samplingCapability("device-1"))
    }

    @Test fun `historical sampling requires capability and cannot be silently resent as legacy`() = withServer({ response() }) { endpoint, requests ->
        val client = client(endpoint)
        assertTrue(client.sendStoredPayload(captured(client).toString()).isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test fun `current collection policy strips recorded sensitive fields without reading replacement context`() {
        var policy = DataCollectionPolicy.ALL_ENABLED
        val client = client(policy = { policy })
        val original = captured(client).put("location", JSONObject().put("latitude", 1.0))
            .put("wifis", JSONArray().put(JSONObject().put("apId", "old-ap")))
        original.getJSONObject("device").getJSONObject("network").put("apId", "old-ap").put("wifiSSID", "old-network")
        original.getJSONObject("device").getJSONObject("permissions").put("advertisingId", "old-ad").put("limitAdTracking", false)
        original.getJSONObject("sampling").put("motion", JSONObject().put("source", "context_fusion").put("state", "stationary_inferred"))
        policy = DataCollectionPolicy(false, false, false)
        val filtered = client.filterPayload(original)
        assertFalse(filtered.has("location")); assertFalse(filtered.has("wifis"))
        assertFalse(filtered.getJSONObject("device").getJSONObject("network").has("apId"))
        assertFalse(filtered.getJSONObject("device").getJSONObject("network").has("wifiSSID"))
        assertFalse(filtered.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
        assertEquals("source_disabled", filtered.getJSONObject("sampling").getJSONObject("motion").getString("reason"))
        assertTrue(original.has("location"))
    }
    @Test fun `identity and push setters close original windows and refresh cached current context`() {
        org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecureStorage.initialize(context)
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        val client = client()
        val device = VisitTestFixtures.device().copy(deviceId = "device-1")
        val info = SDKInfo(appId = config.appId, build = 1)
        val engine = ObservationSummary({ wall }, { elapsed })
        val evidence = MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE,
            MotionReason.STARTUP, null, null, false)
        field("context").set(sdk, context); field("offlineBatchStorage").set(sdk, storage)
        field("apiClient").set(sdk, client); field("sdkInfo").set(sdk, info)
        field("samplingDevice").set(sdk, device); field("observationSummary").set(sdk, engine)
        sdk.setUserProperties(UserProperties(internalId = "original-user"))
        val oldContext = field("samplingContextJson").get(sdk) as String
        val oldGeneration = field("samplingContextId").get(sdk)
        val beacon = Beacon(UUID.randomUUID(), 1, 1, -60, Beacon.Proximity.NEAR, 1.0, Date(wall))
        engine.capture(listOf(beacon), oldContext, evidence)
        sdk.setUserProperties(UserProperties(internalId = "changed-user"))
        val newContext = field("samplingContextJson").get(sdk) as String
        assertEquals("original-user", JSONObject(storage.loadAllRecords().single().requestJson!!).getJSONObject("userProperties").getString("internalId"))
        assertEquals("changed-user", JSONObject(newContext).getJSONObject("userProperties").getString("internalId"))
        assertNotEquals(oldGeneration, field("samplingContextId").get(sdk))
        assertEquals(oldGeneration, storage.loadAllRecords().single().captureContextId)
        wall++; elapsed++; engine.capture(listOf(beacon.copy(timestamp = Date(wall))), newContext, evidence)
        sdk.setPushToken("fixture-push-token")
        val pushContext = JSONObject(field("samplingContextJson").get(sdk) as String)
        assertEquals("fixture-push-token", pushContext.getJSONObject("device").getString("pushToken"))
        assertFalse(JSONObject(storage.loadAllRecords().last().requestJson!!).getJSONObject("device").has("pushToken"))
        assertEquals(device.timestamp, pushContext.getJSONObject("device").getLong("timestamp"))
        sdk.clearUserProperties()
        assertFalse(JSONObject(field("samplingContextJson").get(sdk) as String).has("userProperties"))
    }

    @Test fun `wall clock jumps invalidate the live lease without rebasing old payloads`() {
        val client = client()
        negotiate(client)
        val original = captured(client).toString()
        wall -= 120_001L
        assertNull(client.samplingCapability("device-1"))
        assertTrue(JSONObject(original).getJSONObject("sampling").getLong("sentAt") > wall)
    }

    @Test fun `smaller advertised budget preserves queued evidence and the valid lease`() = withServer({ response() }) { endpoint, requests ->
        val client = client(endpoint)
        client.observeResponse(response(capability().put("maxPayloadBytes", 100)), "device-1", wall, elapsed)
        val body = captured(client).toString()
        val result = client.sendStoredPayload(body)
        assertTrue(result.isFailure); assertFalse(result.exceptionOrNull() is HttpException)
        assertTrue(requests.isEmpty()); assertNotNull(client.samplingCapability("device-1"))
    }

    @Test fun `restored callback captured before the active generation never reads current collection sources`() {
        org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("context").set(sdk, context); field("configuration").set(sdk, config)
        SDKConfigStorage.saveScanningEnabled(context, true)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(60))
        val now = android.os.SystemClock.elapsedRealtime()
        field("samplingGenerationStartedAtElapsed").set(sdk, now)
        DiagnosticsStore.reset()
        val beacon = Beacon(UUID.randomUUID(), 1, 1, -60, Beacon.Proximity.NEAR, 1.0, Date())
        BeAroundSDK::class.java.getDeclaredMethod("capturePhysicalObservation", Beacon::class.java, java.lang.Long::class.java)
            .apply { isAccessible = true }.invoke(sdk, beacon, now - 1L)
        assertEquals(0L, DiagnosticsStore.sampling().uniqueSamples)
        assertEquals(1L, DiagnosticsStore.sampling().cachedObservations)
        assertEquals(0L, DiagnosticsStore.sampling().contextReads)
    }

    @Test fun `drain waiting for its lock cannot pair new tenant rows with an earlier client`() = withServer({ "{}" }) { endpoint, requests ->
        org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "old-tenant" }
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("context").set(sdk, context); field("apiClient").set(sdk, client(endpoint))
        field("sdkInfo").set(sdk, SDKInfo(appId = config.appId, build = 1)); field("offlineBatchStorage").set(sdk, storage)
        field("samplingDevice").set(sdk, VisitTestFixtures.device().copy(deviceId = "device-1"))
        val mutex = field("drainMutex").get(sdk) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        val method = BeAroundSDK::class.java.getDeclaredMethod("drainStoredBatches", Boolean::class.javaPrimitiveType,
            String::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
        val operation = kotlinx.coroutines.coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                suspendCoroutine<Boolean?> { continuation ->
                    val result = method.invoke(sdk, false, null, continuation)
                    if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result as Boolean?)
                }
            }
            field("samplingGeneration").set(sdk, 1L)
            storage.currentTenantId = "new-tenant"
            val original = client(endpoint).buildPayload(emptyList(), SDKInfo(appId = config.appId, build = 1),
                VisitTestFixtures.device().copy(deviceId = "device-1", timestamp = System.currentTimeMillis()), null).toString()
            val id = storage.saveRequestReturningId(original)!!
            mutex.unlock()
            assertNull(deferred.await()); assertEquals(id, storage.loadAllRecords().single().id)
            deferred
        }
        assertTrue(operation.isCompleted); assertTrue(requests.isEmpty())
    }

    @Test fun `fresh boundary live pages are delivered before archival backlog with shared physical IDs`() = withServer({ request ->
        JSONObject().put("samplingAck", JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
            .put("archive", "durable").put("projection", "applied").put("fallback", "keep")).toString()
    }) { endpoint, requests ->
        org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecureStorage.initialize(context)
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("context").set(sdk, context)
        val device = VisitTestFixtures.device().copy(deviceId = sdk.deviceId, timestamp = System.currentTimeMillis())
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        wall = System.currentTimeMillis(); elapsed = android.os.SystemClock.elapsedRealtime()
        val client = APIClient(config, endpoint, { DataCollectionPolicy.ALL_ENABLED }, { true },
            android.os.SystemClock::elapsedRealtime, { wall })
        client.observeResponse(response(), device.deviceId, wall, elapsed)
        val info = SDKInfo(appId = config.appId, build = 1)
        val originalContext = client.buildPayload(emptyList(), info, device, null).toString()
        val engine = ObservationSummary({ wall }, android.os.SystemClock::elapsedRealtime)
        engine.synchronizeClock(0.0, 1.0, elapsed)
        val evidence = MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE, MotionReason.STARTUP, null, null, false)
        val uuid = UUID.randomUUID()
        fun beacon(minor: Int) = Beacon(uuid, 1, minor, -60, Beacon.Proximity.NEAR, 1.0, Date(wall))
        engine.capture((1..100).map(::beacon), originalContext, evidence)
        val closed = engine.capture(listOf(beacon(101)), originalContext, evidence, closeLiveMaxAgeMs = 30_000L)
        field("configuration").set(sdk, config); field("apiClient").set(sdk, client); field("sdkInfo").set(sdk, info)
        field("samplingDevice").set(sdk, device); field("offlineBatchStorage").set(sdk, storage); field("observationSummary").set(sdk, engine)
        SDKConfigStorage.saveScanningEnabled(context, true)
        storage.saveRequestReturningId(closed[1].payloadJson, captureContextId = "context")
        BeAroundSDK::class.java.getDeclaredMethod("queuePreparedLive", PreparedObservation::class.java)
            .apply { isAccessible = true }.invoke(sdk, closed[0])
        val method = BeAroundSDK::class.java.getDeclaredMethod("performAdaptiveSync", APIClient::class.java,
            Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
        val result = suspendCoroutine<Boolean> { continuation ->
            val value = method.invoke(sdk, client, false, continuation)
            if (value != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(value as Boolean)
        }
        assertTrue(result)
        val live = requests.filter { it.getJSONObject("sampling").getString("kind") == "live" && !it.getJSONObject("sampling").has("replay") }
        assertEquals(listOf(100, 1), live.map { it.getJSONArray("beacons").length() })
        assertEquals("live", requests.first().getJSONObject("sampling").getString("kind"))
        val archived = requests.single { it.getJSONObject("sampling").getString("batchId") == closed[1].batchId }
        assertTrue(archived.getJSONObject("sampling").has("replay"))
        assertEquals(live[0].getJSONArray("beacons").getJSONObject(0).getString("sampleId"),
            archived.getJSONArray("beacons").getJSONObject(0).getJSONArray("temporalBuckets").getJSONObject(0).getString("lastSampleId"))
        assertEquals(0, storage.getBatchCount())
    }

    @Test fun `fresh diagnostic bytes match filtered HTTP bodies for Gson doubles and slash escaping`() {
        val wireBytes = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        withServer({ request ->
            JSONObject().put("samplingAck", JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
                .put("archive", "durable").put("projection", "applied").put("fallback", "keep")).toString()
        }, onRawRequest = { wireBytes += it }) { endpoint, requests ->
            org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
            val context = ApplicationProvider.getApplicationContext<Context>()
            SecureStorage.initialize(context)
            File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
            val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("context").set(sdk, context)
            val device = VisitTestFixtures.device().copy(deviceId = sdk.deviceId, timestamp = System.currentTimeMillis())
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
            wall = System.currentTimeMillis(); elapsed = android.os.SystemClock.elapsedRealtime()
            val client = APIClient(config, endpoint, { DataCollectionPolicy(false, false, false) }, { true },
                android.os.SystemClock::elapsedRealtime, { wall })
            client.observeResponse(response(), device.deviceId, wall, elapsed)
            val info = SDKInfo(appId = config.appId, build = 1)
            val originalContext = client.buildPayload(emptyList(), info, device,
                UserProperties(internalId = "fixture/path")).put("location", JSONObject().put("latitude", 1.0))
                .put("wifis", JSONArray().put(JSONObject().put("apId", "fixture-ap"))).apply {
                    getJSONObject("device").getJSONObject("network").put("apId", "fixture-ap").put("wifiSSID", "fixture-network")
                    getJSONObject("device").getJSONObject("permissions").put("advertisingId", "fixture-ad").put("limitAdTracking", false)
                }.toString()
            val engine = ObservationSummary({ wall }, android.os.SystemClock::elapsedRealtime)
            engine.synchronizeClock(0.0, 1.0, elapsed)
            val evidence = MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE,
                MotionReason.STARTUP, null, null, false)
            val beacon = Beacon(UUID.randomUUID(), 1, 1, -60, Beacon.Proximity.NEAR, 1.0, Date(wall))
            engine.capture(listOf(beacon), originalContext, evidence)
            val originalLive = engine.live(30_000L, evidence)!!
            assertTrue(originalLive.payloadJson.contains("\"serverOffsetMs\":0.0"))
            assertTrue(originalLive.payloadJson.contains("fixture/path"))
            field("configuration").set(sdk, config); field("apiClient").set(sdk, client); field("sdkInfo").set(sdk, info)
            field("samplingDevice").set(sdk, device); field("offlineBatchStorage").set(sdk, storage); field("observationSummary").set(sdk, engine)
            field("lastSummaryElapsed").set(sdk, elapsed - 300_000L)
            SDKConfigStorage.saveScanningEnabled(context, true)
            BeAroundSDK::class.java.getDeclaredMethod("queuePreparedLive", PreparedObservation::class.java)
                .apply { isAccessible = true }.invoke(sdk, originalLive)
            DiagnosticsStore.reset()
            val method = BeAroundSDK::class.java.getDeclaredMethod("performAdaptiveSync", APIClient::class.java,
                Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
            val result = suspendCoroutine<Boolean> { continuation ->
                val value = method.invoke(sdk, client, false, continuation)
                if (value != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(value as Boolean)
            }
            assertTrue(result)
            assertEquals(listOf("live", "summary"), requests.map { it.getJSONObject("sampling").getString("kind") })
            requests.forEach {
                assertFalse(it.has("location")); assertFalse(it.has("wifis"))
                assertFalse(it.getJSONObject("device").getJSONObject("network").has("apId"))
                assertFalse(it.getJSONObject("device").getJSONObject("network").has("wifiSSID"))
                assertFalse(it.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
                assertFalse(it.getJSONObject("device").getJSONObject("permissions").has("limitAdTracking"))
            }
            val firstWire = String(wireBytes.first(), Charsets.UTF_8)
            assertTrue(firstWire.contains("\"serverOffsetMs\":0"))
            assertFalse(firstWire.contains("\"serverOffsetMs\":0.0"))
            assertTrue(firstWire.contains("fixture\\/path"))
            val diagnostics = DiagnosticsStore.sampling()
            assertEquals(1L, diagnostics.liveAttempts); assertEquals(1L, diagnostics.summaryAttempts)
            assertEquals(0L, diagnostics.failures)
            assertEquals(wireBytes.sumOf { it.size.toLong() }, diagnostics.serializedBytes)
            assertEquals(0, storage.getBatchCount())
            assertTrue(JSONObject(originalLive.payloadJson).has("location"))
            assertEquals(0L, diagnostics.contextReads)
        }
    }

    @Test fun `serialization observer reports each attempted body once even when its acknowledgment fails`() {
        val wireSizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        var policy = DataCollectionPolicy(false, false, false)
        var policyReads = 0
        withServer({ request ->
            policy = DataCollectionPolicy.ALL_ENABLED
            if (request.getJSONObject("sampling").getString("kind") == "live") "{}" else
                JSONObject().put("samplingAck", JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
                    .put("archive", "durable").put("projection", "applied")).toString()
        }, onRawRequest = { wireSizes += it.size }) { endpoint, requests ->
            val client = client(endpoint) { policyReads++; policy }
            negotiate(client)
            val attempts = mutableListOf<Int>()
            val original = captured(client).put("location", JSONObject().put("latitude", 1.0))
                .put("wifis", JSONArray().put(JSONObject().put("apId", "fixture-ap"))).toString()
            policyReads = 0
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            assertEquals(2, policyReads)
            assertFalse(requests.first().has("location")); assertFalse(requests.first().has("wifis"))
            val unconfirmed = JSONObject(original).apply { getJSONObject("sampling").put("kind", "live") }.toString()
            assertTrue(client.sendStoredPayload(unconfirmed) { attempts += it }.isFailure)
            assertEquals(4, policyReads)
            assertEquals(wireSizes, attempts)
            assertEquals(2, attempts.size)
            assertTrue(JSONObject(original).has("location"))
        }
    }

    @Test fun `serialization observer skips denied malformed unbound and over budget payloads`() = withServer({ response() }) { endpoint, requests ->
        val attempts = mutableListOf<Int>()
        val original = captured(client()).toString()
        val unavailable = client(endpoint)
        assertTrue(unavailable.sendStoredPayload(original) { attempts += it }.isFailure)
        val denied = client(endpoint) { throw SecurityException("Fixture collection policy denied") }
        negotiate(denied)
        assertTrue(denied.sendStoredPayload(original) { attempts += it }.isFailure)
        val client = client(endpoint)
        negotiate(client)
        assertTrue(client.sendStoredPayload("invalid-json") { attempts += it }.isFailure)
        val unbound = JSONObject(original).apply { getJSONObject("sdk").put("appId", "other-app") }.toString()
        assertTrue(client.sendStoredPayload(unbound) { attempts += it }.isFailure)
        client.observeResponse(response(capability().put("maxPayloadBytes", 100)), "device-1", wall, elapsed)
        assertTrue(client.sendStoredPayload(original) { attempts += it }.isFailure)
        assertTrue(attempts.isEmpty()); assertTrue(requests.isEmpty())
    }

    @Test fun `connection failure before body submission reports no attempted body`() = runBlocking {
        val client = client()
        negotiate(client)
        val original = captured(client).toString()
        val attempts = mutableListOf<Int>()
        val result = client.sendStoredPayload(original) { attempts += it }
        assertTrue(result.isFailure)
        assertTrue(attempts.isEmpty())
    }

    private fun gzipResponse() = response(capability().put("requestContentEncodings", JSONArray(listOf("gzip"))))
    private fun negotiateGzip(client: APIClient, deviceId: String = "device-1") = client.observeResponse(gzipResponse(), deviceId, wall, elapsed)
    private fun durableAck(request: JSONObject, advertiseGzip: Boolean = false): String =
        (if (advertiseGzip) JSONObject(gzipResponse()) else JSONObject()).put("samplingAck",
            JSONObject().put("batchId", request.getJSONObject("sampling").getString("batchId"))
                .put("archive", "durable").put("projection", "applied").put("fallback", "keep")).toString()

    @Test fun `registration starts identity and subsequent beacon upload uses negotiated RFC gzip`() {
        val wires = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ gzipResponse() }, onRawRequest = { wires += it }, onRequestHeaders = { headers += it }) { endpoint, requests ->
            val client = client(endpoint)
            val info = SDKInfo(appId = config.appId, build = 1)
            val device = VisitTestFixtures.device().copy(deviceId = "device-1")
            val properties = UserProperties(internalId = "fixture/ação")
            var success = false
            client.sendRegister(info, device, properties) { success = it.isSuccess }
            assertTrue(success)
            val beacon = Beacon(UUID.randomUUID(), 1, 1, -60, Beacon.Proximity.NEAR, 1.0, Date(wall))
            val expected = client.buildPayload(listOf(beacon), info, device, properties).toString().toByteArray(Charsets.UTF_8)
            client.sendBeacons(listOf(beacon), info, device, properties) { success = it.isSuccess }
            assertTrue(success)
            assertFalse(headers[0].containsKey("content-encoding"))
            assertEquals("application/json", headers[0]["content-type"])
            assertEquals("gzip", headers[1]["content-encoding"])
            assertEquals("application/octet-stream", headers[1]["content-type"])
            assertEquals(31, wires[1][0].toInt()); assertEquals(139, wires[1][1].toInt() and 255)
            assertArrayEquals(expected, GZIPInputStream(ByteArrayInputStream(wires[1])).use { it.readBytes() })
            assertTrue(wires[1].size < expected.size)
            assertEquals(wires[1].size.toString(), headers[1]["content-length"])
            assertEquals("fixture/ação", requests[1].getJSONObject("userProperties").getString("internalId"))
        }
    }

    @Test fun `missing unknown duplicated and malformed encoding options preserve the valid identity lease`() {
        val options = listOf<Any?>(null, JSONObject.NULL, "gzip", JSONArray(), JSONArray(listOf("br")),
            JSONArray(listOf("GZIP")), JSONArray(listOf("gzip", "gzip")), JSONArray(listOf("gzip", "br")),
            JSONArray().put("gzip").put(1))
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it) }, onRequestHeaders = { headers += it }) { endpoint, _ ->
            val client = client(endpoint)
            options.forEach { value ->
                val cap = capability(); if (value != null) cap.put("requestContentEncodings", value)
                client.observeResponse(response(cap), "device-1", wall, elapsed)
                assertNotNull(client.samplingCapability("device-1"))
                assertFalse(client.samplingCapability("device-1")!!.requestGzipEnabled)
                assertTrue(client.sendStoredPayload(captured(client).toString()).isSuccess)
            }
            assertEquals(options.size, headers.size)
            assertTrue(headers.all { !it.containsKey("content-encoding") && it["content-type"] == "application/json" })
        }
    }

    @Test fun `expired device or endpoint authorization cannot select gzip`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ "{}" }, onRequestHeaders = { headers += it }) { endpoint, _ ->
            val client = client(endpoint)
            negotiateGzip(client)
            assertNull(client.withEndpoint(endpoint).samplingCapability("device-1"))
            elapsed += 300000; wall += 300000
            assertNull(client.samplingCapability("device-1"))
            val legacy = captured(client).apply { remove("sampling") }.toString()
            assertTrue(client.sendStoredPayload(legacy).isSuccess)
            negotiateGzip(client)
            val otherDevice = JSONObject(legacy).apply { getJSONObject("device").put("deviceId", "other-device") }
            assertTrue(client.sendStoredPayload(otherDevice.toString()).isSuccess)
            assertTrue(headers.all { !it.containsKey("content-encoding") })
        }
    }

    @Test fun `original JSON budget rejects a highly compressible body before any HTTP submission`() = withServer({ durableAck(it) }) { endpoint, requests ->
        val client = client(endpoint)
        client.observeResponse(response(capability().put("maxPayloadBytes", 1024).put("requestContentEncodings", JSONArray(listOf("gzip")))), "device-1", wall, elapsed)
        val original = captured(client).put("fixturePadding", "x".repeat(64000)).toString()
        assertTrue(encodeRequestBody(original.toByteArray(Charsets.UTF_8), true).data.size < 1024)
        val attempts = mutableListOf<Int>()
        assertTrue(client.sendStoredPayload(original) { attempts += it }.isFailure)
        assertTrue(attempts.isEmpty()); assertTrue(requests.isEmpty())
        assertNotNull(client.samplingCapability("device-1"))
    }

    @Test fun `compression failure or no benefit selects unchanged identity before submission`() {
        val original = "{}".toByteArray(Charsets.UTF_8)
        listOf(encodeRequestBody(original, true), encodeRequestBody(original, true) { error("Fixture compression failure") },
            encodeRequestBody(original, true) { it.copyOf(it.size + 1) }).forEach {
            assertArrayEquals(original, it.data); assertNull(it.contentEncoding)
            assertEquals("application/json", it.contentType); assertEquals(original.size, it.originalJsonBytes)
        }
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ "{}" }, onRequestHeaders = { headers += it }) { endpoint, _ ->
            val client = client(endpoint); negotiateGzip(client)
            val compact = JSONObject().put("device", JSONObject().put("deviceId", "device-1"))
                .put("sdk", JSONObject().put("appId", config.appId)).toString()
            assertTrue(encodeRequestBody(compact.toByteArray(Charsets.UTF_8), true).contentEncoding == null)
            assertTrue(client.sendStoredPayload(compact).isSuccess)
            assertFalse(headers.single().containsKey("content-encoding"))
        }
    }

    @Test fun `gzip 415 retries identity once and remains suppressed through the original failed lease`() {
        val wires = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ if (headers.size == 1) "{}" else durableAck(it, true) }, onRawRequest = { wires += it },
            onRequestHeaders = { headers += it }, statusFor = { if (headers.size == 1) 415 else 200 }) { endpoint, requests ->
            val client = client(endpoint); negotiateGzip(client)
            val original = captured(client).toString()
            val attempts = mutableListOf<Int>()
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            elapsed += 1000; wall += 1000
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            elapsed += 299000; wall += 299000
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            assertEquals(listOf("gzip", null, null, "gzip"), headers.map { it["content-encoding"] })
            assertEquals(wires.map { it.size }, attempts)
            assertEquals(4, attempts.size)
            assertEquals(1, requests.map { it.toString() }.toSet().size)
        }
    }

    @Test fun `identity rejection after gzip 415 cannot create a third immediate attempt`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ "{}" }, onRequestHeaders = { headers += it }, statusFor = { 415 }) { endpoint, requests ->
            val client = client(endpoint); negotiateGzip(client)
            val attempts = mutableListOf<Int>()
            val result = client.sendStoredPayload(captured(client).toString()) { attempts += it }
            assertEquals(415, (result.exceptionOrNull() as HttpException).statusCode)
            assertEquals(listOf("gzip", null), headers.map { it["content-encoding"] })
            assertEquals(2, requests.size); assertEquals(2, attempts.size)
        }
    }

    @Test fun `gzip corruption and budget HTTP rejections never retry identity`() {
        listOf(400, 413).forEach { status ->
            val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
            withServer({ "{}" }, onRequestHeaders = { headers += it }, statusFor = { status }) { endpoint, requests ->
                val client = client(endpoint); negotiateGzip(client)
                val attempts = mutableListOf<Int>()
                val result = client.sendStoredPayload(captured(client).toString()) { attempts += it }
                assertEquals(status, (result.exceptionOrNull() as HttpException).statusCode)
                assertEquals("gzip", headers.single()["content-encoding"])
                assertEquals(1, requests.size); assertEquals(1, attempts.size)
                assertNull(client.samplingCapability("device-1"))
            }
        }
    }

    @Test fun `identity fallback reapplies revocation without recollecting or restoring previously denied fields`() {
        listOf(false, true).forEach { initiallyEnabled ->
            val policy = java.util.concurrent.atomic.AtomicReference(if (initiallyEnabled) DataCollectionPolicy.ALL_ENABLED else DataCollectionPolicy(false, false, false))
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
            withServer({ durableAck(it, true) }, onRequestHeaders = { headers += it }, statusFor = {
                if (headers.size == 1) {
                    policy.set(if (initiallyEnabled) DataCollectionPolicy(false, false, false) else DataCollectionPolicy.ALL_ENABLED)
                    415
                } else 200
            }) { endpoint, requests ->
                val client = client(endpoint) { reads.incrementAndGet(); policy.get() }; negotiateGzip(client)
                val original = captured(client()).put("location", JSONObject().put("latitude", 1.0))
                    .put("wifis", JSONArray().put(JSONObject().put("apId", "fixture-ap")))
                val before = original.toString()
                assertTrue(client.sendStoredPayload(before).isSuccess)
                assertEquals(4, reads.get())
                assertEquals(initiallyEnabled, requests[0].has("location"))
                assertFalse(requests[1].has("location")); assertFalse(requests[1].has("wifis"))
                assertEquals(before, original.toString())
                assertEquals(requests[0].getJSONObject("sampling").getString("batchId"), requests[1].getJSONObject("sampling").getString("batchId"))
                assertEquals(requests[0].getJSONObject("sampling").getJSONObject("clock").toString(), requests[1].getJSONObject("sampling").getJSONObject("clock").toString())
            }
        }
    }

    @Test fun `stale SDK generation after gzip rejection blocks fallback before another body`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        lateinit var sdk: BeAroundSDK
        withServer({ "{}" }, onRequestHeaders = { headers += it }, statusFor = {
            BeAroundSDK::class.java.getDeclaredField("samplingGeneration").apply { isAccessible = true }.set(sdk, 1L)
            415
        }) { endpoint, requests ->
            org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
            val context = ApplicationProvider.getApplicationContext<Context>()
            sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            BeAroundSDK::class.java.getDeclaredField("context").apply { isAccessible = true }.set(sdk, context)
            wall = System.currentTimeMillis(); elapsed = android.os.SystemClock.elapsedRealtime()
            val oldResolver = SDKConfiguration.endpointResolver
            val client = try {
                SDKConfiguration.endpointResolver = { endpoint }
                BeAroundSDK::class.java.getDeclaredMethod("newAPIClient", SDKConfiguration::class.java)
                    .apply { isAccessible = true }.invoke(sdk, SDKConfiguration(config.businessToken, config.appId)) as APIClient
            } finally { SDKConfiguration.endpointResolver = oldResolver }
            BeAroundSDK::class.java.getDeclaredField("apiClient").apply { isAccessible = true }.set(sdk, client)
            negotiateGzip(client)
            val attempts = mutableListOf<Int>()
            val result = client.sendStoredPayload(captured(client()).toString()) { attempts += it }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(1, attempts.size); assertEquals(1, requests.size)
            assertEquals("gzip", headers.single()["content-encoding"])
        }
    }

    @Test fun `gzip suppression is isolated from a newly bound API client`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it, true) }, onRequestHeaders = { headers += it },
            statusFor = { if (headers.size == 1) 415 else 200 }) { endpoint, _ ->
            val client = client(endpoint); negotiateGzip(client)
            val original = captured(client).toString()
            assertTrue(client.sendStoredPayload(original).isSuccess)
            val replacement = client(endpoint); negotiateGzip(replacement)
            assertTrue(replacement.sendStoredPayload(original).isSuccess)
            assertEquals(listOf("gzip", null, "gzip"), headers.map { it["content-encoding"] })
        }
    }

    @Test fun `SDK fresh and replay diagnostics sum every submitted body while durable originals keep their IDs`() {
        listOf(false, true).forEach { rejectFirst ->
            val wires = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
            val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
            withServer({ durableAck(it, true) }, onRawRequest = { wires += it }, onRequestHeaders = { headers += it },
                statusFor = { if (rejectFirst && headers.size == 1) 415 else 200 }) { endpoint, requests ->
                org.junit.Assume.assumeTrue("Private SDK wiring is validated in Debug; Release obfuscates private members", io.bearound.sdk.BuildConfig.DEBUG)
                val context = ApplicationProvider.getApplicationContext<Context>()
                SecureStorage.initialize(context)
                File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
                val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
                field("context").set(sdk, context)
                val device = VisitTestFixtures.device().copy(deviceId = sdk.deviceId, timestamp = System.currentTimeMillis())
                val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
                wall = System.currentTimeMillis(); elapsed = android.os.SystemClock.elapsedRealtime()
                val client = APIClient(config, endpoint, { DataCollectionPolicy.ALL_ENABLED }, { true }, android.os.SystemClock::elapsedRealtime, { wall })
                client.observeResponse(gzipResponse(), device.deviceId, wall, elapsed)
                val info = SDKInfo(appId = config.appId, build = 1)
                val originalContext = client.buildPayload(emptyList(), info, device, UserProperties(internalId = "fixture/ação")).toString()
                val engine = ObservationSummary({ wall }, android.os.SystemClock::elapsedRealtime)
                engine.synchronizeClock(0.0, 1.0, elapsed)
                val evidence = MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE, MotionReason.STARTUP, null, null, false)
                val beacon = Beacon(UUID.randomUUID(), 1, 1, -60, Beacon.Proximity.NEAR, 1.0, Date(wall))
                engine.capture(listOf(beacon), originalContext, evidence)
                val live = engine.live(30000L, evidence)!!
                val summary = engine.close()!!
                field("configuration").set(sdk, config); field("apiClient").set(sdk, client); field("sdkInfo").set(sdk, info)
                field("samplingDevice").set(sdk, device); field("offlineBatchStorage").set(sdk, storage); field("observationSummary").set(sdk, engine)
                SDKConfigStorage.saveScanningEnabled(context, true)
                storage.saveRequestReturningId(summary.payloadJson, captureContextId = "context")
                BeAroundSDK::class.java.getDeclaredMethod("queuePreparedLive", PreparedObservation::class.java)
                    .apply { isAccessible = true }.invoke(sdk, live)
                val persisted = storage.loadAllRecords().associate { it.id to it.requestJson }
                DiagnosticsStore.reset()
                val method = BeAroundSDK::class.java.getDeclaredMethod("performAdaptiveSync", APIClient::class.java,
                    Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                val result = suspendCoroutine<Boolean> { continuation ->
                    val value = method.invoke(sdk, client, false, continuation)
                    if (value != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(value as Boolean)
                }
                assertTrue(result)
                val diagnostics = DiagnosticsStore.sampling()
                assertEquals(1L, diagnostics.liveAttempts); assertEquals(1L, diagnostics.summaryAttempts)
                assertEquals((if (rejectFirst) 3 else 2).toLong(), diagnostics.httpAttempts)
                assertEquals(wires.sumOf { it.size.toLong() }, diagnostics.serializedBytes)
                assertEquals(0L, diagnostics.failures); assertEquals(0L, diagnostics.contextReads)
                assertEquals(if (rejectFirst) listOf("gzip", null, null) else listOf("gzip", "gzip"), headers.map { it["content-encoding"] })
                assertEquals(live.batchId, requests.first().getJSONObject("sampling").getString("batchId"))
                if (rejectFirst) assertEquals(requests[0].toString(), requests[1].toString())
                val replay = requests.last(); val originalSummary = JSONObject(persisted.getValue(summary.batchId)!!)
                assertTrue(replay.getJSONObject("sampling").has("replay"))
                assertEquals(originalSummary.getJSONObject("sampling").getLong("sentAt"), replay.getJSONObject("sampling").getLong("sentAt"))
                assertEquals(originalSummary.getJSONObject("sampling").getJSONObject("clock").toString(), replay.getJSONObject("sampling").getJSONObject("clock").toString())
                assertEquals(requests.first().getJSONArray("beacons").getJSONObject(0).getString("sampleId"),
                    replay.getJSONArray("beacons").getJSONObject(0).getJSONArray("temporalBuckets").getJSONObject(0).getString("lastSampleId"))
                assertEquals(0, storage.getBatchCount())
            }
        }
    }

    @Test fun `failed leases remain separately suppressed when a client switches device bindings`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it, true) }, onRequestHeaders = { headers += it },
            statusFor = { if (headers.size in listOf(1, 3)) 415 else 200 }) { endpoint, _ ->
            val client = client(endpoint); negotiateGzip(client)
            val first = captured(client).toString()
            assertTrue(client.sendStoredPayload(first).isSuccess)
            negotiateGzip(client, "other-device")
            val second = JSONObject(first).apply { getJSONObject("device").put("deviceId", "other-device") }.toString()
            assertTrue(client.sendStoredPayload(second).isSuccess)
            negotiateGzip(client)
            assertTrue(client.sendStoredPayload(first).isSuccess)
            assertEquals(listOf("gzip", null, "gzip", null, null), headers.map { it["content-encoding"] })
        }
    }

    @Test fun `authorized identity fallback preserves evidence when its original gzip lease expires in flight`() {
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it, true) }, onRequestHeaders = { headers += it }, statusFor = {
            if (headers.size == 1) { elapsed += 300000; wall += 300000; 415 } else 200
        }) { endpoint, requests ->
            val client = client(endpoint); negotiateGzip(client)
            val original = captured(client).toString()
            assertTrue(client.sendStoredPayload(original).isSuccess)
            assertEquals(listOf("gzip", null), headers.map { it["content-encoding"] })
            assertEquals(requests[0].toString(), requests[1].toString())
            assertEquals(JSONObject(original).getJSONObject("sampling").getLong("sentAt"), requests[1].getJSONObject("sampling").getLong("sentAt"))
        }
    }

    @Test fun `encoding authorization lost after stream acquisition submits only original identity bytes`() {
        for (boundary in listOf("expiry", "clock", "replacement")) {
            val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
            val wires = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
            withServer({ durableAck(it) }, onRawRequest = { wires += it }, onRequestHeaders = { headers += it }) { endpoint, requests ->
                val client = client(endpoint); negotiateGzip(client)
                val original = captured(client).toString()
                var guards = 0
                client.bindGeneration {
                    guards++
                    if (guards == 3) when (boundary) {
                        "expiry" -> { elapsed += 300000; wall += 300000 }
                        "clock" -> wall += 120001
                        "replacement" -> negotiateGzip(client)
                    }
                    true
                }
                val attempts = mutableListOf<Int>()
                assertTrue(boundary, client.sendStoredPayload(original) { attempts += it }.isSuccess)
                assertEquals(1, requests.size)
                assertNull(headers.single()["content-encoding"])
                assertArrayEquals(original.toByteArray(Charsets.UTF_8), wires.single())
                assertEquals(wires.map { it.size }, attempts)
                assertEquals(JSONObject(original).getJSONObject("sampling").getJSONObject("clock").toString(),
                    requests.single().getJSONObject("sampling").getJSONObject("clock").toString())
            }
        }
    }

    @Test fun `policy removal after stream acquisition is applied before any body is submitted`() {
        var policy = DataCollectionPolicy.ALL_ENABLED
        val wires = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it) }, onRawRequest = { wires += it }, onRequestHeaders = { headers += it }) { endpoint, requests ->
            val client = client(endpoint) { policy }; negotiateGzip(client)
            val original = captured(client).put("location", JSONObject().put("fixture", true))
                .put("wifis", JSONArray().put(JSONObject().put("fixture", true))).toString()
            var guards = 0
            client.bindGeneration {
                guards++
                if (guards == 3) policy = DataCollectionPolicy(false, false, false)
                true
            }
            val attempts = mutableListOf<Int>()
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            assertEquals(1, requests.size)
            assertNull(headers.single()["content-encoding"])
            assertFalse(requests.single().has("location")); assertFalse(requests.single().has("wifis"))
            assertEquals(client.filterPayload(JSONObject(original)).toString(), requests.single().toString())
            assertEquals(wires.map { it.size }, attempts)
        }
    }

    @Test fun `only the exact reserved inertial tuple is independent of disabled GPS and WiFi`() {
        val motion = JSONObject().put("state", "moving_inferred").put("source", "context_fusion")
            .put("confidence", "low").put("reason", "context_changed").put("observedAtMs", wall - 1000).put("ageMs", 1000)
        val client = client(policy = { DataCollectionPolicy(location = false, wifi = false, motionSensors = true) })
        val original = captured(client).apply { getJSONObject("sampling").put("motion", motion) }
        assertEquals(motion.toString(), client.filterPayload(original).getJSONObject("sampling").getJSONObject("motion").toString())
        for ((key, value) in listOf("state" to "stationary_inferred", "confidence" to "medium", "reason" to "context_stable")) {
            val near = JSONObject(original.toString()); near.getJSONObject("sampling").getJSONObject("motion").put(key, value)
            assertEquals("unknown", client.filterPayload(near).getJSONObject("sampling").getJSONObject("motion").getString("state"))
        }
    }
    @Test fun `motion opt out filters native and inertial evidence without reading native availability or removing GPS evidence`() {
        var availabilityReads = 0
        val client = APIClient(config, "http://127.0.0.1:1", { DataCollectionPolicy(motionSensors = false) }, { availabilityReads++; true })
        for (source in listOf("native_activity", "context_fusion", "fresh_location")) {
            val original = captured(client).apply { getJSONObject("sampling").put("motion", JSONObject()
                .put("state", "moving_inferred").put("source", source).put("confidence", "low").put("reason", "context_changed")) }
            assertEquals(if (source == "fresh_location") "moving_inferred" else "unknown",
                client.filterPayload(original).getJSONObject("sampling").getJSONObject("motion").getString("state"))
        }
        assertEquals(0, availabilityReads)
    }
    @Test fun `historical gzip identity fallback removes revoked inertial evidence without changing original IDs clocks or queue body`() {
        var policy = DataCollectionPolicy(location = false, wifi = false, motionSensors = true)
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ durableAck(it, true) }, onRequestHeaders = { headers += it }, statusFor = {
            if (headers.size == 1) { policy = policy.copy(motionSensors = false); 415 } else 200
        }) { endpoint, requests ->
            val client = client(endpoint) { policy }; negotiateGzip(client)
            val original = captured(client).apply { getJSONObject("sampling").put("motion", JSONObject()
                .put("state", "moving_inferred").put("source", "context_fusion").put("confidence", "low").put("reason", "context_changed")
                .put("observedAtMs", wall - 1000).put("ageMs", 1000))
                getJSONObject("sampling").put("replay", JSONObject().put("fixture", true)) }.toString()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "fixture-inertial"; it.clearAllBatches() }
            val id = storage.saveRequestReturningId(original)!!
            val attempts = mutableListOf<Int>()
            assertTrue(client.sendStoredPayload(original) { attempts += it }.isSuccess)
            assertEquals(listOf("gzip", null), headers.map { it["content-encoding"] }); assertEquals(2, attempts.size)
            assertEquals("moving_inferred", requests[0].getJSONObject("sampling").getJSONObject("motion").getString("state"))
            assertEquals("unknown", requests[1].getJSONObject("sampling").getJSONObject("motion").getString("state"))
            for (key in listOf("batchId", "clock", "sentAt", "observedFrom", "observedTo", "replay"))
                assertEquals(requests[0].getJSONObject("sampling").get(key).toString(), requests[1].getJSONObject("sampling").get(key).toString())
            assertEquals(original, storage.loadAllRecords().single { it.id == id }.requestJson)
        }
    }

    @Test fun `WiFi visits submit captured legacy requests independently of adaptive backlog and ACKs`() {
        var attempts = 0
        withServer({ "{}" }, statusFor = { if (++attempts == 1) 503 else 200 }) { endpoint, requests ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            SecureStorage.initialize(context)
            File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
            val device = VisitTestFixtures.device().copy(timestamp = System.currentTimeMillis())
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
            val client = client(endpoint)
            val backlog = storage.saveRequestReturningId(captured(client).toString())!!
            val capturedAt = System.currentTimeMillis() - 60_000L
            val wifi = io.bearound.sdk.models.WifiObservation("fixture-arrival", "Fixture WiFi", -60, true, 2412, capturedAt)
            val visit = io.bearound.sdk.visit.VisitEvent(io.bearound.sdk.visit.VisitEventKind.ARRIVAL, null,
                "fixture-environment", listOf(wifi))
            val visitId = storage.saveVisitEvent(visit)!!
            val info = SDKInfo(appId = config.appId, build = 1)
            val drain = io.bearound.sdk.utilities.StoredBatchDrain(storage, BeAroundSDK.PERMANENT_HTTP_CODES,
                send = { beacons, capturedDevice, trigger ->
                    var result: Result<Unit> = Result.failure(IllegalStateException("Missing response"))
                    client.sendBeacons(beacons, info, capturedDevice, null, trigger) { result = it }
                    result
                })
            val previousPolicy = io.bearound.sdk.models.DataCollectionPolicyStore.current
            io.bearound.sdk.models.DataCollectionPolicyStore.apply(DataCollectionPolicy.ALL_ENABLED)
            try {
                suspend fun flush() = drain.drain(storage.loadAllRecords().filter { it.syncTrigger == "visit" },
                    device, DataCollectionPolicy.ALL_ENABLED)
                flush()
                assertEquals(setOf(backlog, visitId), storage.loadAllRecords().map { it.id }.toSet())
                val submitted = requests.single()
                assertEquals("visit", submitted.getString("syncTrigger"))
                assertFalse(submitted.has("sampling")); assertFalse(submitted.has("location"))
                assertEquals(0, submitted.getJSONArray("beacons").length())
                val submittedWifi = submitted.getJSONArray("wifis").getJSONObject(0)
                assertEquals(wifi.apId, submittedWifi.getString("apId"))
                assertEquals(wifi.ssid, submittedWifi.getString("ssid"))
                assertEquals(capturedAt, submittedWifi.getLong("timestamp"))
                assertEquals(wifi.apId, submitted.getJSONObject("device").getJSONObject("network").getString("apId"))
                flush()
                assertEquals(2, requests.size)
                assertEquals(submitted.toString(), requests.last().toString())
                assertEquals(listOf(backlog), storage.loadAllRecords().map { it.id })
            } finally {
                io.bearound.sdk.models.DataCollectionPolicyStore.apply(previousPolicy)
            }
        }

    }

    @Test fun `SDK contextless v1 retry collects current fallback without capability and preserves time tenant and privacy`() = withServer({ "{}" }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecureStorage.initialize(context)
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val fix = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 20.0; longitude = 30.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        @Suppress("DEPRECATION")
        org.robolectric.Shadows.shadowOf(manager).apply {
            setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
            setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, fix)
        }
        val previousPolicy = io.bearound.sdk.models.DataCollectionPolicyStore.current
        try {
            for (adaptive in listOf(false, true)) {
                File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
                val configuration = config.copy(adaptiveSamplingEnabled = adaptive, collectAdvertisingId = false,
                    collectLocation = true, collectWifi = false, collectMotionSensors = false)
                var policy = configuration.dataCollectionPolicy
                io.bearound.sdk.models.DataCollectionPolicyStore.apply(policy)
                val client = APIClient(configuration, endpoint, { policy })
                val storage = OfflineBatchStorage(context).also { it.currentTenantId = "foreign-tenant" }
                val at = System.currentTimeMillis() - 60_000L
                val beacon = Beacon(UUID.randomUUID(), 0, 188, -61, Beacon.Proximity.NEAR, 1.0, Date(at))
                val foreignId = storage.saveBatchReturningId(listOf(beacon.copy(minor = 189)))!!
                storage.currentTenantId = "test-tenant"
                val cached = VisitTestFixtures.device().copy(location = io.bearound.sdk.models.DeviceLocation(
                    1.0, 2.0, 5f, timestamp = at - 60_000L, source = "gps"), advertisingId = "stale-ad")
                val sdk = sdkForStoredDrain(context, configuration, client, storage, cached,
                    UserProperties(internalId = "retry-user"), currentSources = true)
                assertNull(client.samplingCapability("device-1"))
                assertNotNull(storage.saveBatchReturningId(listOf(beacon)))
                val before = requests.size

                assertEquals(true, drainSdkStoredBatches(sdk))

                val submitted = requests.last()
                assertEquals(before + 1, requests.size)
                assertEquals(20.0, submitted.getJSONObject("location").getDouble("latitude"), 0.0)
                assertEquals(fix.time, submitted.getJSONObject("location").getLong("timestamp"))
                assertEquals(at, submitted.getJSONArray("beacons").getJSONObject(0).getLong("timestamp"))
                assertEquals(at, submitted.getJSONObject("device").getLong("timestamp"))
                assertEquals("retry-user", submitted.getJSONObject("userProperties").getString("internalId"))
                assertFalse(submitted.has("sampling"))
                assertFalse(submitted.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
                assertTrue(storage.loadAllRecords().isEmpty())

                policy = DataCollectionPolicy(false, false, false, false)
                io.bearound.sdk.models.DataCollectionPolicyStore.apply(policy)
                assertNotNull(storage.saveBatchReturningId(listOf(beacon)))
                assertEquals(true, drainSdkStoredBatches(sdk))
                assertFalse(requests.last().has("location")); assertFalse(requests.last().has("wifis"))
                assertEquals(at, requests.last().getJSONArray("beacons").getJSONObject(0).getLong("timestamp"))
                storage.currentTenantId = "foreign-tenant"
                assertEquals(foreignId, storage.loadAllRecords().single().id)
            }
        } finally {
            io.bearound.sdk.models.DataCollectionPolicyStore.apply(previousPolicy)
        }
    }

    @Test fun `SDK captured v2 retry preserves its envelope and privacy without collecting replacement context`() {
        var attempt = 0
        withServer({ "{}" }, statusFor = { if (++attempt == 1) 503 else 200 }) { endpoint, requests ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            SecureStorage.initialize(context)
            File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
            val previousPolicy = io.bearound.sdk.models.DataCollectionPolicyStore.current
            var policy = DataCollectionPolicy.ALL_ENABLED
            io.bearound.sdk.models.DataCollectionPolicyStore.apply(policy)
            try {
                val configuration = config.copy(adaptiveSamplingEnabled = false)
                val client = APIClient(configuration, endpoint, { policy })
                val storage = OfflineBatchStorage(context).also { it.currentTenantId = "foreign-tenant" }
                val at = System.currentTimeMillis() - 60_000L
                val beacon = Beacon(UUID.randomUUID(), 0, 188, -61, Beacon.Proximity.NEAR, 1.0, Date(at))
                val wifi = io.bearound.sdk.models.WifiObservation("captured-ap", "Fixture", -61, true, 2412, at)
                val originalDevice = VisitTestFixtures.device().copy(timestamp = at, advertisingId = "original-ad",
                    location = io.bearound.sdk.models.DeviceLocation(1.0, 2.0, 5f, timestamp = at, source = "gps"),
                    wifis = listOf(wifi), encounterIds = listOf("original-encounter"))
                val original = client.buildPayload(listOf(beacon), SDKInfo(appId = configuration.appId, build = 1),
                    originalDevice, UserProperties(internalId = "original-user")).toString()
                val foreignId = storage.saveRequestReturningId(original)!!
                storage.currentTenantId = "test-tenant"
                val id = storage.saveRequestReturningId(original)!!
                val sdk = sdkForStoredDrain(context, configuration, client, storage,
                    originalDevice.copy(timestamp = at + 60_000L, advertisingId = "replacement-ad",
                        location = originalDevice.location!!.copy(latitude = 20.0)), UserProperties(internalId = "replacement-user"))

                assertEquals(false, drainSdkStoredBatches(sdk))

                assertEquals(JSONObject(original).toString(), requests.single().toString())
                assertEquals(original, storage.loadAllRecords().single().requestJson)
                policy = DataCollectionPolicy(false, false, false, false)
                io.bearound.sdk.models.DataCollectionPolicyStore.apply(policy)
                assertEquals(true, drainSdkStoredBatches(sdk))
                val replay = requests.last()
                assertEquals(2, requests.size)
                assertFalse(replay.has("location")); assertFalse(replay.has("wifis"))
                assertFalse(replay.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
                assertEquals("original-user", replay.getJSONObject("userProperties").getString("internalId"))
                assertEquals("original-encounter", replay.getJSONArray("encounterIds").getString(0))
                assertEquals(at, replay.getJSONObject("device").getLong("timestamp"))
                assertEquals(at, replay.getJSONArray("beacons").getJSONObject(0).getLong("timestamp"))
                assertTrue(storage.loadAllRecords().none { it.id == id })
                storage.currentTenantId = "foreign-tenant"
                assertEquals(foreignId, storage.loadAllRecords().single().id)
                assertEquals(original, storage.loadAllRecords().single().requestJson)
            } finally {
                io.bearound.sdk.models.DataCollectionPolicyStore.apply(previousPolicy)
            }
        }
    }

    @Test fun `legacy sixty second offline payloads and known tenant upgrade records retry without capability`() = withServer({ "{}" }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (adaptive in listOf(false, true)) {
            File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
            val configuration = config.copy(adaptiveSamplingEnabled = adaptive)
            val client = APIClient(configuration, endpoint, { DataCollectionPolicy.ALL_ENABLED })
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
            val at = System.currentTimeMillis() - 60_000L
            val beacon = Beacon(UUID.randomUUID(), 0, 188, -61, Beacon.Proximity.NEAR, 1.0, Date(at))
            val device = VisitTestFixtures.device().copy(deviceId = "device-1", timestamp = at)
            val info = SDKInfo(appId = config.appId, build = 1)
            val properties = UserProperties(internalId = "captured-user")
            val original = client.buildPayload(listOf(beacon), info, device, properties).toString()
            storage.saveRequestReturningId(original)!!
            storage.saveBatchReturningId(listOf(beacon), null, OfflineBatchStorage.CapturedContext(device.location, device.wifis))!!
            val restored = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
            val drain = io.bearound.sdk.utilities.StoredBatchDrain(restored, BeAroundSDK.PERMANENT_HTTP_CODES,
                send = { beacons, capturedDevice, trigger ->
                    var result: Result<Unit> = Result.failure(IllegalStateException("Missing response"))
                    client.sendBeacons(beacons, info, capturedDevice, properties, trigger) { result = it }; result
                }, sendCaptured = { record, _ -> client.sendStoredPayload(record.requestJson!!).map { Unit } })
            val count = requests.size
            assertNull(client.samplingCapability("device-1"))
            assertTrue(drain.drain(restored.loadAllRecords(), device.copy(timestamp = at + 60_000), DataCollectionPolicy.ALL_ENABLED))
            assertTrue(restored.loadAllRecords().isEmpty())
            assertEquals(2, requests.size - count)
            requests.drop(count).forEach {
                assertFalse(it.has("sampling"))
                assertEquals(at, it.getJSONArray("beacons").getJSONObject(0).getLong("timestamp"))
                assertEquals(at, it.getJSONObject("device").getLong("timestamp"))
                assertEquals("captured-user", it.getJSONObject("userProperties").getString("internalId"))
            }
            assertEquals(JSONObject(original).toString(), requests[count].toString())
        }
    }

    @Test fun `captured visit retry retains its identity and encounters while enforcing current privacy`() = withServer({ "{}" }, statusFor = { if (it.optBoolean("fixtureFirst")) 503 else 200 }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        var policy = DataCollectionPolicy.ALL_ENABLED
        val client = APIClient(config, endpoint, { policy })
        val at = System.currentTimeMillis() - 60_000L
        val wifi = io.bearound.sdk.models.WifiObservation("arrival", "Fixture", -61, true, 2412, at)
        val visit = io.bearound.sdk.visit.VisitEvent(io.bearound.sdk.visit.VisitEventKind.ARRIVAL, null, "fixture", listOf(wifi))
        val device = VisitTestFixtures.device().copy(timestamp = at, advertisingId = "captured-ad", limitAdTracking = false,
            encounterIds = listOf("captured-rotating-id"), encounters = listOf(io.bearound.sdk.models.EncounterObservation(
                "0123456789abcdef0123456789abcdef", -60, 2, -61, -59, -60, at - 1000, at)))
        val info = SDKInfo(appId = config.appId, build = 1)
        var properties = UserProperties(internalId = "visit-user")
        val queue = io.bearound.sdk.visit.OfflineBatchVisitEventQueue(context, storage, capturePayload = {
            client.buildVisitPayload(it, info, device, properties).toString()
        }, drain = {})
        assertTrue(queue.persist(visit))
        val captured = storage.loadAllRecords().single().requestJson!!
        assertEquals("captured-ad", JSONObject(captured).getJSONObject("device").getJSONObject("permissions").getString("advertisingId"))
        assertTrue(client.sendStoredPayload(JSONObject(captured).put("fixtureFirst", true).toString()).isFailure)
        properties = UserProperties(internalId = "later-user")
        policy = DataCollectionPolicy(advertisingId = false, location = false, wifi = false, motionSensors = false)
        val reopened = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        val drain = io.bearound.sdk.utilities.StoredBatchDrain(reopened, BeAroundSDK.PERMANENT_HTTP_CODES,
            send = { _, _, _ -> error("Visit must use captured payload") },
            sendCaptured = { record, _ -> client.sendStoredPayload(record.requestJson!!).map { Unit } })
        assertTrue(drain.drain(reopened.loadAllRecords(), device, policy))
        val submitted = requests.last()
        assertEquals("visit", submitted.getString("syncTrigger")); assertFalse(submitted.has("sampling"))
        assertEquals("visit-user", submitted.getJSONObject("userProperties").getString("internalId"))
        assertFalse(submitted.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
        assertFalse(submitted.has("wifis")); assertFalse(submitted.has("location"))
        assertEquals(at, submitted.getJSONObject("device").getLong("timestamp"))
        assertEquals("captured-rotating-id", submitted.getJSONArray("encounterIds").getString(0))
        assertEquals(at, submitted.getJSONArray("encounters").getJSONObject(0).getLong("lastSeen"))
        assertTrue(reopened.loadAllRecords().isEmpty())
        assertTrue(JSONObject(captured).has("wifis"))
    }

    @Test fun `over budget replay retains original and lease while later observations make progress`() = withServer({ durableAck(it) }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        val client = client(endpoint)
        client.observeResponse(response(capability().put("maxPayloadBytes", 6000)), "device-1", wall, elapsed)
        val oversized = captured(client).put("fixturePadding", "x".repeat(10_000)).toString()
        val first = storage.saveRequestReturningId(oversized)!!
        val second = storage.saveRequestReturningId(captured(client).toString())!!
        val drain = io.bearound.sdk.utilities.StoredBatchDrain(storage, BeAroundSDK.PERMANENT_HTTP_CODES,
            send = { _, _, _ -> error("Expected captured requests") }, sendCaptured = { record, manifest ->
                val original = JSONObject(record.requestJson!!)
                manifest?.let { original.getJSONObject("sampling").put("replay", it) }
                client.sendStoredPayload(original.toString()).map { Unit }
            })
        assertFalse(drain.drain(storage.loadAllRecords(), VisitTestFixtures.device(), DataCollectionPolicy.ALL_ENABLED))
        assertEquals(1, requests.size)
        assertEquals(second, requests.single().getJSONObject("sampling").getString("batchId"))
        assertEquals(first, storage.loadAllRecords().single().id)
        assertEquals(oversized, storage.loadAllRecords().single().requestJson)
        assertNotNull(client.samplingCapability("device-1"))
        assertFalse(drain.drain(storage.loadAllRecords(), VisitTestFixtures.device(), DataCollectionPolicy.ALL_ENABLED))
        assertEquals(1, requests.size)
    }

    @Test fun `version one retries retain main identifier fallback and captured context with current privacy`() = withServer({ "{}" }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        val at = System.currentTimeMillis() - 60_000L
        val beacon = Beacon(UUID.randomUUID(), 0, 188, -61, Beacon.Proximity.NEAR, 1.0, Date(at))
        val location = io.bearound.sdk.models.DeviceLocation(1.0, 2.0, timestamp = at, source = "gnss")
        val device = VisitTestFixtures.device().copy(timestamp = at + 60_000L, advertisingId = "legacy-fallback-ad",
            encounterIds = listOf("legacy-fallback-rpi"))
        var policy = DataCollectionPolicy.ALL_ENABLED
        val client = APIClient(config, endpoint, { policy })
        val info = SDKInfo(appId = config.appId, build = 1)
        val drain = io.bearound.sdk.utilities.StoredBatchDrain(storage, BeAroundSDK.PERMANENT_HTTP_CODES,
            send = { samples, capturedDevice, trigger ->
                var result: Result<Unit> = Result.failure(IllegalStateException("Missing response"))
                client.sendBeacons(samples, info, capturedDevice, UserProperties(internalId = "legacy-main-user"), trigger) { result = it }
                result
            })
        suspend fun submit() {
            storage.saveBatchReturningId(listOf(beacon), null, OfflineBatchStorage.CapturedContext(location, emptyList()))!!
            assertTrue(drain.drain(storage.loadAllRecords(), device, policy))
        }
        submit()
        assertEquals(at, requests.last().getJSONObject("location").getLong("timestamp"))
        assertEquals("legacy-fallback-ad", requests.last().getJSONObject("device").getJSONObject("permissions").getString("advertisingId"))
        policy = DataCollectionPolicy(advertisingId = false, location = false, wifi = false, motionSensors = false)
        submit()
        val revoked = requests.last()
        assertFalse(revoked.has("location")); assertFalse(revoked.has("wifis"))
        assertFalse(revoked.getJSONObject("device").getJSONObject("permissions").has("advertisingId"))
        assertEquals("legacy-main-user", revoked.getJSONObject("userProperties").getString("internalId"))
        assertEquals("legacy-fallback-rpi", revoked.getJSONArray("encounterIds").getString(0))
        assertEquals(at, revoked.getJSONObject("device").getLong("timestamp"))
    }

    @Test fun `large legacy retry keeps main identity transport despite a smaller adaptive gzip lease`() {
        val headers = mutableListOf<Map<String, String>>()
        withServer({ "{}" }, onRequestHeaders = { headers += it }) { endpoint, requests ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
            val client = client(endpoint)
            client.observeResponse(response(capability().put("maxPayloadBytes", 4000)
                .put("requestContentEncodings", JSONArray(listOf("gzip")))), "device-1", wall, elapsed)
            val original = client.buildPayload(emptyList(), SDKInfo(appId = config.appId, build = 1),
                VisitTestFixtures.device().copy(deviceId = "device-1"),
                UserProperties(internalId = "legacy-user", customProperties = mapOf("fixturePadding" to "x".repeat(600_000)))).toString()
            assertTrue(original.toByteArray(Charsets.UTF_8).size > 524_288)
            assertTrue(original.toByteArray(Charsets.UTF_8).size < 1_048_576)
            val id = storage.saveRequestReturningId(original)!!
            assertTrue(client.sendStoredPayload(storage.loadAllRecords().single().requestJson!!).isSuccess)
            storage.removeBatch(id)
            assertEquals(JSONObject(original).toString(), requests.single().toString())
            assertFalse(headers.single().containsKey("content-encoding"))
            assertNotNull(client.samplingCapability("device-1"))
            assertTrue(storage.loadAllRecords().isEmpty())
        }
    }

    @Test fun `legacy stop then immediate restart retains failed disk snapshots until one attributed retry`() = withServer({ "{}" }) { endpoint, requests ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (adaptive in listOf(false, true)) {
            val directory = File(context.filesDir.parentFile, "app_com.bearound.sdk.batches")
            directory.deleteRecursively()
            val parked = File(directory.parentFile, "parked-stop-capture")
            parked.deleteRecursively()
            val storage = OfflineBatchStorage(context).also { it.currentTenantId = "original-tenant" }
            val configuration = config.copy(adaptiveSamplingEnabled = adaptive)
            val client = APIClient(configuration, endpoint, { DataCollectionPolicy.ALL_ENABLED })
            val info = SDKInfo(appId = config.appId, build = 1)
            val at = System.currentTimeMillis() - 60_000L
            val oldDevice = VisitTestFixtures.device().copy(timestamp = at, advertisingId = "original-ad")
            var properties = UserProperties(internalId = "original-user")
            var generation = 7L
            val frames = mutableListOf<Beacon>()
            val retained = mutableListOf<io.bearound.sdk.LegacyStopSnapshot>()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val restarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val queue = PhysicalCaptureQueue(scope, capture = { beacon, _, admittedGeneration ->
                if (beacon.minor == 1) gate.await()
                assertEquals(7L, admittedGeneration); assertEquals(7L, generation)
                frames += beacon
            }, overflow = {}, failed = { throw it })
            try {
                val uuid = UUID.randomUUID()
                repeat(2) { index ->
                    assertTrue(queue.offer(Beacon(uuid, 0, index + 1, -60, Beacon.Proximity.NEAR, 1.0, Date(at)), 100L + index, generation) { true })
                }
                queue.closeAdmission {}
                assertTrue(directory.renameTo(parked))
                directory.writeText("Unavailable fixture directory")
                queue.flush {
                    val snapshot = BeAroundSDK.prepareLegacyStopSnapshot(adaptive, false, "original-tenant",
                        client, info, frames.toList(), oldDevice, properties)!!
                    assertFalse(snapshot.persist(storage))
                    retained += snapshot
                }
                assertTrue(queue.deferStart {
                    generation++
                    frames.clear()
                    properties = UserProperties(internalId = "later-user")
                    storage.currentTenantId = "different-tenant"
                    queue.resume()
                    restarted.complete(Unit)
                })
                gate.complete(Unit)
                restarted.await()
                assertEquals(8L, generation); assertTrue(frames.isEmpty()); assertEquals(1, retained.size)
                directory.delete(); assertTrue(parked.renameTo(directory))
                assertFalse(retained.single().persist(storage))
                assertTrue(storage.loadAllRecords().isEmpty())
                storage.currentTenantId = "original-tenant"
                assertTrue(retained.removeAll { it.persist(storage) })
                assertTrue(retained.isEmpty()); assertEquals(1, storage.loadAllRecords().size)
                val count = requests.size
                val drain = io.bearound.sdk.utilities.StoredBatchDrain(storage, BeAroundSDK.PERMANENT_HTTP_CODES,
                    send = { _, _, _ -> error("Expected frozen stop request") },
                    sendCaptured = { record, _ -> client.sendStoredPayload(record.requestJson!!).map { Unit } })
                assertTrue(drain.drain(storage.loadAllRecords(), oldDevice.copy(timestamp = at + 60_000L), DataCollectionPolicy.ALL_ENABLED))
                assertTrue(drain.drain(storage.loadAllRecords(), oldDevice, DataCollectionPolicy.ALL_ENABLED))
                assertEquals(count + 1, requests.size)
                val submitted = requests.last()
                assertFalse(submitted.has("sampling")); assertEquals(2, submitted.getJSONArray("beacons").length())
                assertEquals(at, submitted.getJSONObject("device").getLong("timestamp"))
                assertEquals(at, submitted.getJSONArray("beacons").getJSONObject(0).getLong("timestamp"))
                assertEquals("original-user", submitted.getJSONObject("userProperties").getString("internalId"))
                assertEquals("original-ad", submitted.getJSONObject("device").getJSONObject("permissions").getString("advertisingId"))
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                if (parked.exists()) { directory.deleteRecursively(); parked.renameTo(directory) }
            }
        }
    }

}

package io.bearound.sdk.apppresence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.AppPresenceConfiguration
import io.bearound.sdk.models.AppPresenceConfigurationException
import io.bearound.sdk.models.AppPresenceDeclarations
import io.bearound.sdk.models.AppPresenceDetectionMethod
import io.bearound.sdk.models.AppPresenceReason
import io.bearound.sdk.models.AppPresenceResult
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceState
import io.bearound.sdk.models.AppPresenceTarget
import io.bearound.sdk.models.AppPresenceTime
import io.bearound.sdk.models.AppPresenceValidator
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPresenceModelsTest {

    private lateinit var context: Context

    private val fixture = AppPresenceTarget(
        id = "fixture",
        iosScheme = "bearound-presence-fixture",
        androidPackageName = "io.bearound.fixture.presence"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKConfigStorage.clearConfiguration(context)
    }

    // region defaults and persistence

    @Test
    fun `defaults are disabled with no targets`() {
        val config = AppPresenceConfiguration()
        assertFalse(config.enabled)
        assertTrue(config.targets.isEmpty())

        val sdk = SDKConfiguration(businessToken = "token", appId = "io.test")
        assertEquals(AppPresenceConfiguration(), sdk.appPresence)
        // Existing defaults are preserved.
        assertTrue(sdk.collectAdvertisingId)
        assertTrue(sdk.collectLocation)
        assertTrue(sdk.collectWifi)
    }

    @Test
    fun `legacy stored config restores app presence disabled`() {
        SDKConfigStorage.saveConfiguration(context, SDKConfiguration(businessToken = "legacy", appId = "io.test"))
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .remove("app_presence_configuration")
            .commit()

        val loaded = SDKConfigStorage.loadConfiguration(context)
        assertNotNull(loaded)
        assertFalse(loaded!!.appPresence.enabled)
        assertTrue(loaded.appPresence.targets.isEmpty())
        assertTrue(loaded.collectAdvertisingId && loaded.collectLocation && loaded.collectWifi)
        assertEquals(AppPresenceConfiguration.DISABLED, SDKConfigStorage.loadAppPresenceConfiguration(context))
    }

    @Test
    fun `app presence survives a save-load roundtrip with normalized scheme`() {
        val config = AppPresenceConfiguration(
            enabled = true,
            targets = listOf(fixture.copy(iosScheme = "BeAround-Presence-Fixture"), AppPresenceTarget(id = "ios.only", iosScheme = "x-app"))
        )
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "token", appId = "io.test", appPresence = config)
        )

        val loaded = SDKConfigStorage.loadConfiguration(context)!!.appPresence
        assertTrue(loaded.enabled)
        assertEquals(listOf("fixture", "ios.only"), loaded.targets.map { it.id })
        assertEquals("bearound-presence-fixture", loaded.targets[0].iosScheme)
        assertNull(loaded.targets[1].androidPackageName)
    }

    @Test
    fun `corrupted or invalid stored app presence fails closed`() {
        SDKConfigStorage.saveAppPresenceConfiguration(
            context,
            AppPresenceConfiguration(enabled = true, targets = listOf(fixture, fixture))
        )
        assertEquals(AppPresenceConfiguration.DISABLED, SDKConfigStorage.loadAppPresenceConfiguration(context))

        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .putString("app_presence_configuration", "{not json")
            .commit()
        assertEquals(AppPresenceConfiguration.DISABLED, SDKConfigStorage.loadAppPresenceConfiguration(context))
    }

    // endregion

    // region validation

    @Test
    fun `valid configuration has no error, including targets without a platform identifier`() {
        val config = AppPresenceConfiguration(
            enabled = true,
            targets = listOf(fixture, AppPresenceTarget(id = "A-b_c.9"), AppPresenceTarget(id = "x".repeat(64)))
        )
        assertNull(config.validationError())
    }

    @Test
    fun `invalid ids are rejected`() {
        listOf("", "x".repeat(65), "with space", "acentuação", "a/b").forEach { id ->
            assertInvalid(AppPresenceConfiguration(true, listOf(AppPresenceTarget(id = id))))
        }
    }

    @Test
    fun `duplicated ids are rejected but duplicated identifiers are allowed`() {
        assertInvalid(AppPresenceConfiguration(true, listOf(fixture, fixture.copy(iosScheme = "other"))))
        assertNull(AppPresenceConfiguration(true, listOf(fixture, fixture.copy(id = "fixture2"))).validationError())
    }

    @Test
    fun `more than 50 targets are rejected`() {
        val fifty = (1..50).map { AppPresenceTarget(id = "t$it") }
        assertNull(AppPresenceConfiguration(true, fifty).validationError())
        assertInvalid(AppPresenceConfiguration(true, fifty + AppPresenceTarget(id = "t51")))
    }

    @Test
    fun `malformed ios schemes are rejected`() {
        listOf("", "http", "HTTPS", "app://", "app:", "1app", "my app", "app/path", "app?q=1").forEach { scheme ->
            assertInvalid(AppPresenceConfiguration(true, listOf(AppPresenceTarget(id = "t", iosScheme = scheme))))
        }
        listOf("fb", "x-app+v2.beta", "BeAround").forEach { scheme ->
            assertTrue(scheme, AppPresenceValidator.isValidIosScheme(scheme))
        }
    }

    @Test
    fun `malformed android package names are rejected`() {
        listOf("", "single", "com.*", "com..app", "com.1app", ".com.app", "com.app.", "com.my app").forEach { name ->
            assertInvalid(AppPresenceConfiguration(true, listOf(AppPresenceTarget(id = "t", androidPackageName = name))))
        }
        assertTrue(AppPresenceValidator.isValidAndroidPackageName("io.bearound.fixture.presence"))
        assertTrue(AppPresenceValidator.isValidAndroidPackageName("com.app_x.Y2"))
    }

    @Test
    fun `fingerprint ignores order, scheme case and enabled, but not identifiers`() {
        val a = AppPresenceTarget(id = "a", androidPackageName = "com.a.app")
        val b = AppPresenceTarget(id = "b", iosScheme = "bapp")
        val base = AppPresenceValidator.fingerprint(listOf(a, b))

        assertEquals(64, base.length)
        assertEquals(base, AppPresenceValidator.fingerprint(listOf(b.copy(iosScheme = "BAPP"), a)))
        assertNotEquals(base, AppPresenceValidator.fingerprint(listOf(a, b.copy(iosScheme = "capp"))))
        assertNotEquals(base, AppPresenceValidator.fingerprint(listOf(a)))
    }

    @Test
    fun `configuration JSON with a wrong type is rejected with the feature error code`() {
        listOf(
            "{not json",
            """{"enabled":"yes"}""",
            """{"targets":{}}""",
            """{"targets":[{"iosScheme":"x"}]}""",
            """{"targets":[{"id":"t","androidPackageName":5}]}"""
        ).forEach { raw ->
            try {
                AppPresenceConfiguration.fromJson(raw)
                fail("expected rejection of $raw")
            } catch (e: AppPresenceConfigurationException) {
                assertEquals("app_presence_invalid_configuration", e.code)
            }
        }
        assertEquals(AppPresenceConfiguration.DISABLED, AppPresenceConfiguration.fromJson("{}"))
    }

    // endregion

    // region JSON and explicit null

    @Test
    fun `unknown result serializes present and reason as explicit null and parses back as null`() {
        val known = AppPresenceResult(
            targetId = "fixture",
            state = AppPresenceState.ABSENT,
            present = false,
            reason = null,
            checkedAt = AppPresenceTime.format(0L),
            detectionMethod = AppPresenceDetectionMethod.ANDROID_PACKAGE
        )
        val unknown = AppPresenceResult(
            targetId = "ios.only",
            state = AppPresenceState.UNKNOWN,
            present = null,
            reason = AppPresenceReason.UNSUPPORTED_PLATFORM,
            checkedAt = AppPresenceTime.format(1_000L),
            detectionMethod = AppPresenceDetectionMethod.NONE
        )

        val knownJson = known.toJson()
        assertTrue(knownJson.has("reason"))
        assertEquals(JSONObject.NULL, knownJson.get("reason"))
        assertEquals(false, knownJson.get("present"))

        val unknownJson = unknown.toJson()
        assertTrue(unknownJson.has("present"))
        assertEquals(JSONObject.NULL, unknownJson.get("present"))
        assertEquals("unsupported_platform", unknownJson.getString("reason"))
        assertEquals("none", unknownJson.getString("detectionMethod"))
        assertEquals("unknown", unknownJson.getString("state"))
        assertTrue(unknownJson.toString().contains("\"present\":null"))

        val snapshot = AppPresenceSnapshot(
            snapshotId = "7f1d3c5e-2b1a-4c1e-9b7a-0f6e5d4c3b2a",
            configurationFingerprint = AppPresenceValidator.fingerprint(listOf(fixture)),
            checkedAt = AppPresenceTime.format(2_000L),
            cached = false,
            results = listOf(known, unknown)
        )
        val parsed = AppPresenceSnapshot.fromJson(JSONObject(snapshot.toJson().toString()))
        assertEquals(snapshot, parsed)
        assertNull(parsed.results[1].present)
        assertEquals(1, parsed.schemaVersion)
    }

    @Test
    fun `checkedAt is ISO 8601 UTC with milliseconds`() {
        assertEquals("1970-01-01T00:00:01.234Z", AppPresenceTime.format(1_234L))
    }

    @Test
    fun `target JSON writes absent platform identifiers as explicit null`() {
        val json = AppPresenceConfiguration(true, listOf(AppPresenceTarget(id = "t"))).toJson()
        val target = json.getJSONArray("targets").getJSONObject(0)
        assertEquals(JSONObject.NULL, target.get("iosScheme"))
        assertEquals(JSONObject.NULL, target.get("androidPackageName"))
        assertEquals(AppPresenceConfiguration(true, listOf(AppPresenceTarget(id = "t"))), AppPresenceConfiguration.fromJson(json.toString()))
    }

    // endregion

    // region declaration asset

    @Test
    fun `declaration asset is accepted only when schema, applicationId and packages are valid`() {
        val appId = "io.bearound.host"
        val valid = """{"schemaVersion":1,"applicationId":"$appId","packages":["com.a.app","io.bearound.fixture.presence"]}"""

        val parsed = AppPresenceDeclarations.parse(valid, appId)
        assertNotNull(parsed)
        assertTrue(parsed!!.isDeclared("io.bearound.fixture.presence"))
        assertFalse(parsed.isDeclared("com.other.app"))

        assertNull(AppPresenceDeclarations.parse(null, appId))
        assertNull(AppPresenceDeclarations.parse("{broken", appId))
        assertNull(AppPresenceDeclarations.parse(valid, "io.bearound.other"))
        assertNull(AppPresenceDeclarations.parse(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), appId))
        assertNull(AppPresenceDeclarations.parse("""{"schemaVersion":1,"applicationId":"$appId"}""", appId))
        assertNull(AppPresenceDeclarations.parse("""{"schemaVersion":1,"applicationId":"$appId","packages":["z.app","a.app"]}""", appId))
        assertNull(AppPresenceDeclarations.parse("""{"schemaVersion":1,"applicationId":"$appId","packages":["a.app","a.app"]}""", appId))
        assertNull(AppPresenceDeclarations.parse("""{"schemaVersion":1,"applicationId":"$appId","packages":["com.*"]}""", appId))
        assertNotNull(AppPresenceDeclarations.parse("""{"schemaVersion":1,"applicationId":"$appId","packages":[]}""", appId))
    }

    // endregion

    private fun assertInvalid(config: AppPresenceConfiguration) {
        val error = config.validationError()
        assertNotNull("expected invalid: $config", error)
        assertEquals("app_presence_invalid_configuration", error!!.code)
    }
}

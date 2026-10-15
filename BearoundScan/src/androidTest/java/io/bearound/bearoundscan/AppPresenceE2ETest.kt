package io.bearound.bearoundscan

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.AppPresenceConfiguration
import io.bearound.sdk.models.AppPresenceConfigurationException
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceTarget
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.utilities.AppPresenceStorageException
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One step of the app presence device E2E (e2e/app-presence/run.sh), run in this app's real
 * process against the real PackageManager and the production 3600 s interval.
 *
 * This class is a driver, not a self-contained test: every `am instrument` invocation is a
 * fresh process (a restart), and the expectations of each step depend on the steps before it
 * and on the real time elapsed, so the harness asserts them (tests/test_device_results.py)
 * from the records logged here.
 *
 * Instrumentation arguments:
 * - `client`: client label; the business token is derived from it, so each label is its own
 *   cooldown namespace (A/B/A isolation).
 * - `action`: `run` (enable the targets and start scanning) or `optout` (enabled = false).
 * - `waitMs`: how long to collect callbacks before stopping (default 15000).
 *
 * Every record is one logcat line: `BEAROUND_E2E {json}`. Bluetooth and location permissions
 * are never granted and the device is offline during the run: the round must not depend on
 * either.
 */
@RunWith(AndroidJUnit4::class)
class AppPresenceE2ETest {

    @Test
    fun appPresenceStep() {
        val args = InstrumentationRegistry.getArguments()
        val client = args.getString("client") ?: "A"
        val action = args.getString("action") ?: "run"
        val waitMs = args.getString("waitMs")?.toLongOrNull() ?: 15_000L
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        fun emit(event: String, snapshot: AppPresenceSnapshot? = null, extra: JSONObject? = null) {
            val record = JSONObject()
                .put("host", "native")
                .put("client", client)
                .put("action", action)
                .put("event", event)
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("snapshot", snapshot?.toJson() ?: JSONObject.NULL)
            extra?.keys()?.forEach { record.put(it, extra.get(it)) }
            Log.i(TAG, "$MARKER $record")
        }

        val listener = object : BeAroundSDKListener {
            override fun onBeaconsUpdated(beacons: List<Beacon>) {}

            override fun onAppPresenceUpdated(snapshot: AppPresenceSnapshot) {
                emit("listener", snapshot)
            }

            override fun onError(error: Exception) {
                val code = when (error) {
                    is AppPresenceConfigurationException -> error.code
                    is AppPresenceStorageException -> "app_presence_storage_unavailable"
                    else -> error.javaClass.simpleName
                }
                emit("error", extra = JSONObject().put("code", code))
            }
        }

        lateinit var sdk: BeAroundSDK
        instrumentation.runOnMainSync {
            sdk = BeAroundSDK.getInstance(context)
            sdk.configure(
                businessToken = "e2e-native-client-$client",
                collectAdvertisingId = false,
                collectLocation = false,
                collectWifi = false
            )
            sdk.configureAppPresence(
                AppPresenceConfiguration(enabled = action != "optout", targets = TARGETS)
            )
            emit("getter_start", sdk.getLastAppPresenceSnapshot())
            sdk.listener = listener
            sdk.startScanning()
            emit("started")
        }

        Thread.sleep(waitMs)

        instrumentation.runOnMainSync {
            emit("getter_end", sdk.getLastAppPresenceSnapshot())
            // Disables the SDK's own background mechanisms too, so nothing runs a round
            // between steps outside the harness's control.
            sdk.stopScanning()
            emit("done")
        }
    }

    private companion object {
        const val TAG = "BeAroundAppPresenceE2E"
        const val MARKER = "BEAROUND_E2E"

        /** Same targets in every host of the E2E (native, Flutter, React Native). */
        val TARGETS = listOf(
            AppPresenceTarget(
                id = "fixture",
                iosScheme = "bearound-presence-fixture",
                androidPackageName = "io.bearound.fixture.presence"
            ),
            AppPresenceTarget(
                id = "undeclared",
                iosScheme = "bearound-presence-undeclared",
                androidPackageName = "io.bearound.fixture.undeclared"
            ),
            AppPresenceTarget(id = "ios-only", iosScheme = "bearound-presence-ios-only")
        )
    }
}

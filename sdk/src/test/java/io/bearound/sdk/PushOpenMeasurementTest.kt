package io.bearound.sdk

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.background.BackgroundScheduler
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.push.PushEventQueue
import io.bearound.sdk.push.PushEventVerb
import io.bearound.sdk.push.PushHitOutcome
import io.bearound.sdk.push.PushEventTransport
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Push receipt + open (tap) measurement through the ads--tracker (REQ-022, REQ-023, REQ-024).
 */
@RunWith(RobolectricTestRunner::class)
class PushOpenMeasurementTest {

    private lateinit var context: android.content.Context
    private lateinit var sdk: BeAroundSDK

    /**
     * Unique per test so a stray leftover Activity/lifecycle callback from another test
     * (the BeAroundSDK singleton and its Robolectric Application can outlive a single test
     * method) can never collide on the same (sid, verb) dedupe key. `d` carries the sid
     * (mirroring production, where `d` is opaque but distinct per push) so a test can find
     * its own hit in the sent URL, which never carries `sid` directly (only `d` is sent).
     */
    private fun markerJsonFor(sid: String) =
        """{"t":1,"sid":"$sid","d":"$sid","tr":"https://track.bearound.io"}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // configure() reaches BackgroundScheduler -> WorkManager.getInstance(); Robolectric
        // needs a test WorkManager wired up before that (no existing test exercises a full
        // successful configure(), so this gap was previously unhit).
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        BackgroundScheduler._resetForTesting()

        PushEventQueue.resetForTest(context)
        sdk = BeAroundSDK.getInstance(context)
        // The singleton persists across tests in this run (only the FIRST getInstance()
        // call ever sets its private `context` field); rebind it to THIS test's fresh
        // Robolectric Application so lifecycle-callback registration targets the right
        // instance instead of a previous test's dead Application.
        BeAroundSDK::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(sdk, context)
        }
        unregisterNotificationOpenCallbacksForTest()
        SDKConfigStorage.clearConfiguration(context)
    }

    @After
    fun tearDown() {
        PushEventQueue.resetForTest(context)
        SDKConfigStorage.clearConfiguration(context)
        BeAroundSDK::class.java.declaredFields
            .filter { it.type == SDKConfiguration::class.java }
            .forEach { it.isAccessible = true; it.set(sdk, null) }
        unregisterNotificationOpenCallbacksForTest()
    }

    /**
     * Unregisters the SDK's [android.app.Application.ActivityLifecycleCallbacks] from
     * WHICHEVER Application it is currently attached to (per
     * `notificationOpenCallbacksRegisteredOn`), then clears that tracking field. Called
     * from both `@Before` and `@After`: the singleton (and, per observed Robolectric
     * behavior, its Application) can outlive a single test method, so only unregistering
     * in one of the two hooks left a stale registration that double-fired the next test's
     * lifecycle callbacks.
     */
    private fun unregisterNotificationOpenCallbacksForTest() {
        val registeredOnField = BeAroundSDK::class.java.getDeclaredField("notificationOpenCallbacksRegisteredOn")
            .apply { isAccessible = true }
        val registeredOn = registeredOnField.get(sdk) as? android.app.Application
        val callback = BeAroundSDK::class.java.getDeclaredField("notificationOpenCallbacks")
            .apply { isAccessible = true }
            .get(sdk) as android.app.Application.ActivityLifecycleCallbacks
        registeredOn?.unregisterActivityLifecycleCallbacks(callback)
        // Also cover the CURRENT test's Application, in case it differs from whatever was
        // tracked (e.g. first test in the class, where nothing was tracked yet).
        (context as? android.app.Application)?.unregisterActivityLifecycleCallbacks(callback)
        registeredOnField.set(sdk, null)
    }

    private fun captureSentUrls(expectedHits: Int = 1): Pair<MutableList<String>, CountDownLatch> {
        val urls = mutableListOf<String>()
        val latch = CountDownLatch(expectedHits)
        PushEventQueue.transport = PushEventTransport { url ->
            synchronized(urls) { urls.add(url) }
            latch.countDown()
            PushHitOutcome.DRAIN
        }
        return urls to latch
    }

    // region handleRemoteMessage -> received

    @Test
    fun `handleRemoteMessage with a measurable marker enqueues one received event`() {
        val (urls, latch) = captureSentUrls()

        sdk.handleRemoteMessage(mapOf("bearound" to markerJsonFor("sid-received-1")))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue("urls=$urls", urls.any { it.contains("sid-received-1") && it.contains("push:received") })
    }

    @Test
    fun `handleRemoteMessage without sid (sync push) enqueues nothing`() {
        val sendCount = AtomicInteger(0)
        PushEventQueue.transport = PushEventTransport { sendCount.incrementAndGet(); PushHitOutcome.DRAIN }

        sdk.handleRemoteMessage(mapOf("bearound" to """{"t":1}"""))

        Thread.sleep(200)
        assertEquals(0, sendCount.get())
    }

    // endregion

    // region lifecycle open reporting

    @Test
    fun `activity created with a bearound intent extra enqueues open and strips the extra`() {
        val sid = "sid-activity-created-1"
        val (urls, latch) = captureSentUrls(expectedHits = 2) // open + received
        sdk.configure(businessToken = "test-token")

        val intent = Intent().putExtra("bearound", markerJsonFor(sid))
        val controller = Robolectric.buildActivity(Activity::class.java, intent).create().resume()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(urls.any { it.contains(sid) && it.contains("push:open") })
        assertTrue(urls.any { it.contains(sid) && it.contains("push:received") })
        assertFalse(
            "extra must be stripped after reporting",
            controller.get().intent.hasExtra("bearound")
        )
    }

    @Test
    fun `double lifecycle fire (create then resume) reports the open only once`() {
        val sid = "sid-double-fire-1"
        val hitsForSid = mutableListOf<String>()
        val latch = CountDownLatch(2) // open + received
        PushEventQueue.transport = PushEventTransport { url ->
            if (url.contains(sid)) {
                synchronized(hitsForSid) { hitsForSid.add(url) }
                latch.countDown()
            }
            PushHitOutcome.DRAIN
        }
        sdk.configure(businessToken = "test-token")

        val intent = Intent().putExtra("bearound", markerJsonFor(sid))
        // create() then resume(): both callbacks fire on the SAME intent instance.
        Robolectric.buildActivity(Activity::class.java, intent).create().resume()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        // Exactly open + received for THIS sid, never doubled by the second lifecycle callback
        // (a leftover callback from another test method could still fire for A DIFFERENT sid,
        // which this count deliberately ignores by filtering on `sid`).
        val hits = synchronized(hitsForSid) { hitsForSid.toList() }
        assertEquals("hits=$hits", 2, hits.size)
    }

    @Test
    fun `activity without a bearound extra reports nothing`() {
        val sid = "sid-no-extra-1"
        val hitsForSid = AtomicInteger(0)
        PushEventQueue.transport = PushEventTransport { url ->
            if (url.contains(sid)) hitsForSid.incrementAndGet()
            PushHitOutcome.DRAIN
        }
        sdk.configure(businessToken = "test-token")

        Robolectric.buildActivity(Activity::class.java).create().resume()

        Thread.sleep(200)
        assertEquals(0, hitsForSid.get())
    }

    // endregion

    // region handleNotificationIntent

    @Test
    fun `handleNotificationIntent with a bearound marker returns true and records one open`() {
        val sid = "sid-handle-intent-1"
        val (urls, latch) = captureSentUrls(expectedHits = 2) // open + received

        val intent = Intent().putExtra("bearound", markerJsonFor(sid))
        val consumed = sdk.handleNotificationIntent(intent)

        assertTrue(consumed)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(urls.any { it.contains(sid) && it.contains("push:open") })
        assertFalse(intent.hasExtra("bearound"))
    }

    @Test
    fun `handleNotificationIntent without a bearound marker returns false`() {
        assertFalse(sdk.handleNotificationIntent(Intent()))
        assertFalse(sdk.handleNotificationIntent(null))
    }

    // endregion

    // region trackNotificationOpened (Flutter/RN bridge)

    @Test
    fun `trackNotificationOpened reads data bearound and enqueues open and received`() {
        val sid = "sid-track-opened-1"
        val (urls, latch) = captureSentUrls(expectedHits = 2) // open + received

        sdk.trackNotificationOpened(mapOf("bearound" to markerJsonFor(sid)))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(urls.any { it.contains(sid) && it.contains("push:open") })
    }

    @Test
    fun `trackNotificationOpened without bearound key is a no-op`() {
        val sendCount = AtomicInteger(0)
        PushEventQueue.transport = PushEventTransport { sendCount.incrementAndGet(); PushHitOutcome.DRAIN }

        sdk.trackNotificationOpened(mapOf("other" to "value"))

        Thread.sleep(200)
        assertEquals(0, sendCount.get())
    }

    // endregion
}

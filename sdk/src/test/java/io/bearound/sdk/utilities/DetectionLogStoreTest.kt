package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectionLogStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DetectionLogStore.clear(context)
        AppStateMonitor.resetForTests()
    }

    @After
    fun tearDown() {
        DetectionLogStore.clear(context)
    }

    @Test
    fun `empty log reads as empty json array`() {
        assertEquals("[]", DetectionLogStore.readJson(context))
    }

    @Test
    fun `entry carries type detail and process state`() {
        DetectionLogStore.append(context, type = "Scan", detail = "0.205 (-47 dBm)")

        val entry = JSONArray(DetectionLogStore.readJson(context)).getJSONObject(0)
        assertEquals("Scan", entry.getString("type"))
        assertEquals("0.205 (-47 dBm)", entry.getString("detail"))
        assertTrue(entry.getLong("timestamp") > 0)
        assertTrue(entry.getString("id").isNotEmpty())
    }

    @Test
    fun `events from a process whose UI never became active are tagged terminated`() {
        // No Activity ever resumed — exactly the broadcast-revived process case.
        DetectionLogStore.append(context, type = "Background", detail = "1 beacon(s)")

        val entry = JSONArray(DetectionLogStore.readJson(context)).getJSONObject(0)
        assertEquals("terminated", entry.getString("state"))
    }

    @Test
    fun `newest entry comes first`() {
        DetectionLogStore.append(context, type = "Scan", detail = "primeiro")
        DetectionLogStore.append(context, type = "Scan", detail = "segundo")

        val arr = JSONArray(DetectionLogStore.readJson(context))
        assertEquals("segundo", arr.getJSONObject(0).getString("detail"))
        assertEquals("primeiro", arr.getJSONObject(1).getString("detail"))
    }

    @Test
    fun `log is capped so it cannot grow without bound`() {
        repeat(520) { DetectionLogStore.append(context, type = "Scan", detail = "n$it") }

        assertEquals(500, JSONArray(DetectionLogStore.readJson(context)).length())
    }

    @Test
    fun `clear empties the log`() {
        DetectionLogStore.append(context, type = "Scan", detail = "algo")
        DetectionLogStore.clear(context)

        assertEquals("[]", DetectionLogStore.readJson(context))
    }
}

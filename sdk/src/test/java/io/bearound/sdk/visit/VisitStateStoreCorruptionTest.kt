package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisitStateStoreCorruptionTest {

    private lateinit var context: Context
    private lateinit var store: VisitStateStore

    private val prefs get() = context.getSharedPreferences(VisitStateStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = VisitStateStore(context)
        store.clear()
    }

    @Test
    fun `a corrupt open stop reads as null and is dropped`() {
        // Valid JSON without the fields: the old getter threw JSONException here.
        prefs.edit().putString("open_stop", """{"env":"env-1","arrival":1}""").commit()

        assertNull(store.openStop)
        assertFalse(prefs.contains("open_stop"))
    }

    @Test
    fun `a soft candidate that is not JSON reads as null and is dropped`() {
        prefs.edit().putString("soft_candidate", "not json").commit()

        assertNull(store.softCandidate)
        assertFalse(prefs.contains("soft_candidate"))
    }

    @Test
    fun `a native registration with a missing field reads as null and is dropped`() {
        prefs.edit().putString("native_registration", """{"sig":"abc"}""").commit()

        assertNull(store.nativeRegistration)
        assertFalse(prefs.contains("native_registration"))
    }

    @Test
    fun `a long stored with the wrong type reads as null and is dropped`() {
        prefs.edit().putString("soft_last_fix_at", "yesterday").commit()

        assertNull(store.softLastFixAt)
        assertFalse(prefs.contains("soft_last_fix_at"))
    }

    @Test
    fun `the tracker survives a corrupt open stop and opens a new one`() {
        prefs.edit().putString("open_stop", """{"arrival":{"lat":"x"}}""").commit()
        val tracker = VisitStopTracker(store, RecordingVisitEventQueue()) { 1_800_000_000_000L }
        val fix = VisitFix(-23.561, -46.656, accuracy = 10f, timestamp = 1_800_000_000_000L)

        assertNull(tracker.openStop())
        assertEquals(true, tracker.arrive("env-1", fix))
        assertEquals("env-1", store.openStop?.environmentId)
    }
}

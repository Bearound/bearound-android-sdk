package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BeAroundSDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LegacyAppPresenceCleanupTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKConfigStorage.clearConfiguration(context)
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(SDKConfigStorage.PREFS_NAME, Context.MODE_PRIVATE)

    /** The layout 3.16.x wrote: one record per namespace, its temp file and the lock file. */
    private fun seedStore(base: File): File {
        val directory = File(base, LegacyAppPresenceCleanup.DIRECTORY).apply { mkdirs() }
        File(directory, "ns_fixture.json").writeText("{\"schemaVersion\":1}")
        File(directory, "ns_fixture.json.tmp").writeText("{}")
        File(directory, ".lock").writeText("")
        return directory
    }

    private fun seedLegacyData(): File {
        val directory = seedStore(context.noBackupFilesDir)
        prefs().edit()
            .putString(LegacyAppPresenceCleanup.CONFIGURATION_KEY, "{\"enabled\":true,\"targets\":[]}")
            .putString("business_token", "fixture-token")
            .commit()
        return directory
    }

    @Test fun `deletes the legacy store and key and leaves everything else in place`() {
        val directory = seedLegacyData()
        val sibling = File(context.noBackupFilesDir, "fixture_other_store").apply { writeText("keep") }

        LegacyAppPresenceCleanup.run(context)

        assertFalse(directory.exists())
        assertFalse(prefs().contains(LegacyAppPresenceCleanup.CONFIGURATION_KEY))
        assertEquals("fixture-token", prefs().getString("business_token", null))
        assertEquals("keep", sibling.readText())
    }

    @Test fun `deletes the store written to the files fallback`() {
        val directory = seedStore(context.filesDir)

        LegacyAppPresenceCleanup.run(context)

        assertFalse(directory.exists())
    }

    @Test fun `is idempotent and a no-op on a clean install`() {
        seedLegacyData()
        LegacyAppPresenceCleanup.run(context)
        val after = prefs().all

        LegacyAppPresenceCleanup.run(context)
        LegacyAppPresenceCleanup.run(context)

        assertEquals(after, prefs().all)
        assertFalse(File(context.noBackupFilesDir, LegacyAppPresenceCleanup.DIRECTORY).exists())
        assertFalse(File(context.filesDir, LegacyAppPresenceCleanup.DIRECTORY).exists())
    }

    @Test fun `sdk start runs the cleanup`() {
        val directory = seedLegacyData()
        val instance = BeAroundSDK::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        instance.set(null, null)
        try {
            BeAroundSDK.getInstance(context)

            val deadline = System.currentTimeMillis() + 5_000L
            while ((directory.exists() || prefs().contains(LegacyAppPresenceCleanup.CONFIGURATION_KEY)) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20L)
            }
            assertFalse(directory.exists())
            assertFalse(prefs().contains(LegacyAppPresenceCleanup.CONFIGURATION_KEY))
            assertTrue(prefs().contains("business_token"))
        } finally {
            instance.set(null, previous)
        }
    }
}

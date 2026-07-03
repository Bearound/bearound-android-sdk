package io.bearound.sdk.utilities

import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Tests the manufacturer-fallback classification of [OemPowerProfile]. The property-based
 * detection (ro.miui.ui.version.name etc.) needs a real ROM; under Robolectric every
 * SystemProperties read returns empty, so detection falls through to Build.MANUFACTURER —
 * which is exactly the fallback path these tests pin down.
 */
@RunWith(RobolectricTestRunner::class)
class OemPowerProfileTest {

    @After
    fun tearDown() {
        OemPowerProfile.overrideForTest(null)
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "robolectric")
    }

    private fun detectFor(manufacturer: String): OemPowerProfile.Profile {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
        OemPowerProfile.overrideForTest(null) // drop the cache so get() re-detects
        return OemPowerProfile.get()
    }

    @Test
    fun `xiaomi falls back to aggressive`() {
        val p = detectFor("Xiaomi")

        assertEquals(OemPowerProfile.Aggressiveness.AGGRESSIVE, p.aggressiveness)
    }

    @Test
    fun `samsung is classified moderate as One UI`() {
        val p = detectFor("samsung")

        assertEquals("One UI", p.rom)
        assertEquals(OemPowerProfile.Aggressiveness.MODERATE, p.aggressiveness)
    }

    @Test
    fun `stock android is standard`() {
        val p = detectFor("Google")

        assertNull(p.rom)
        assertEquals(OemPowerProfile.Aggressiveness.STANDARD, p.aggressiveness)
    }

    @Test
    fun `oneplus and vivo fall back to aggressive`() {
        assertEquals(OemPowerProfile.Aggressiveness.AGGRESSIVE, detectFor("OnePlus").aggressiveness)
        assertEquals(OemPowerProfile.Aggressiveness.AGGRESSIVE, detectFor("vivo").aggressiveness)
    }
}

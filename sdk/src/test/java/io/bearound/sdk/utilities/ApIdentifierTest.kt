package io.bearound.sdk.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The canonicalisation is the one piece that silently breaks the whole access-point
 * map if it drifts: iOS and Android must produce the SAME identifier for the SAME
 * router, forever. These tests pin that contract.
 */
class ApIdentifierTest {

    /**
     * Golden value for a fixed input, verified on real hardware on BOTH platforms. If this
     * assertion ever fails, every identifier already issued becomes unreachable under the
     * new hash — so it is a compatibility gate, not a style check.
     */
    private val GOLDEN_ID = "9a6abef5e0c70054"

    @Test
    fun `android and ios formats agree on the same router`() {
        val android = ApIdentifier.from("00:00:5e:00:53:01") // keeps leading zeros
        val ios = ApIdentifier.from("00:0:5e:0:53:1")       // drops them

        assertEquals(GOLDEN_ID, android)
        assertEquals(GOLDEN_ID, ios)
    }

    @Test
    fun `case and separator do not change the identity`() {
        assertEquals(GOLDEN_ID, ApIdentifier.from("00:00:5E:00:53:01"))
        assertEquals(GOLDEN_ID, ApIdentifier.from("00-00-5e-00-53-01"))
        assertEquals(GOLDEN_ID, ApIdentifier.from("  00:00:5e:00:53:01  "))
    }

    @Test
    fun `identifier is 16 hex characters`() {
        val id = ApIdentifier.from("00:00:5e:00:53:0a")
        assertNotNull(id)
        assertEquals(16, id!!.length)
        assertEquals(true, id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `different routers get different identifiers`() {
        val a = ApIdentifier.from("00:00:5e:00:53:01")
        val b = ApIdentifier.from("00:00:5e:00:53:02")
        assertEquals(false, a == b)
    }

    @Test
    fun `platform placeholders are rejected`() {
        // Android hands this back when the location permission is missing — hashing it
        // would invent one phantom access point shared by every unpermitted device.
        assertNull(ApIdentifier.from("02:00:00:00:00:00"))
        assertNull(ApIdentifier.from("00:00:00:00:00:00"))
        assertNull(ApIdentifier.from("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `malformed input returns null instead of a bogus identifier`() {
        assertNull(ApIdentifier.from(null))
        assertNull(ApIdentifier.from(""))
        assertNull(ApIdentifier.from("00:00:5e:00:53"))        // 5 octets
        assertNull(ApIdentifier.from("00:00:5e:00:53:01:aa"))  // 7 octets
        assertNull(ApIdentifier.from("zz:00:5e:00:53:01"))     // not hex
        assertNull(ApIdentifier.from("00005e005301"))          // no separators
    }
}

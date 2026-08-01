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
     * The value below was verified on real hardware during the POC — the office router
     * seen from an Android phone and from an iPhone. If this assertion ever fails, every
     * access point already mapped becomes unreachable under the new hash.
     */
    private val OFFICE_AP = "2dc5d7448d0b3ef4"

    @Test
    fun `android and ios formats agree on the same router`() {
        val android = ApIdentifier.from("b8:1e:61:00:95:0e") // keeps leading zeros
        val ios = ApIdentifier.from("b8:1e:61:0:95:e")       // drops them

        assertEquals(OFFICE_AP, android)
        assertEquals(OFFICE_AP, ios)
    }

    @Test
    fun `case and separator do not change the identity`() {
        assertEquals(OFFICE_AP, ApIdentifier.from("B8:1E:61:00:95:0E"))
        assertEquals(OFFICE_AP, ApIdentifier.from("b8-1e-61-00-95-0e"))
        assertEquals(OFFICE_AP, ApIdentifier.from("  b8:1e:61:00:95:0e  "))
    }

    @Test
    fun `identifier is 16 hex characters`() {
        val id = ApIdentifier.from("a4:33:d7:fa:48:b8")
        assertNotNull(id)
        assertEquals(16, id!!.length)
        assertEquals(true, id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `different routers get different identifiers`() {
        val a = ApIdentifier.from("b8:1e:61:00:95:0e")
        val b = ApIdentifier.from("b8:1e:61:00:95:0f")
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
        assertNull(ApIdentifier.from("b8:1e:61:00:95"))        // 5 octets
        assertNull(ApIdentifier.from("b8:1e:61:00:95:0e:aa"))  // 7 octets
        assertNull(ApIdentifier.from("zz:1e:61:00:95:0e"))     // not hex
        assertNull(ApIdentifier.from("b81e6100950e"))          // no separators
    }
}

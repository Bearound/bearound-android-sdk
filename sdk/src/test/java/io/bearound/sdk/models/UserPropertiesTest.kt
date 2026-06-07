package io.bearound.sdk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [UserProperties.mergedWith], which lets identity set at `configure()` survive a later
 * partial `setUserProperties` update instead of being wiped.
 */
class UserPropertiesTest {

    @Test
    fun `mergedWith keeps existing internalId when the update omits it`() {
        val base = UserProperties(internalId = "user-123")
        val update = UserProperties(email = "user@example.com")

        val merged = base.mergedWith(update)

        assertEquals("user-123", merged.internalId)
        assertEquals("user@example.com", merged.email)
    }

    @Test
    fun `mergedWith overrides provided fields and merges custom keys`() {
        val base = UserProperties(internalId = "old", name = "Old", customProperties = mapOf("a" to "1", "b" to "2"))
        val update = UserProperties(internalId = "new", customProperties = mapOf("b" to "9", "c" to "3"))

        val merged = base.mergedWith(update)

        assertEquals("new", merged.internalId)
        assertEquals("Old", merged.name)
        assertEquals("1", merged.customProperties["a"])
        assertEquals("9", merged.customProperties["b"])
        assertEquals("3", merged.customProperties["c"])
    }

    @Test
    fun `mergedWith onto empty base yields the update`() {
        val merged = UserProperties().mergedWith(UserProperties(internalId = "x"))

        assertEquals("x", merged.internalId)
        assertTrue(merged.hasProperties)
    }
}

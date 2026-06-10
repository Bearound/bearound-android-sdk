package io.bearound.sdk.models

import io.bearound.sdk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SDKInfoTest {
    @Test
    fun `sdk technology comes from BuildConfig and is android-native`() {
        val info = SDKInfo(appId = "com.test.app", build = 210)
        assertEquals(BuildConfig.SDK_TECHNOLOGY, info.technology)
        assertEquals("android-native", BuildConfig.SDK_TECHNOLOGY)
        assertEquals("android", info.platform)
    }

    @Test
    fun `sdk version comes from BuildConfig and is 3_3_0`() {
        val info = SDKInfo(appId = "com.test.app", build = 1)
        assertEquals(BuildConfig.SDK_VERSION, info.version)
        assertEquals("3.3.0", BuildConfig.SDK_VERSION)
        assertNotEquals("2.2.1", info.version)
    }
}

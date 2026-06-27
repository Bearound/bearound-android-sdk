package io.bearound.sdk.models

import io.bearound.sdk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SDKInfoTest {
    @Test
    fun `sdk info reports android platform and android-native technology`() {
        val info = SDKInfo(appId = "com.test.app", build = 210)
        assertEquals("android", info.platform)
        assertEquals("android-native", info.technology)
        assertEquals("com.test.app", info.appId)
        assertEquals(210, info.build)
    }

    @Test
    fun `sdk version comes from BuildConfig and is 3_4_1`() {
        val info = SDKInfo(appId = "com.test.app", build = 1)
        assertEquals(BuildConfig.SDK_VERSION, info.version)
        assertEquals("3.4.1", BuildConfig.SDK_VERSION)
        assertNotEquals("2.2.1", info.version)
    }
}

package io.bearound.sdk.models

import io.bearound.sdk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `sdk version comes from BuildConfig and is a non-blank semver`() {
        val info = SDKInfo(appId = "com.test.app", build = 1)
        // The version must come from BuildConfig.SDK_VERSION (single source of truth in
        // gradle.properties) — asserting a hardcoded number here just goes stale on every bump.
        assertEquals(BuildConfig.SDK_VERSION, info.version)
        assertTrue("SDK_VERSION must not be blank", BuildConfig.SDK_VERSION.isNotBlank())
        assertTrue(
            "SDK_VERSION must be a MAJOR.MINOR.PATCH semver, was '${BuildConfig.SDK_VERSION}'",
            BuildConfig.SDK_VERSION.matches(Regex("""\d+\.\d+\.\d+"""))
        )
    }
}

package io.bearound.sdk.models

import io.bearound.sdk.BuildConfig

/**
 * SDK information sent with each request
 */
data class SDKInfo(
    val version: String = BuildConfig.SDK_VERSION,
    val platform: String = "android",
    val appId: String,
    val build: Int
)


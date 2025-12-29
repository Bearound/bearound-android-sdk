package io.bearound.sdk.models

/**
 * SDK information sent with each request
 */
data class SDKInfo(
    val version: String = "1.4.0",
    val platform: String = "android",
    val appId: String,
    val build: Int
)


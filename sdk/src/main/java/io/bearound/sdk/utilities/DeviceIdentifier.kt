package io.bearound.sdk.utilities

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * Device identifier management.
 *
 * Identity source: a stable device id generated ONCE and persisted in [SecureStorage].
 * On first generation the priority is:
 *   1. [Settings.Secure.ANDROID_ID] when valid — survives reinstall on the same
 *      signing key + device since Android 8.
 *   2. random UUID as a fallback.
 * Once stored, the id is frozen and never recomputed — identity must stay stable for
 * the lifetime of the install (independent of advertising IDs, which the SDK no longer collects).
 */
object DeviceIdentifier {
    private const val TAG = "BeAroundSDK-DeviceId"
    private const val STORAGE_KEY = "io.bearound.sdk.deviceId"
    private const val INVALID_ANDROID_ID = "9774d56d682e549c"
    private var cachedDeviceId: String? = null

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }

        SecureStorage.retrieve(STORAGE_KEY)?.let {
            Log.d(TAG, "Using stored device ID")
            cachedDeviceId = it
            return it
        }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val deviceId = if (isValidAndroidId(androidId)) {
            Log.d(TAG, "Generated new device ID from ANDROID_ID")
            androidId!!
        } else {
            Log.d(TAG, "Generated new UUID as device ID")
            UUID.randomUUID().toString()
        }

        SecureStorage.save(STORAGE_KEY, deviceId)
        cachedDeviceId = deviceId
        return deviceId
    }

    private fun isValidAndroidId(androidId: String?): Boolean {
        return !androidId.isNullOrBlank() &&
            androidId != INVALID_ANDROID_ID &&
            androidId.length >= 8
    }

    /**
     * Clear cached values (useful for testing)
     */
    fun clearCache() {
        cachedDeviceId = null
    }
}

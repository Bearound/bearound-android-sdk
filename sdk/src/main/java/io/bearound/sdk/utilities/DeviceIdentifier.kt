package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Device identifier management
 * Priority: GAID (Google Advertising ID) > Secure Storage UUID > Android ID
 */
object DeviceIdentifier {
    private const val TAG = "BeAroundSDK-DeviceId"
    private const val STORAGE_KEY = "io.bearound.sdk.deviceId"
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }

        // Try to get from secure storage first
        SecureStorage.retrieve(STORAGE_KEY)?.let {
            Log.d(TAG, "Using stored UUID as device ID")
            cachedDeviceId = it
            return it
        }

        // Generate and store new UUID
        val uuid = UUID.randomUUID().toString()
        SecureStorage.save(STORAGE_KEY, uuid)
        Log.d(TAG, "Generated new UUID as device ID")
        cachedDeviceId = uuid
        return uuid
    }

    suspend fun getAdvertisingId(context: Context): String? {
        return try {
            withContext(Dispatchers.IO) {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
                if (!adInfo.isLimitAdTrackingEnabled && adInfo.id != null) {
                    Log.d(TAG, "GAID available")
                    adInfo.id
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get GAID: ${e.message}")
            null
        }
    }

    fun isAdTrackingEnabled(context: Context): Boolean {
        return try {
            val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
            !adInfo.isLimitAdTrackingEnabled
        } catch (e: Exception) {
            false
        }
    }
}


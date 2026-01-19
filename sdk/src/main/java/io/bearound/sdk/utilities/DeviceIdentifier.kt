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
    private var cachedAdTrackingEnabled: Boolean? = null
    private var cachedAdvertisingId: String? = null
    private var adInfoFetched = false

    fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        SecureStorage.retrieve(STORAGE_KEY)?.let {
            Log.d(TAG, "Using stored UUID as device ID")
            cachedDeviceId = it
            return it
        }

        val uuid = UUID.randomUUID().toString()
        SecureStorage.save(STORAGE_KEY, uuid)
        Log.d(TAG, "Generated new UUID as device ID")
        cachedDeviceId = uuid
        return uuid
    }

    /**
     * Get advertising ID asynchronously with caching
     */
    suspend fun getAdvertisingId(context: Context): String? {
        // Return cached value if already fetched
        if (adInfoFetched) {
            return cachedAdvertisingId
        }
        
        return try {
            withContext(Dispatchers.IO) {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
                
                // Cache the results
                cachedAdTrackingEnabled = !adInfo.isLimitAdTrackingEnabled
                adInfoFetched = true
                
                if (!adInfo.isLimitAdTrackingEnabled && adInfo.id != null) {
                    Log.d(TAG, "GAID available")
                    cachedAdvertisingId = adInfo.id
                    adInfo.id
                } else {
                    cachedAdvertisingId = null
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get GAID: ${e.message}")
            adInfoFetched = true
            cachedAdvertisingId = null
            null
        }
    }

    /**
     * Check if ad tracking is enabled (uses cached value if available)
     * This is now non-blocking after the first call to getAdvertisingId
     */
    fun isAdTrackingEnabled(): Boolean {
        // Return cached value if available (already fetched asynchronously)
        cachedAdTrackingEnabled?.let { return it }
        
        // Default to false if not yet fetched
        // The actual value will be fetched when getAdvertisingId is called
        return false
    }
    
    /**
     * Prefetch advertising info asynchronously
     * Call this early in app lifecycle to have cached values ready
     */
    suspend fun prefetchAdInfo(context: Context) {
        if (adInfoFetched) return
        getAdvertisingId(context)
    }
    
    /**
     * Clear cached values (useful for testing)
     */
    fun clearCache() {
        cachedAdTrackingEnabled = null
        cachedAdvertisingId = null
        adInfoFetched = false
    }
}

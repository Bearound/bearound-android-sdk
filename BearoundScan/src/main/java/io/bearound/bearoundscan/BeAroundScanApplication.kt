package io.bearound.bearoundscan

import android.app.Application
import android.util.Log
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.utilities.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BeAroundScanApplication : Application() {

    companion object {
        private const val TAG = "BeAroundScanApp"
    }

    private lateinit var backgroundListener: SDKBackgroundListener
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application onCreate - Registering background listener")

        backgroundListener = SDKBackgroundListener(applicationContext)

        val sdk = BeAroundSDK.getInstance(applicationContext)
        sdk.listener = backgroundListener

        // Prefetch Google Advertising ID so it's cached before first sync
        appScope.launch {
            DeviceIdentifier.prefetchAdInfo(applicationContext)
            Log.d(TAG, "GAID prefetch complete - adTracking: ${DeviceIdentifier.isAdTrackingEnabled()}")
        }

        Log.d(TAG, "Background listener registered successfully")
    }
}

package io.bearound.bearoundscan

import android.app.Application
import android.util.Log
import io.bearound.sdk.BeAroundSDK

class BeAroundScanApplication : Application() {

    companion object {
        private const val TAG = "BeAroundScanApp"
    }

    private lateinit var backgroundListener: SDKBackgroundListener

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application onCreate - Registering background listener")

        backgroundListener = SDKBackgroundListener(applicationContext)

        val sdk = BeAroundSDK.getInstance(applicationContext)
        sdk.listener = backgroundListener

        Log.d(TAG, "Background listener registered successfully")
    }
}

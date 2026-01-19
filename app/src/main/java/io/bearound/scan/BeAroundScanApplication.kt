package io.bearound.scan

import android.app.Application
import android.util.Log
import io.bearound.sdk.BeAroundSDK

/**
 * Application class for BeAround Scan example app
 * Registers global SDK listener that persists even when Activity is destroyed
 * This ensures notifications work when app wakes up in background
 */
class BeAroundScanApplication : Application() {
    
    companion object {
        private const val TAG = "BeAroundScanApp"
    }
    
    private lateinit var backgroundListener: SDKBackgroundListener
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application onCreate - Registering background listener")
        
        // Create and register background listener
        // This listener persists even when Activity is destroyed
        // Handles notifications when app wakes up in background
        backgroundListener = SDKBackgroundListener(applicationContext)
        
        val sdk = BeAroundSDK.getInstance(applicationContext)
        sdk.listener = backgroundListener
        
        Log.d(TAG, "Background listener registered successfully")
    }
}

package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import io.bearound.sdk.models.SDKConfiguration

/**
 * Persists SDK configuration to survive app restarts
 */
object SDKConfigStorage {
    private const val PREFS_NAME = "bearound_sdk_config"
    private const val KEY_BUSINESS_TOKEN = "business_token"
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    private const val KEY_ENABLE_BLUETOOTH = "enable_bluetooth"
    private const val KEY_ENABLE_PERIODIC = "enable_periodic"
    private const val KEY_IS_CONFIGURED = "is_configured"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveConfiguration(context: Context, config: SDKConfiguration) {
        getPrefs(context).edit().apply {
            putString(KEY_BUSINESS_TOKEN, config.businessToken)
            putLong(KEY_SYNC_INTERVAL, config.syncInterval)
            putBoolean(KEY_ENABLE_BLUETOOTH, config.enableBluetoothScanning)
            putBoolean(KEY_ENABLE_PERIODIC, config.enablePeriodicScanning)
            putBoolean(KEY_IS_CONFIGURED, true)
            apply()
        }
    }
    
    fun loadConfiguration(context: Context): SDKConfiguration? {
        val prefs = getPrefs(context)
        val isConfigured = prefs.getBoolean(KEY_IS_CONFIGURED, false)
        
        if (!isConfigured) {
            return null
        }
        
        val businessToken = prefs.getString(KEY_BUSINESS_TOKEN, null) ?: return null
        val appId = context.packageName
        val syncInterval = prefs.getLong(KEY_SYNC_INTERVAL, 30000L)
        
        return SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            syncInterval = syncInterval,
            enableBluetoothScanning = prefs.getBoolean(KEY_ENABLE_BLUETOOTH, false),
            enablePeriodicScanning = prefs.getBoolean(KEY_ENABLE_PERIODIC, true)
        )
    }
    
    fun clearConfiguration(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    fun isConfigured(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_CONFIGURED, false)
    }
}


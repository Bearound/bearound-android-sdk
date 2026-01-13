package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.SDKConfiguration

/**
 * Persists SDK configuration to survive app restarts
 */
object SDKConfigStorage {
    private const val PREFS_NAME = "bearound_sdk_config"
    private const val KEY_BUSINESS_TOKEN = "business_token"
    private const val KEY_FOREGROUND_INTERVAL = "foreground_interval"
    private const val KEY_BACKGROUND_INTERVAL = "background_interval"
    private const val KEY_MAX_QUEUED_PAYLOADS = "max_queued_payloads"
    private const val KEY_ENABLE_BLUETOOTH = "enable_bluetooth"
    private const val KEY_ENABLE_PERIODIC = "enable_periodic"
    private const val KEY_IS_CONFIGURED = "is_configured"
    
    // Legacy key for migration
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveConfiguration(context: Context, config: SDKConfiguration) {
        getPrefs(context).edit().apply {
            putString(KEY_BUSINESS_TOKEN, config.businessToken)
            putLong(KEY_FOREGROUND_INTERVAL, config.foregroundScanInterval.milliseconds)
            putLong(KEY_BACKGROUND_INTERVAL, config.backgroundScanInterval.milliseconds)
            putInt(KEY_MAX_QUEUED_PAYLOADS, config.maxQueuedPayloads.value)
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
        
        // Try to load new format first
        val foregroundMillis = prefs.getLong(KEY_FOREGROUND_INTERVAL, -1L)
        val backgroundMillis = prefs.getLong(KEY_BACKGROUND_INTERVAL, -1L)
        val maxQueuedValue = prefs.getInt(KEY_MAX_QUEUED_PAYLOADS, -1)
        
        // Migration: If old format exists but new doesn't, migrate
        val foregroundInterval = if (foregroundMillis > 0) {
            ForegroundScanInterval.fromMilliseconds(foregroundMillis)
        } else {
            // Try legacy sync_interval key
            val legacySyncInterval = prefs.getLong(KEY_SYNC_INTERVAL, 15000L)
            ForegroundScanInterval.fromMilliseconds(legacySyncInterval)
        }
        
        val backgroundInterval = if (backgroundMillis > 0) {
            BackgroundScanInterval.fromMilliseconds(backgroundMillis)
        } else {
            BackgroundScanInterval.SECONDS_30
        }
        
        val maxQueuedPayloads = if (maxQueuedValue > 0) {
            MaxQueuedPayloads.fromValue(maxQueuedValue)
        } else {
            MaxQueuedPayloads.MEDIUM
        }
        
        return SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            foregroundScanInterval = foregroundInterval,
            backgroundScanInterval = backgroundInterval,
            maxQueuedPayloads = maxQueuedPayloads,
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


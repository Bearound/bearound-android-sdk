package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanConfig
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
    private const val KEY_IS_CONFIGURED = "is_configured"
    private const val KEY_SCANNING_ENABLED = "scanning_enabled"
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    private const val KEY_FG_SCAN_ENABLED = "fg_scan_enabled"
    private const val KEY_FG_SCAN_TITLE = "fg_scan_title"
    private const val KEY_FG_SCAN_TEXT = "fg_scan_text"
    private const val KEY_FG_SCAN_ICON = "fg_scan_icon"
    private const val KEY_FG_SCAN_CHANNEL_ID = "fg_scan_channel_id"
    private const val KEY_FG_SCAN_CHANNEL_NAME = "fg_scan_channel_name"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveConfiguration(context: Context, config: SDKConfiguration) {
        getPrefs(context).edit().apply {
            putString(KEY_BUSINESS_TOKEN, config.businessToken)
            putLong(KEY_FOREGROUND_INTERVAL, config.foregroundScanInterval.milliseconds)
            putLong(KEY_BACKGROUND_INTERVAL, config.backgroundScanInterval.milliseconds)
            putInt(KEY_MAX_QUEUED_PAYLOADS, config.maxQueuedPayloads.value)
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
            maxQueuedPayloads = maxQueuedPayloads
        )
    }
    
    fun clearConfiguration(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    fun isConfigured(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_CONFIGURED, false)
    }
    
    /**
     * Save scanning enabled state
     * Used to restore scanning after app kill or device reboot
     */
    fun saveScanningEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_SCANNING_ENABLED, enabled)
            apply()
        }
    }
    
    /**
     * Load scanning enabled state
     * Returns true if scanning was enabled before app kill/reboot
     */
    fun loadScanningEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCANNING_ENABLED, false)
    }

    fun saveForegroundScanConfig(context: Context, config: ForegroundScanConfig) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_FG_SCAN_ENABLED, config.enabled)
            putString(KEY_FG_SCAN_TITLE, config.notificationTitle)
            putString(KEY_FG_SCAN_TEXT, config.notificationText)
            putInt(KEY_FG_SCAN_ICON, config.notificationIcon ?: 0)
            putString(KEY_FG_SCAN_CHANNEL_ID, config.notificationChannelId)
            putString(KEY_FG_SCAN_CHANNEL_NAME, config.notificationChannelName)
            apply()
        }
    }

    fun loadForegroundScanConfig(context: Context): ForegroundScanConfig? {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_FG_SCAN_ENABLED)) return null

        val icon = prefs.getInt(KEY_FG_SCAN_ICON, 0).takeIf { it != 0 }

        return ForegroundScanConfig(
            enabled = prefs.getBoolean(KEY_FG_SCAN_ENABLED, false),
            notificationTitle = prefs.getString(KEY_FG_SCAN_TITLE, "Monitorando região") ?: "Monitorando região",
            notificationText = prefs.getString(KEY_FG_SCAN_TEXT, "Verificando região em background") ?: "Verificando região em background",
            notificationIcon = icon,
            notificationChannelId = prefs.getString(KEY_FG_SCAN_CHANNEL_ID, null),
            notificationChannelName = prefs.getString(KEY_FG_SCAN_CHANNEL_NAME, "Serviço de monitoramento da região") ?: "Serviço de monitoramento da região"
        )
    }
}


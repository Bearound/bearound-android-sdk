package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import io.bearound.sdk.models.ForegroundScanConfig
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.ScanPrecision

/**
 * Persists SDK configuration to survive app restarts
 */
object SDKConfigStorage {
    private const val PREFS_NAME = "bearound_sdk_config"
    private const val KEY_BUSINESS_TOKEN = "business_token"
    private const val KEY_SCAN_PRECISION = "scan_precision"
    private const val KEY_MAX_QUEUED_PAYLOADS = "max_queued_payloads"
    private const val KEY_IS_CONFIGURED = "is_configured"
    private const val KEY_SCANNING_ENABLED = "scanning_enabled"
    // Legacy keys for migration
    private const val KEY_FOREGROUND_INTERVAL = "foreground_interval"
    private const val KEY_BACKGROUND_INTERVAL = "background_interval"
    private const val KEY_SYNC_INTERVAL = "sync_interval"
    // Foreground scan config keys
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
            putString(KEY_SCAN_PRECISION, config.scanPrecision.name)
            putInt(KEY_MAX_QUEUED_PAYLOADS, config.maxQueuedPayloads.value)
            putBoolean(KEY_IS_CONFIGURED, true)
            // Remove legacy keys if they exist
            remove(KEY_FOREGROUND_INTERVAL)
            remove(KEY_BACKGROUND_INTERVAL)
            remove(KEY_SYNC_INTERVAL)
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

        // Try new scan_precision key first
        val precisionName = prefs.getString(KEY_SCAN_PRECISION, null)
        val scanPrecision = if (precisionName != null) {
            ScanPrecision.fromName(precisionName)
        } else {
            // Migration from legacy foreground/background interval keys
            migrateLegacyInterval(prefs)
        }

        val maxQueuedValue = prefs.getInt(KEY_MAX_QUEUED_PAYLOADS, -1)
        val maxQueuedPayloads = if (maxQueuedValue > 0) {
            MaxQueuedPayloads.fromValue(maxQueuedValue)
        } else {
            MaxQueuedPayloads.MEDIUM
        }

        return SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            scanPrecision = scanPrecision,
            maxQueuedPayloads = maxQueuedPayloads
        )
    }

    /**
     * Migrate from legacy ForegroundScanInterval/BackgroundScanInterval to ScanPrecision.
     * - FG 5-10s -> HIGH
     * - FG 15-30s -> MEDIUM
     * - FG 35-60s -> LOW
     * Falls back to legacy sync_interval key with same logic.
     */
    private fun migrateLegacyInterval(prefs: SharedPreferences): ScanPrecision {
        val foregroundMillis = prefs.getLong(KEY_FOREGROUND_INTERVAL, -1L)
        val millis = if (foregroundMillis > 0) {
            foregroundMillis
        } else {
            // Try legacy sync_interval key
            prefs.getLong(KEY_SYNC_INTERVAL, -1L)
        }

        return when {
            millis <= 0 -> ScanPrecision.MEDIUM  // No legacy data
            millis <= 10_000L -> ScanPrecision.HIGH
            millis <= 30_000L -> ScanPrecision.MEDIUM
            else -> ScanPrecision.LOW
        }
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

package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * Deletes what the removed app presence feature left on devices that ran 3.16.x: its private
 * directory under no_backup (or files, its fallback) and its configuration key in the
 * [SDKConfigStorage] preferences. Nothing reads them any more.
 *
 * Idempotent and safe to run on every start: once both are gone, a run only checks that they
 * are still absent. Never throws; a failed deletion is retried on the next start.
 */
internal object LegacyAppPresenceCleanup {
    private const val TAG = "BeAroundSDK-Cleanup"
    internal const val DIRECTORY = "bearound_app_presence"
    internal const val CONFIGURATION_KEY = "app_presence_configuration"

    fun run(context: Context) {
        try {
            listOfNotNull(context.noBackupFilesDir, context.filesDir)
                .map { File(it, DIRECTORY) }
                .filter { it.exists() }
                .forEach { directory ->
                    if (directory.deleteRecursively()) Log.d(TAG, "Removed legacy app presence store")
                }
        } catch (_: Exception) {
            // Retried on the next start.
        }
        try {
            val prefs = context.getSharedPreferences(SDKConfigStorage.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(CONFIGURATION_KEY)) prefs.edit { remove(CONFIGURATION_KEY) }
        } catch (_: Exception) {
            // Retried on the next start.
        }
    }
}

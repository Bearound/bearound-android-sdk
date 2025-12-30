package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage helper using EncryptedSharedPreferences
 * Similar to iOS Keychain
 */
object SecureStorage {
    private const val TAG = "BeAroundSDK-Storage"
    private const val PREFS_NAME = "io.bearound.sdk.secure"
    
    private var encryptedPrefs: SharedPreferences? = null

    fun initialize(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted storage: ${e.message}")
            // Fallback to regular SharedPreferences
            encryptedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun save(key: String, value: String): Boolean {
        return try {
            encryptedPrefs?.edit()?.putString(key, value)?.apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save value for key $key: ${e.message}")
            false
        }
    }

    fun retrieve(key: String): String? {
        return try {
            encryptedPrefs?.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve value for key $key: ${e.message}")
            null
        }
    }

    fun delete(key: String): Boolean {
        return try {
            encryptedPrefs?.edit()?.remove(key)?.apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete value for key $key: ${e.message}")
            false
        }
    }
}


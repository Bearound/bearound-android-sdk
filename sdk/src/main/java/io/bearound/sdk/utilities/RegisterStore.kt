package io.bearound.sdk.utilities

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * Persists the device registration state so that /ingest is called with
 * beacons=[] + syncTrigger="register" at most once per 24 hours, and
 * whenever the device fingerprint changes (e.g. app update, OS update).
 *
 * Fingerprint components: deviceId + appId + businessToken + sdkVersion +
 * osVersion + appBuild. Any change forces an immediate re-registration.
 */
object RegisterStore {

    private const val PREFS_NAME = "bearound_sdk_register"
    private const val KEY_LAST_REGISTER_AT = "last_register_at"
    private const val KEY_LAST_FINGERPRINT = "last_fingerprint"

    /** 24 hours in milliseconds — matches iOS parity. */
    internal const val TTL_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Builds the fingerprint string from the components that, when changed,
     * should trigger a new registration (e.g. app update, new businessToken).
     */
    fun buildFingerprint(
        deviceId: String,
        appId: String,
        businessToken: String,
        sdkVersion: String,
        osVersion: String,
        appBuild: Int
    ): String = "$deviceId|$appId|$businessToken|$sdkVersion|$osVersion|$appBuild"

    /**
     * Returns true when a register call should be sent:
     * - never registered before, OR
     * - fingerprint changed, OR
     * - TTL (24h) elapsed since last successful register.
     */
    fun shouldRegister(context: Context, fingerprint: String): Boolean {
        val p = prefs(context)
        val lastAt = p.getLong(KEY_LAST_REGISTER_AT, -1L)
        val lastFp = p.getString(KEY_LAST_FINGERPRINT, null)

        if (lastAt == -1L || lastFp == null) return true
        if (lastFp != fingerprint) return true
        return (System.currentTimeMillis() - lastAt) > TTL_MS
    }

    /**
     * Persists a successful registration timestamp and fingerprint.
     * Call this only after the /ingest POST succeeds.
     */
    fun markRegistered(context: Context, fingerprint: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_REGISTER_AT, System.currentTimeMillis())
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
            .apply()
    }

    /** Clears persisted state — useful for testing. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Returns the last recorded registration timestamp, or null if never registered. */
    fun lastRegisterAt(context: Context): Long? {
        val v = prefs(context).getLong(KEY_LAST_REGISTER_AT, -1L)
        return if (v == -1L) null else v
    }
}

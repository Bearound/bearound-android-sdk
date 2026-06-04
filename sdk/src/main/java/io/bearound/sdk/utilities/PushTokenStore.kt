package io.bearound.sdk.utilities

/**
 * Stores the device's push token (FCM/APNs) and tracks whether it has been synced
 * to the backend. Backed by [SecureStorage]. The token is sent once with the next
 * request and re-sent only when it changes.
 */
object PushTokenStore {
    private const val TOKEN_KEY = "io.bearound.sdk.pushToken"
    private const val SYNCED_KEY = "io.bearound.sdk.pushTokenSynced"

    /**
     * Registers a push token. If it differs from the stored one, persists it and
     * marks it as not yet synced. Idempotent when the token is unchanged.
     */
    fun setToken(token: String) {
        if (token == SecureStorage.retrieve(TOKEN_KEY)) {
            return
        }
        SecureStorage.save(TOKEN_KEY, token)
        SecureStorage.save(SYNCED_KEY, "false")
    }

    /**
     * Returns the stored token only while it still needs to be synced; null otherwise.
     */
    fun unsyncedToken(): String? {
        if (SecureStorage.retrieve(SYNCED_KEY) == "true") {
            return null
        }
        return SecureStorage.retrieve(TOKEN_KEY)
    }

    /**
     * Marks the stored token as synced so it stops being included in requests.
     */
    fun markSynced() {
        if (SecureStorage.retrieve(TOKEN_KEY) != null) {
            SecureStorage.save(SYNCED_KEY, "true")
        }
    }
}

package io.bearound.sdk.utilities

/** Stores the device's push token and heartbeat-resends it (see [tokenForPayload]). Backed by [SecureStorage]. */
object PushTokenStore {
    private const val TOKEN_KEY = "io.bearound.sdk.pushToken"
    private const val LAST_SENT_KEY = "io.bearound.sdk.pushTokenLastSent"
    private const val LAST_SENT_AT_KEY = "io.bearound.sdk.pushTokenLastSentAt"

    /** Re-send the token if the last successful send is older than 7 days. */
    private const val RESEND_INTERVAL_MS = 604800000L

    fun setToken(token: String) {
        SecureStorage.save(TOKEN_KEY, token)
    }

    /** Returns the current token when it should be sent (changed, never sent, or stale), else null. */
    fun tokenForPayload(): String? {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return null
        val lastSent = SecureStorage.retrieve(LAST_SENT_KEY)
        val lastSentAt = SecureStorage.retrieve(LAST_SENT_AT_KEY)?.toLongOrNull()

        val shouldSend = token != lastSent ||
            lastSentAt == null ||
            (System.currentTimeMillis() - lastSentAt) > RESEND_INTERVAL_MS

        return if (shouldSend) token else null
    }

    fun markSent() {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return
        SecureStorage.save(LAST_SENT_KEY, token)
        SecureStorage.save(LAST_SENT_AT_KEY, System.currentTimeMillis().toString())
    }

    fun lastSentAt(): Long? {
        return SecureStorage.retrieve(LAST_SENT_AT_KEY)?.toLongOrNull()
    }

    /** Current token masked for display (first 8 + "…" + last 4), or null when unset. */
    fun maskedToken(): String? {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return null
        if (token.length <= 12) {
            return "…"
        }
        return "${token.take(8)}…${token.takeLast(4)}"
    }
}

package io.bearound.sdk.utilities

/**
 * Stores the device's push token (FCM/APNs) and tracks when it was last successfully
 * sent to the backend. Backed by [SecureStorage].
 *
 * Delivery is heartbeat-based: the token is included in a payload when it changed
 * since the last successful send OR when the last successful send is older than
 * [RESEND_INTERVAL_MS]. This makes delivery resilient to a single lost send — the
 * device stays reachable instead of becoming unpushable until the next rotation.
 */
object PushTokenStore {
    private const val TOKEN_KEY = "io.bearound.sdk.pushToken"
    private const val LAST_SENT_KEY = "io.bearound.sdk.pushTokenLastSent"
    private const val LAST_SENT_AT_KEY = "io.bearound.sdk.pushTokenLastSentAt"

    /** Re-send the token if the last successful send is older than 7 days. */
    private const val RESEND_INTERVAL_MS = 604800000L

    /**
     * Stores the current push token. No send-state manipulation — delivery is decided
     * by [tokenForPayload] based on what was last successfully sent.
     */
    fun setToken(token: String) {
        SecureStorage.save(TOKEN_KEY, token)
    }

    /**
     * Returns the current token when it should be sent, otherwise null.
     *
     * It should be sent when there is a current token AND either it differs from the
     * last successfully sent token, or it has never been sent, or the last send is
     * older than [RESEND_INTERVAL_MS].
     */
    fun tokenForPayload(): String? {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return null
        val lastSent = SecureStorage.retrieve(LAST_SENT_KEY)
        val lastSentAt = SecureStorage.retrieve(LAST_SENT_AT_KEY)?.toLongOrNull()

        val shouldSend = token != lastSent ||
            lastSentAt == null ||
            (System.currentTimeMillis() - lastSentAt) > RESEND_INTERVAL_MS

        return if (shouldSend) token else null
    }

    /**
     * Records a successful send: remembers the token that was sent and the time.
     */
    fun markSent() {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return
        SecureStorage.save(LAST_SENT_KEY, token)
        SecureStorage.save(LAST_SENT_AT_KEY, System.currentTimeMillis().toString())
    }

    /**
     * Epoch millis of the last successful send, or null if never sent.
     */
    fun lastSentAt(): Long? {
        return SecureStorage.retrieve(LAST_SENT_AT_KEY)?.toLongOrNull()
    }

    /**
     * The current token masked for display (first 8 + "…" + last 4), or null when
     * there is no token. Short tokens are masked wholesale to avoid leaking them.
     */
    fun maskedToken(): String? {
        val token = SecureStorage.retrieve(TOKEN_KEY) ?: return null
        if (token.length <= 12) {
            return "…"
        }
        return "${token.take(8)}…${token.takeLast(4)}"
    }
}

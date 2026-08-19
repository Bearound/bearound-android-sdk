package io.bearound.sdk.models

import android.util.Log

/**
 * What the host app allows the SDK to collect and upload.
 *
 * Three signals in the payload describe the *person* rather than the sighting — the
 * advertising identifier, the device's own coordinates, and the Wi-Fi access points around
 * it. An app may already collect them for its own purposes and still not want them shared
 * with Bearound: a different legal basis, a privacy label it does not want to extend, or a
 * client policy that simply says no.
 *
 * Each switch is **collect-and-send**, not send-only: a disabled signal is never read from
 * the platform in the first place, so nothing to withhold ever exists in memory.
 *
 * Every switch defaults to `true`, so an integration that does not mention them behaves
 * exactly as it does today.
 *
 * @property advertisingId Google Advertising ID (AAID). Off: `permissions.advertisingId` and
 *   `permissions.limitAdTracking` are absent from every payload, and Play Services is never
 *   queried for the identifier.
 * @property location The device's own fix. Off: the `location` block is absent from every
 *   payload. The `permissions.location` / `permissions.locationAccuracy` fields stay — they
 *   report the authorisation the user granted, not where they are.
 * @property wifi Access points seen around the device. Off: the `wifis` array and the
 *   `network.apId` / `network.wifiSSID` fields are absent from every payload, and no Wi-Fi
 *   read or scan nudge is issued.
 */
data class DataCollectionPolicy(
    val advertisingId: Boolean = true,
    val location: Boolean = true,
    val wifi: Boolean = true
) {
    companion object {
        /** Everything on — the behaviour of every SDK version before this switch existed. */
        val ALL_ENABLED = DataCollectionPolicy()
    }
}

/**
 * Process-wide holder for the active policy.
 *
 * Global on purpose: the policy has to be honoured by every collector, on every path that
 * builds a payload — including the ones a background relaunch takes, which construct their
 * collectors before anything hands them a configuration. A single guarded value is what keeps
 * a disabled signal disabled on *all* of them.
 *
 * Defaults to [DataCollectionPolicy.ALL_ENABLED] so a payload built before `configure()` (or
 * by a host that never touches these switches) is unchanged.
 */
object DataCollectionPolicyStore {
    private const val TAG = "BeAroundSDK-Privacy"

    @Volatile
    var current: DataCollectionPolicy = DataCollectionPolicy.ALL_ENABLED
        private set

    fun apply(policy: DataCollectionPolicy) {
        current = policy
        if (policy != DataCollectionPolicy.ALL_ENABLED) {
            Log.i(
                TAG,
                "Data collection policy: advertisingId=${policy.advertisingId.state()} " +
                    "location=${policy.location.state()} wifi=${policy.wifi.state()}"
            )
        }
    }

    /** Test hook — restores the default so one test's policy cannot leak into the next. */
    fun reset() = apply(DataCollectionPolicy.ALL_ENABLED)

    private fun Boolean.state() = if (this) "on" else "OFF"
}

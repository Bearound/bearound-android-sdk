package io.bearound.sdk.utilities

import java.security.MessageDigest
import java.util.Locale

/**
 * Canonical, privacy-preserving identifier for a Wi-Fi access point.
 *
 * The raw BSSID (the access point's MAC address) never leaves the device — only this
 * hash does. Two properties make it work:
 *
 * 1. **Deterministic across platforms.** iOS reports the same address without leading
 *    zeros (`b8:1e:61:0:95:e`) while Android keeps them (`b8:1e:61:00:95:0e`). Hashing
 *    the raw strings would give one physical router two different identities and the
 *    access-point map would never converge. Canonical form fixes that: each octet
 *    left-padded to two hex digits, lowercased, joined without separators.
 *
 * 2. **Deterministic across devices.** No salt, on purpose — two phones that see the
 *    same router must produce the same `apId`, otherwise cross-device correlation
 *    (the whole point of the map) is impossible. This is pseudonymisation, not
 *    anonymisation, and it is a conscious trade-off.
 *
 * Verified on-device: `b8:1e:61:00:95:0e` (Android) and `b8:1e:61:0:95:e` (iOS) both
 * produce `2dc5d7448d0b3ef4`.
 */
internal object ApIdentifier {

    /**
     * BSSIDs the platform hands back when it will not tell us the real one — a missing
     * permission, a disconnected interface, or a broadcast address. Hashing them would
     * create a phantom access point shared by every device in that state.
     */
    private val PLACEHOLDERS = setOf(
        "020000000000", // Android: location permission missing
        "000000000000",
        "ffffffffffff"
    )

    private val CANONICAL = Regex("[0-9a-f]{12}")

    /**
     * @return the 16-hex-character identifier, or `null` when [bssid] is absent,
     *         malformed, or one of the platform placeholders.
     */
    fun from(bssid: String?): String? {
        val raw = bssid?.trim()?.replace('-', ':') ?: return null
        val octets = raw.split(':')
        if (octets.size != 6) return null

        val canonical = buildString {
            for (octet in octets) {
                if (octet.isEmpty() || octet.length > 2) return null
                append(octet.padStart(2, '0').lowercase(Locale.ROOT))
            }
        }

        if (!CANONICAL.matches(canonical)) return null
        if (canonical in PLACEHOLDERS) return null

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}

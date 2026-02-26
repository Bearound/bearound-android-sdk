package io.bearound.sdk.models

/**
 * Scan precision configuration.
 *
 * Controls the duty cycle and sync interval for beacon scanning.
 * - HIGH: Continuous BLE + Beacon scanning, sync every 15s
 * - MEDIUM: 3x (10s scan + 10s pause) per minute, sync every 60s
 * - LOW: 1x (10s scan + 50s pause) per minute, sync every 60s
 */
enum class ScanPrecision {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromName(name: String): ScanPrecision {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }
}

/**
 * Maximum queued payloads configuration.
 *
 * Controls how many failed API request batches are stored for retry.
 * Each batch contains all beacons from a single sync operation.
 */
enum class MaxQueuedPayloads(val value: Int) {
    /** Store up to 50 failed batches */
    SMALL(50),

    /** Store up to 100 failed batches (default) */
    MEDIUM(100),

    /** Store up to 200 failed batches */
    LARGE(200),

    /** Store up to 500 failed batches */
    XLARGE(500);

    companion object {
        /**
         * Get MaxQueuedPayloads from integer value
         * @param size The integer value
         * @return The matching enum or MEDIUM as default
         */
        fun fromValue(size: Int): MaxQueuedPayloads {
            return entries.find { it.value == size } ?: MEDIUM
        }
    }
}

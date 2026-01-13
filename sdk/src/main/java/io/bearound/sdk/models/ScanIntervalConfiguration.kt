package io.bearound.sdk.models

/**
 * Foreground scan interval configuration
 * Controls how frequently the SDK scans for beacons when the app is in foreground
 */
enum class ForegroundScanInterval(val timeIntervalSeconds: Long) {
    SECONDS_5(5),
    SECONDS_10(10),
    SECONDS_15(15),
    SECONDS_20(20),
    SECONDS_25(25),
    SECONDS_30(30),
    SECONDS_35(35),
    SECONDS_40(40),
    SECONDS_45(45),
    SECONDS_50(50),
    SECONDS_55(55),
    SECONDS_60(60);

    companion object {
        fun fromName(name: String?): ForegroundScanInterval {
            return entries.firstOrNull { it.name == name } ?: SECONDS_15
        }
    }
}

/**
 * Background scan interval configuration
 * Controls how frequently the SDK scans for beacons when the app is in background
 */
enum class BackgroundScanInterval(val timeIntervalSeconds: Long) {
    SECONDS_60(60),
    SECONDS_90(90),
    SECONDS_120(120);

    companion object {
        fun fromName(name: String?): BackgroundScanInterval {
            return entries.firstOrNull { it.name == name } ?: SECONDS_60
        }
    }
}

/**
 * Maximum queued payloads configuration
 * Controls how many failed API requests are stored for retry
 */
enum class MaxQueuedPayloads(val value: Int) {
    SMALL(50),
    MEDIUM(100),
    LARGE(200),
    XLARGE(500);

    companion object {
        fun fromValue(value: Int): MaxQueuedPayloads {
            return entries.firstOrNull { it.value == value } ?: MEDIUM
        }
    }
}
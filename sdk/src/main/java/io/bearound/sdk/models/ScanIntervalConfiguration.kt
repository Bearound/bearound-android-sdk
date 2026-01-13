package io.bearound.sdk.models

/**
 * Foreground scan interval configuration.
 *
 * Controls how frequently the SDK scans for beacons when the app is in foreground.
 */
enum class ForegroundScanInterval(val milliseconds: Long) {
    /** Scan every 5 seconds */
    SECONDS_5(5000),
    
    /** Scan every 10 seconds */
    SECONDS_10(10000),
    
    /** Scan every 15 seconds (default) */
    SECONDS_15(15000),
    
    /** Scan every 20 seconds */
    SECONDS_20(20000),
    
    /** Scan every 25 seconds */
    SECONDS_25(25000),
    
    /** Scan every 30 seconds */
    SECONDS_30(30000),
    
    /** Scan every 35 seconds */
    SECONDS_35(35000),
    
    /** Scan every 40 seconds */
    SECONDS_40(40000),
    
    /** Scan every 45 seconds */
    SECONDS_45(45000),
    
    /** Scan every 50 seconds */
    SECONDS_50(50000),
    
    /** Scan every 55 seconds */
    SECONDS_55(55000),
    
    /** Scan every 60 seconds */
    SECONDS_60(60000);
    
    companion object {
        /**
         * Get ForegroundScanInterval from milliseconds value
         * @param millis The milliseconds value
         * @return The matching enum or SECONDS_15 as default
         */
        fun fromMilliseconds(millis: Long): ForegroundScanInterval {
            return entries.find { it.milliseconds == millis } ?: SECONDS_15
        }
    }
}

/**
 * Background scan interval configuration.
 *
 * Controls how frequently the SDK scans for beacons when the app is in background.
 */
enum class BackgroundScanInterval(val milliseconds: Long) {
    /** Scan every 15 seconds */
    SECONDS_15(15000),
    
    /** Scan every 30 seconds (default) */
    SECONDS_30(30000),
    
    /** Scan every 60 seconds */
    SECONDS_60(60000),
    
    /** Scan every 90 seconds */
    SECONDS_90(90000),
    
    /** Scan every 120 seconds */
    SECONDS_120(120000);
    
    companion object {
        /**
         * Get BackgroundScanInterval from milliseconds value
         * @param millis The milliseconds value
         * @return The matching enum or SECONDS_30 as default
         */
        fun fromMilliseconds(millis: Long): BackgroundScanInterval {
            return entries.find { it.milliseconds == millis } ?: SECONDS_30
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

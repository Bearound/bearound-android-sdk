package io.bearound.sdk.utilities

import android.os.Build

/**
 * Detects chipsets with a weak BLE *receiver* — SoCs whose controller firmware opens
 * tiny listen windows regardless of the requested scan mode.
 *
 * Bench-measured (2026-07): Unisoc/Spreadtrum devices (Moto G35 / T760, realme C61 /
 * T612) capture ~2-8% of the frames a mid-range Exynos (Galaxy A16) captures at the
 * SAME RSSI, screen on, LOW_LATENCY granted, host reporting continuous scan time.
 * No software setting recovers the missing frames — but the SDK can compensate on the
 * RETENTION side: with sparse captures, gaps between useful frames stretch to 10-45 s,
 * so eviction/stale windows are doubled on these devices to keep the host list stable
 * ("detected → holds on screen") instead of flickering.
 *
 * Detection is signature-based and conservative: [Build.SOC_MANUFACTURER] (API 31+,
 * "Unisoc"/"Spreadtrum") with a [Build.HARDWARE] prefix fallback ("ums*"/"sprd*"/"sp98*")
 * for older API levels. False negatives just keep default timings; false positives only
 * make a list slightly more patient.
 */
internal object WeakReceiverProfile {

    val isWeakReceiver: Boolean by lazy { detect() }

    private fun detect(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MANUFACTURER.lowercase()
            if (soc.contains("unisoc") || soc.contains("spreadtrum")) return true
        }
        val hw = Build.HARDWARE.lowercase()
        return hw.startsWith("ums") || hw.startsWith("sprd") || hw.startsWith("sp98")
    }
}

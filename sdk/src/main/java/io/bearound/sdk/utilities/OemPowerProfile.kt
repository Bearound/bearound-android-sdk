package io.bearound.sdk.utilities

import android.annotation.SuppressLint
import android.os.Build

/**
 * Detects the device's OEM power-management profile so the SDK (and the host app) can adapt
 * automatically to ROMs that aggressively kill background work — the number-one field cause
 * of "the SDK stopped detecting" reports on Android.
 *
 * Detection uses the ROM's own system properties (via reflection over
 * `android.os.SystemProperties`, defensively — it is a hidden API) with `Build.MANUFACTURER`
 * as fallback, because manufacturer alone misses rebrands (POCO/Redmi → Xiaomi) and cannot
 * distinguish MIUI from HyperOS.
 */
object OemPowerProfile {

    /** How aggressively this ROM kills background work, per community data (dontkillmyapp). */
    enum class Aggressiveness {
        /** Stock-like Android (Pixel, Motorola, Sony…) — AOSP rules only. */
        STANDARD,

        /** Extra battery management that benefits from user exemption (Samsung One UI). */
        MODERATE,

        /**
         * ROM kills sticky services/receivers and disables autostart by default
         * (Xiaomi MIUI/HyperOS, Huawei EMU I/HarmonyOS, Oppo ColorOS, Vivo, OnePlus, Meizu…).
         * Reliable background detection typically REQUIRES user action (autostart +
         * battery exemption).
         */
        AGGRESSIVE
    }

    data class Profile(
        /** Marketing name of the ROM ("HyperOS", "MIUI", "One UI", "ColorOS"…), null if stock. */
        val rom: String?,
        /** ROM version string when the ROM exposes one. */
        val romVersion: String?,
        val aggressiveness: Aggressiveness
    )

    @Volatile
    private var cached: Profile? = null

    fun get(): Profile = cached ?: detect().also { cached = it }

    private fun detect(): Profile {
        // Xiaomi / Redmi / POCO — HyperOS exposes ro.mi.os.version.name, MIUI the classic prop.
        prop("ro.mi.os.version.name")?.let {
            return Profile("HyperOS", it, Aggressiveness.AGGRESSIVE)
        }
        prop("ro.miui.ui.version.name")?.let {
            return Profile("MIUI", it, Aggressiveness.AGGRESSIVE)
        }
        // Huawei / Honor
        prop("ro.build.version.emui")?.let {
            return Profile("EMUI", it, Aggressiveness.AGGRESSIVE)
        }
        prop("ro.build.version.magic")?.let {
            return Profile("MagicOS", it, Aggressiveness.AGGRESSIVE)
        }
        // Oppo / Realme (ColorOS); newer builds use the oplus prop.
        (prop("ro.build.version.oplusrom") ?: prop("ro.build.version.opporom"))?.let {
            return Profile("ColorOS", it, Aggressiveness.AGGRESSIVE)
        }
        // Vivo / iQOO
        prop("ro.vivo.os.version")?.let {
            return Profile("Funtouch/OriginOS", it, Aggressiveness.AGGRESSIVE)
        }
        // OnePlus (older OxygenOS; newer OnePlus report ColorOS props above)
        prop("ro.oxygen.version")?.let {
            return Profile("OxygenOS", it, Aggressiveness.AGGRESSIVE)
        }
        // Meizu
        prop("ro.flyme.version.id")?.let {
            return Profile("Flyme", it, Aggressiveness.AGGRESSIVE)
        }
        // Samsung One UI — no version prop readable; classify by manufacturer.
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            return Profile("One UI", null, Aggressiveness.MODERATE)
        }
        // Manufacturer fallback for ROMs whose props were locked down.
        val aggressiveManufacturers = setOf(
            "xiaomi", "huawei", "honor", "oppo", "realme", "vivo", "oneplus", "meizu", "letv", "asus"
        )
        if (Build.MANUFACTURER.lowercase() in aggressiveManufacturers) {
            return Profile(null, null, Aggressiveness.AGGRESSIVE)
        }
        return Profile(null, null, Aggressiveness.STANDARD)
    }

    /** Reads a system property via reflection; null when absent/empty/inaccessible. */
    @SuppressLint("PrivateApi")
    private fun prop(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Test hook: override the detected profile (pass null to re-detect). */
    internal fun overrideForTest(profile: Profile?) {
        cached = profile
    }
}

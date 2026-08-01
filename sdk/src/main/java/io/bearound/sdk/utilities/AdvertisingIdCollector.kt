package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the Google Advertising ID (AAID) — the resettable, user-controlled identifier that
 * lets the same person be recognised across apps for advertising purposes.
 *
 * Three properties of the platform shape this implementation:
 *
 * 1. **No prompt exists.** Unlike iOS, Android has no dialog for the advertising ID. The
 *    user's choice lives in system settings, and the platform enforces it for us: opting out
 *    turns the ID into a zeroed UUID, which we treat as absent.
 *
 * 2. **It must not be read on the main thread.** `AdvertisingIdClient` throws if you try. So
 *    the value is fetched once in the background and cached; payload building reads the
 *    cache synchronously.
 *
 * 3. **Google Play Services is a soft dependency.** `play-services-ads-identifier` is
 *    `compileOnly`, mirroring the Firebase pattern: apps that bundle Play Services get the
 *    ID, apps that do not simply report none. No host is forced to take the dependency.
 */
internal object AdvertisingIdCollector {

    private const val TAG = "AdvertisingId"

    /** Returned by the platform when the user opted out of ad personalisation. */
    private const val OPTED_OUT = "00000000-0000-0000-0000-000000000000"

    private val cached = AtomicReference<String?>(null)
    private val limitTracking = AtomicReference<Boolean?>(null)

    /**
     * Fetches the advertising ID in the background and caches it. Safe to call repeatedly —
     * a refresh is cheap and the user can reset the ID at any time.
     */
    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val info = com.google.android.gms.ads.identifier.AdvertisingIdClient
                    .getAdvertisingIdInfo(context.applicationContext)

                val id = info.id
                val limited = info.isLimitAdTrackingEnabled
                limitTracking.set(limited)

                cached.set(
                    when {
                        limited -> null
                        id.isNullOrBlank() -> null
                        id == OPTED_OUT -> null
                        else -> id
                    }
                )
            } catch (t: Throwable) {
                // NoClassDefFoundError when Play Services is not bundled; GooglePlayServices*
                // exceptions when it is present but unavailable on the device. Both mean the
                // same thing to us: no advertising ID, and nothing else changes.
                Log.d(TAG, "advertising ID unavailable: ${t.javaClass.simpleName}")
                cached.set(null)
            }
        }
    }

    /**
     * @return the cached advertising ID, or `null` when the user opted out, Play Services is
     *         absent, or the first fetch has not landed yet.
     */
    fun current(): String? = cached.get()

    /**
     * @return true when the user asked apps not to track them, false when they allow it, and
     *         `null` while unknown. Reported separately from the ID so the backend can tell
     *         "opted out" apart from "Play Services missing".
     */
    fun isLimitAdTrackingEnabled(): Boolean? = limitTracking.get()
}

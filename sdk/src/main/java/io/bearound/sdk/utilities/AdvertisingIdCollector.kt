package io.bearound.sdk.utilities

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the Google Advertising ID (AAID) — the resettable, user-controlled identifier that
 * lets the same person be recognised across apps for advertising purposes.
 *
 * Four properties of the platform shape this implementation:
 *
 * 1. **No prompt exists.** Unlike iOS, Android has no dialog for the advertising ID. The
 *    user's choice lives in system settings, and the platform enforces it for us: opting out
 *    turns the ID into a zeroed UUID, which we treat as absent.
 *
 * 2. **It must not be read on the main thread.** `AdvertisingIdClient` throws if you try. So
 *    the value is fetched in the background and cached; payload building reads the cache
 *    synchronously.
 *
 * 3. **The first read can fail, and failing once must not be permanent.** Play Services can
 *    be slow, updating, or frozen by an aggressive OEM — measured in the field, devices that
 *    missed the first fetch reported no ID in *every* session afterwards, while devices that
 *    caught it reported one in 100% of theirs. So a failure schedules a retry with backoff
 *    instead of settling into a permanent null.
 *
 * 4. **The ID is not immutable.** The user can reset it at any time, which is the whole point
 *    of a resettable identifier. A cached value is therefore refreshed after [TTL_SUCESSO_MS]
 *    so a stale ID does not follow the device forever.
 *
 * 5. **Google Play Services is a soft dependency.** `play-services-ads-identifier` is
 *    `compileOnly`, mirroring the Firebase pattern: apps that bundle Play Services get the
 *    ID, apps that do not simply report none. No host is forced to take the dependency.
 */
internal object AdvertisingIdCollector {

    private const val TAG = "AdvertisingId"

    /** Returned by the platform when the user opted out of ad personalisation. */
    private const val OPTED_OUT = "00000000-0000-0000-0000-000000000000"

    /** A cached ID is re-read after this long — the user can reset it whenever they want. */
    private const val TTL_SUCESSO_MS = 6 * 60 * 60 * 1000L

    /** First retry delay after a failed read; doubles up to [BACKOFF_MAX_MS]. */
    private const val BACKOFF_BASE_MS = 30_000L
    private const val BACKOFF_MAX_MS = 30 * 60 * 1000L

    private val cached = AtomicReference<String?>(null)
    private val limitTracking = AtomicReference<Boolean?>(null)

    /** `elapsedRealtime` of the last successful read — monotonic, unaffected by clock changes. */
    private val ultimoSucessoMs = AtomicLong(0L)

    /** Before this instant a retry is pointless; set by the backoff after each failure. */
    private val proximaTentativaMs = AtomicLong(0L)
    private val falhasSeguidas = AtomicInteger(0)

    /** Guards against piling up fetches when several payloads are built at once. */
    private val buscando = AtomicBoolean(false)

    /**
     * Fetches the advertising ID when it is missing or stale. Cheap to call on every payload:
     * it returns immediately unless a fetch is actually warranted.
     */
    fun ensureFresh(context: Context) {
        val precisa = deveBuscar(
            temValor = cached.get() != null,
            agoraMs = SystemClock.elapsedRealtime(),
            ultimoSucessoMs = ultimoSucessoMs.get(),
            proximaTentativaMs = proximaTentativaMs.get(),
        )
        if (precisa) buscar(context)
    }

    /**
     * A política de quando vale reler, isolada do relógio e da corrotina para poder ser
     * verificada em teste: com valor em mãos só relemos depois do TTL; sem valor, assim que
     * o backoff da última falha vencer.
     */
    internal fun deveBuscar(
        temValor: Boolean,
        agoraMs: Long,
        ultimoSucessoMs: Long,
        proximaTentativaMs: Long,
    ): Boolean =
        if (temValor) agoraMs - ultimoSucessoMs >= TTL_SUCESSO_MS
        else agoraMs >= proximaTentativaMs

    /** Espera antes da próxima tentativa: dobra a cada falha seguida, com teto. */
    internal fun esperaDoBackoff(falhasSeguidas: Int): Long =
        (BACKOFF_BASE_MS shl (falhasSeguidas - 1).coerceIn(0, 6)).coerceAtMost(BACKOFF_MAX_MS)

    /** Forces a read regardless of cache state — used once at configure time. */
    fun refresh(context: Context) = buscar(context)

    private fun buscar(context: Context) {
        if (!buscando.compareAndSet(false, true)) return
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
                // Opting out is a definitive answer, not a failure: it counts as a successful
                // read so we honour the TTL instead of retrying against the user's choice.
                ultimoSucessoMs.set(SystemClock.elapsedRealtime())
                falhasSeguidas.set(0)
                proximaTentativaMs.set(0L)
            } catch (t: Throwable) {
                // NoClassDefFoundError when Play Services is not bundled; GooglePlayServices*
                // exceptions when it is present but unavailable on the device. Either way the
                // next attempt is delayed rather than abandoned — a device that fails forever
                // simply keeps paying a cheap, widely spaced retry.
                val espera = esperaDoBackoff(falhasSeguidas.incrementAndGet())
                proximaTentativaMs.set(SystemClock.elapsedRealtime() + espera)
                Log.d(TAG, "advertising ID unavailable (${t.javaClass.simpleName}); retry in ${espera}ms")
            } finally {
                buscando.set(false)
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

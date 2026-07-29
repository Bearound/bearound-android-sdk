package io.bearound.sdk.utilities

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager

/**
 * Best-effort classification of the PROCESS state at write time, so persisted
 * detections can say *how* the app was running when they happened:
 *
 *  - `foreground` — an Activity is resumed
 *  - `background` — the app ran the UI before, but no Activity is resumed now
 *  - `backgroundLocked` — same as background, with the device screen LOCKED
 *  - `terminated` — the process is alive but the UI NEVER became active: it was
 *    started by the system (broadcast-delivered BLE scan result, boot receiver,
 *    watchdog alarm) while the app was closed. This is the Android counterpart
 *    of an iOS state-restoration relaunch, and the only tag that answers
 *    "did the SDK work with the app closed?".
 *
 * The `terminated` heuristic is the same one the iOS SDK uses ("process alive,
 * UI never active"), which keeps both platforms reporting the same thing.
 *
 * Mirrors the iOS `AppStateMonitor`.
 */
internal object AppStateMonitor {

    enum class Tag(val raw: String) {
        FOREGROUND("foreground"),
        BACKGROUND("background"),
        BACKGROUND_LOCKED("backgroundLocked"),
        TERMINATED("terminated"),
    }

    private val lock = Any()
    private var initialized = false
    private var resumedActivities = 0

    /** True once ANY Activity of this process resumed — the terminated discriminator. */
    private var wasEverActive = false

    private var appContext: Context? = null

    /**
     * Registers the Activity lifecycle observer. Safe to call repeatedly and from
     * any thread; a host without an Application context (rare, e.g. some test
     * harnesses) simply keeps the default classification.
     */
    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            appContext = app
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                synchronized(lock) {
                    resumedActivities++
                    wasEverActive = true
                }
            }

            override fun onActivityPaused(activity: Activity) {
                synchronized(lock) { if (resumedActivities > 0) resumedActivities-- }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Current tag. Safe from any thread; never throws. */
    fun currentTag(): Tag {
        val (active, resumed) = synchronized(lock) { wasEverActive to resumedActivities }

        // Process running without the UI ever having been active → the system
        // started us while the app was closed.
        if (!active) return Tag.TERMINATED
        if (resumed > 0) return Tag.FOREGROUND
        return if (isDeviceLocked()) Tag.BACKGROUND_LOCKED else Tag.BACKGROUND
    }

    private fun isDeviceLocked(): Boolean {
        val ctx = synchronized(lock) { appContext } ?: return false
        return try {
            val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            keyguard?.isKeyguardLocked == true || power?.isInteractive == false
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    internal fun resetForTests() {
        synchronized(lock) {
            initialized = false
            resumedActivities = 0
            wasEverActive = false
            appContext = null
        }
    }
}

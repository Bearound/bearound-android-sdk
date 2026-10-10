package io.bearound.sdk.collectors

import android.content.Context
import android.content.pm.PackageManager
import io.bearound.sdk.models.AppPresenceDeclarations
import io.bearound.sdk.models.AppPresenceDetectionMethod
import io.bearound.sdk.models.AppPresenceReason
import io.bearound.sdk.models.AppPresenceResult
import io.bearound.sdk.models.AppPresenceState
import io.bearound.sdk.models.AppPresenceTarget
import io.bearound.sdk.models.AppPresenceTime

/**
 * Single-package lookup. Must throw [PackageManager.NameNotFoundException] when the package
 * is not visible/installed; any other exception means the query itself failed.
 */
internal fun interface AppPresencePackageLookup {
    @Throws(Exception::class)
    fun getPackageInfo(packageName: String)
}

/**
 * Checks the configured targets on Android, one targeted `getPackageInfo(pkg, 0)` per declared
 * package. Never enumerates installed packages and never needs `QUERY_ALL_PACKAGES`.
 *
 * Package visibility (API 30+) silently hides undeclared packages, so a "not found" is only
 * meaningful when the build evidence asset proves the package is declared in the final
 * manifest `<queries>`. Without valid evidence every Android target is
 * `unknown` / `declaration_unverified` and PackageManager is not called at all.
 */
internal class AppPresenceCollector(
    declarationsLoader: () -> AppPresenceDeclarations?,
    private val packageLookup: AppPresencePackageLookup,
    private val wallClock: () -> Long
) {
    /** The evidence asset is read once per collector (once per process in production). */
    private val declarations: AppPresenceDeclarations? by lazy(declarationsLoader)

    private sealed class Outcome {
        object Installed : Outcome()
        object NotFound : Outcome()
        object Failed : Outcome()
    }

    /**
     * Returns one result per target, in the given order. Never throws: a failing target is
     * `unknown` / `query_failed` and the others are still checked. Duplicated package names
     * are looked up once per call.
     */
    fun collect(targets: List<AppPresenceTarget>): List<AppPresenceResult> {
        val lookups = HashMap<String, Outcome>()
        return targets.map { target -> resolve(target, lookups) }
    }

    private fun resolve(target: AppPresenceTarget, lookups: MutableMap<String, Outcome>): AppPresenceResult {
        val packageName = target.androidPackageName
            ?: return unknown(target, AppPresenceReason.UNSUPPORTED_PLATFORM, AppPresenceDetectionMethod.NONE)
        val evidence = declarations
            ?: return unknown(target, AppPresenceReason.DECLARATION_UNVERIFIED)
        if (!evidence.isDeclared(packageName)) {
            return unknown(target, AppPresenceReason.NOT_DECLARED)
        }
        return when (lookups.getOrPut(packageName) { lookup(packageName) }) {
            Outcome.Installed -> known(target, present = true)
            Outcome.NotFound -> known(target, present = false)
            Outcome.Failed -> unknown(target, AppPresenceReason.QUERY_FAILED)
        }
    }

    private fun lookup(packageName: String): Outcome = try {
        packageLookup.getPackageInfo(packageName)
        Outcome.Installed
    } catch (_: PackageManager.NameNotFoundException) {
        Outcome.NotFound
    } catch (_: Exception) {
        // SecurityException, DeadSystemException wrappers, OEM bugs: undetermined, not absent.
        Outcome.Failed
    }

    private fun known(target: AppPresenceTarget, present: Boolean) = AppPresenceResult(
        targetId = target.id,
        state = if (present) AppPresenceState.PRESENT else AppPresenceState.ABSENT,
        present = present,
        reason = null,
        checkedAt = AppPresenceTime.format(wallClock()),
        detectionMethod = AppPresenceDetectionMethod.ANDROID_PACKAGE
    )

    private fun unknown(
        target: AppPresenceTarget,
        reason: AppPresenceReason,
        method: AppPresenceDetectionMethod = AppPresenceDetectionMethod.ANDROID_PACKAGE
    ) = AppPresenceResult(
        targetId = target.id,
        state = AppPresenceState.UNKNOWN,
        present = null,
        reason = reason,
        checkedAt = AppPresenceTime.format(wallClock()),
        detectionMethod = method
    )

    companion object {
        fun create(context: Context): AppPresenceCollector {
            val app = context.applicationContext ?: context
            return AppPresenceCollector(
                declarationsLoader = { readDeclarations(app) },
                packageLookup = { packageName -> app.packageManager.getPackageInfo(packageName, 0) },
                wallClock = System::currentTimeMillis
            )
        }

        /** Reads and validates the build evidence asset against the running package name. */
        internal fun readDeclarations(context: Context): AppPresenceDeclarations? {
            val raw = try {
                context.assets.open(AppPresenceDeclarations.ASSET_NAME).use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (_: Exception) {
                null
            }
            return AppPresenceDeclarations.parse(raw, context.packageName)
        }
    }
}

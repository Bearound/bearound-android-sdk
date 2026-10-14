package io.bearound.bearoundscan.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.bearound.bearoundscan.BuildConfig
import io.bearound.sdk.models.AdaptiveSamplingDiagnostics
import io.bearound.sdk.telemetry.ErrorReporter
import io.bearound.sdk.testing.AdaptiveSamplingBench
import io.bearound.sdk.utilities.DiagnosticsStore
import io.bearound.sdk.utilities.SDKConfigStorage
import org.json.JSONObject
import java.net.URI

/** Initializes explicit local transport before the host Application can restore tracking. */
class AdaptiveBenchBootstrap : ContentProvider() {
    override fun onCreate(): Boolean {
        if (!BuildConfig.ADAPTIVE_BENCH) return true
        val context = requireNotNull(context)
        val uri = URI(BuildConfig.BENCH_ENDPOINT)
        val host = uri.host
        val parts = host?.split('.')
        val octets = parts?.takeIf { it.size == 4 && it.all { part ->
            part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 && part == part.toInt().toString()
        } }?.map { it.toInt() }
        val privateIpv4 = octets?.size == 4 && (octets[0] == 127 || octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))
        require(uri.scheme == "https" && (privateIpv4 || host in listOf("localhost", "::1", "[::1]")) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path in listOf("", "/") && uri.port in 1..65535) { "A local trusted HTTPS fixture endpoint is required" }
        require(BuildConfig.BENCH_TOKEN.matches(Regex("fixture-token-[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) &&
            BuildConfig.BENCH_USER.matches(Regex("fixture-[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) &&
            context.packageName.endsWith(".adaptivebench")) { "An isolated synthetic fixture identity is required" }
        ErrorReporter.setEnabled(false)
        AdaptiveSamplingBench.install(context, BuildConfig.BENCH_ENDPOINT)
        SDKConfigStorage.saveInternalId(context, BuildConfig.BENCH_USER)
        val fingerprint = context.assets.open("adaptive_sampling_bench_build.json").bufferedReader().use { it.readText() }
        val identity = org.json.JSONObject(fingerprint).getString("sourceFingerprint")
        Log.i("AdaptiveBench", "Frozen source fingerprint: $identity; adaptive=${BuildConfig.BENCH_ADAPTIVE}")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val applicationUid = requireNotNull(context).applicationInfo.uid
        return AdaptiveBenchDiagnosticCall.snapshot(method, arg, extras,
            BuildConfig.DEBUG && BuildConfig.ADAPTIVE_BENCH, Binder.getCallingUid(), applicationUid,
            DiagnosticsStore::sampling)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object AdaptiveBenchDiagnosticCall {
    private const val SHELL_UID = 2000

    fun snapshot(method: String, argument: String?, extras: Bundle?, benchEnabled: Boolean,
                 callingUid: Int, applicationUid: Int, readSampling: () -> AdaptiveSamplingDiagnostics): Bundle {
        if (!benchEnabled || (callingUid != applicationUid && callingUid != SHELL_UID)) {
            throw SecurityException("Bench diagnostics are unavailable for this caller")
        }
        require(method == "sampling-diagnostics" && argument == null && (extras == null || extras.isEmpty)) {
            "Only a sampling diagnostic snapshot without inputs is supported"
        }
        val sampling = readSampling()
        val json = JSONObject().apply {
            put("state", sampling.state)
            put("source", sampling.source)
            put("confidence", sampling.confidence)
            put("reason", sampling.reason)
            put("evidenceAgeMs", sampling.evidenceAgeMs ?: JSONObject.NULL)
            put("contextStable", sampling.contextStable)
            put("capabilityStatus", sampling.capabilityStatus)
            put("contextIntervalMs", sampling.contextIntervalMs ?: JSONObject.NULL)
            put("summaryIntervalMs", sampling.summaryIntervalMs ?: JSONObject.NULL)
            put("liveIntervalMs", sampling.liveIntervalMs ?: JSONObject.NULL)
            put("uniqueSamples", sampling.uniqueSamples)
            put("cachedObservations", sampling.cachedObservations)
            put("contextReads", sampling.contextReads)
            put("bucketCount", sampling.bucketCount)
            put("gapCount", sampling.gapCount)
            put("liveAttempts", sampling.liveAttempts)
            put("summaryAttempts", sampling.summaryAttempts)
            put("transitionAttempts", sampling.transitionAttempts)
            put("serializedBytes", sampling.serializedBytes)
            put("failures", sampling.failures)
            put("retryLagMs", sampling.retryLagMs)
            put("archiveOnlyCount", sampling.archiveOnlyCount)
            put("policyDenials", sampling.policyDenials)
            put("clockUncertaintyMs", sampling.clockUncertaintyMs ?: JSONObject.NULL)
            put("mode", sampling.mode)
            put("nextContextReadElapsedMs", sampling.nextContextReadElapsedMs ?: JSONObject.NULL)
            put("nextSummaryElapsedMs", sampling.nextSummaryElapsedMs ?: JSONObject.NULL)
            put("nextLiveAttemptElapsedMs", sampling.nextLiveAttemptElapsedMs ?: JSONObject.NULL)
            put("manifestGapCount", sampling.manifestGapCount)
        }
        return Bundle().apply { putString("json", json.toString()) }
    }
}

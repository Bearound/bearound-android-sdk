package io.bearound.bearoundscan.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.bearound.bearoundscan.BuildConfig
import io.bearound.sdk.telemetry.ErrorReporter
import io.bearound.sdk.testing.AdaptiveSamplingBench
import io.bearound.sdk.utilities.SDKConfigStorage
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
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

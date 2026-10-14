package io.bearound.sdk.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.bearound.sdk.models.SDKConfiguration
import java.net.URI

/** Available only in Debug builds. Configure before constructing or restoring the SDK. */
object AdaptiveSamplingBench {
    private const val PREFERENCES = "bearound_adaptive_sampling_bench"
    private const val ENDPOINT = "fixture_endpoint"

    fun install(context: Context, endpoint: String) {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "A trusted HTTPS fixture endpoint is required" }
        val normalized = endpoint.trimEnd('/')
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(ENDPOINT, normalized).commit()
        SDKConfiguration.endpointResolver = { normalized }
    }

    internal fun restore(context: Context) {
        val endpoint = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ENDPOINT, null) ?: return
        install(context, endpoint)
    }
}

/** Restores the explicit bench destination before host Application initialization. */
class AdaptiveSamplingBenchProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { AdaptiveSamplingBench.restore(it) }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

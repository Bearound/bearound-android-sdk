package io.bearound.sdk.visit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Body of `GET /sdk/places/nearby` (REQ-013, REQ-021, D-14). The list keeps the name
 * `places` for payload compatibility, but every item is an environment (D-23).
 *
 * Only the circle of each geometry is used on device: `GeofencingClient` accepts nothing
 * else, and the ingest decides the environment from the coordinates (design 2.4a).
 */
internal data class PlacesConfig(
    val origin: Coordinate,
    val refreshAfterMeters: Double,
    val maxAgeSeconds: Double,
    val visitDetectionEnabled: Boolean,
    /** Nearest first, as the API sorts them. */
    val places: List<Place>
) {
    data class Coordinate(val lat: Double, val lng: Double)

    data class Place(
        val environmentId: String,
        val distanceMeters: Double,
        /** `point` lat/lng, or the circumscribed circle of a `polygon`. */
        val center: Coordinate,
        val radiusMeters: Double,
        val minDwellMinutes: Int?
    )

    companion object {
        /**
         * Parses the API body. Throws when the envelope is unusable; a single malformed item
         * is skipped instead of throwing away the whole list.
         */
        fun parse(body: String): PlacesConfig {
            val root = JSONObject(body)
            val origin = root.getJSONObject("origin").toCoordinate()
                ?: throw IllegalArgumentException("origin without lat/lng")
            val items = root.optJSONArray("places") ?: JSONArray()
            val places = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.let(::parsePlace)
            }
            return PlacesConfig(
                origin = origin,
                refreshAfterMeters = root.getDouble("refreshAfterMeters"),
                maxAgeSeconds = root.getDouble("maxAgeSeconds"),
                // The API defaults the flag to true (D-13); an absent field keeps that default.
                visitDetectionEnabled = root.optBoolean("visit_detection_enabled", true),
                places = places
            )
        }

        private fun parsePlace(item: JSONObject): Place? = try {
            val geometry = item.getJSONObject("geometry")
            val center = if (geometry.optString("type") == "polygon") {
                geometry.optJSONObject("center")?.toCoordinate()
            } else {
                geometry.toCoordinate() ?: geometry.optJSONObject("center")?.toCoordinate()
            }
            center?.let {
                Place(
                    environmentId = item.getString("environmentId"),
                    distanceMeters = item.optDouble("distanceMeters", 0.0),
                    center = it,
                    radiusMeters = geometry.getDouble("radiusMeters"),
                    minDwellMinutes = if (item.isNull("minDwellMinutes")) null else item.optInt("minDwellMinutes")
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.toCoordinate(): Coordinate? {
            if (!has("lat") || !has("lng") || isNull("lat") || isNull("lng")) return null
            return Coordinate(getDouble("lat"), getDouble("lng"))
        }
    }
}

internal sealed class PlacesFetchResult {
    /** [body] is persisted verbatim, so the cache is exactly what the API said (D-22). */
    data class Updated(val body: String, val config: PlacesConfig, val etag: String?) : PlacesFetchResult()
    object NotModified : PlacesFetchResult()
    data class Failed(val error: Throwable) : PlacesFetchResult()
}

internal fun interface PlacesConfigFetching {
    suspend fun fetch(latitude: Double, longitude: Double, etag: String?): PlacesFetchResult
}

/**
 * `GET {controlHubBaseURL}/sdk/places/nearby?lat=&lng=`, authenticated with the raw business
 * token exactly like `/ingest` (`Authorization: <businessToken>`).
 */
internal class PlacesConfigClient(
    private val baseURL: String,
    private val businessToken: String
) : PlacesConfigFetching {

    companion object {
        private const val TAG = "BeAroundSDK-Visit"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /**
         * The server rounds to 3 decimals (~110 m) anyway; rounding here keeps the finer fix
         * on the device and lets unchanged neighbourhoods hit the ETag.
         */
        fun requestUrl(baseURL: String, latitude: Double, longitude: Double): String =
            String.format(Locale.US, "%s/sdk/places/nearby?lat=%.3f&lng=%.3f", baseURL, latitude, longitude)
    }

    override suspend fun fetch(latitude: Double, longitude: Double, etag: String?): PlacesFetchResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(requestUrl(baseURL, latitude, longitude)).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", businessToken)
                if (!etag.isNullOrEmpty()) connection.setRequestProperty("If-None-Match", etag)
                // Our own ETag logic decides freshness; never answer from an HTTP cache.
                connection.useCaches = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                val status = connection.responseCode
                when {
                    status == HttpURLConnection.HTTP_NOT_MODIFIED -> PlacesFetchResult.NotModified
                    status in 200..299 -> {
                        val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                        PlacesFetchResult.Updated(body, PlacesConfig.parse(body), connection.getHeaderField("ETag"))
                    }
                    else -> {
                        val body = try {
                            BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }.take(512)
                        } catch (e: Exception) {
                            ""
                        }
                        PlacesFetchResult.Failed(io.bearound.sdk.network.HttpException(status, body))
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Places config fetch failed: ${e.message}")
                PlacesFetchResult.Failed(e)
            } finally {
                connection?.disconnect()
            }
        }
}

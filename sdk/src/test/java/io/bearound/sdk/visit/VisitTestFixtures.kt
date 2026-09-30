package io.bearound.sdk.visit

import io.bearound.sdk.models.UserDevice

/** Records what the tracker persists; nothing is ever sent. */
internal class RecordingVisitEventQueue : VisitEventQueue {
    val persisted = mutableListOf<VisitEvent>()
    override fun persist(event: VisitEvent): Boolean = persisted.add(event)
    override suspend fun flush() {}
    override fun discardPending() { persisted.clear() }
}

internal object VisitTestFixtures {
    const val ORIGIN_LAT = -23.561
    const val ORIGIN_LNG = -46.656
    const val ENV_ID = "env-1"
    const val WIFI_ENV_ID = "env-wifi"

    /** Meters along a meridian to degrees of latitude, with the same Earth radius as [Geo]. */
    fun metersToLatDegrees(meters: Double) = Math.toDegrees(meters / 6_371_000.0)

    /** A place that carries the `knownApIds` field the server sends for Wi-Fi visit matching. */
    private const val WIFI_PLACE_JSON = """
            {
              "environmentId": "$WIFI_ENV_ID",
              "distanceMeters": 120,
              "geometry": { "type": "point", "lat": $ORIGIN_LAT, "lng": $ORIGIN_LNG, "radiusMeters": 60 },
              "minDwellMinutes": 5,
              "knownApIds": ["9f3a1c02b7d4e688", "0a1b2c3d4e5f6071"]
            }"""

    fun configBody(
        enabled: Boolean = true,
        refreshAfterMeters: Double = 2500.0,
        maxAgeSeconds: Double = 21600.0,
        minDwellMinutes: Int = 5,
        withWifiPlace: Boolean = false
    ) = """
        {
          "origin": { "lat": $ORIGIN_LAT, "lng": $ORIGIN_LNG },
          "refreshAfterMeters": $refreshAfterMeters,
          "maxAgeSeconds": $maxAgeSeconds,
          "visit_detection_enabled": $enabled,
          "places": [
            {
              "environmentId": "$ENV_ID",
              "businessId": "biz-1",
              "name": "Store",
              "distanceMeters": 0,
              "gpsVisitClass": "street_isolated",
              "geometry": { "type": "point", "lat": $ORIGIN_LAT, "lng": $ORIGIN_LNG, "radiusMeters": 60 },
              "minDwellMinutes": $minDwellMinutes
            },
            {
              "environmentId": "env-2",
              "distanceMeters": 900,
              "geometry": {
                "type": "polygon",
                "rings": [[{ "lat": -23.553, "lng": -46.656 }]],
                "center": { "lat": -23.553, "lng": -46.656 },
                "radiusMeters": 80
              },
              "minDwellMinutes": null
            }${if (withWifiPlace) ",$WIFI_PLACE_JSON" else ""}
          ]
        }
    """.trimIndent()

    fun device() = UserDevice(
        deviceId = "device-1",
        manufacturer = "Google",
        model = "Pixel",
        osVersion = "14",
        timestamp = 0L,
        timezone = "America/Sao_Paulo",
        batteryLevel = 80,
        isCharging = false,
        bluetoothState = "powered_on",
        locationPermission = "authorized_when_in_use",
        notificationsPermission = "authorized",
        networkType = "wifi",
        cellularGeneration = null,
        ramTotalMb = 4096,
        ramAvailableMb = 2048,
        screenWidth = 1080,
        screenHeight = 2400,
        appInForeground = true,
        appUptimeMs = 1_000L,
        coldStart = false,
        lowPowerMode = false,
        locationAccuracy = "full",
        backgroundLocation = false,
        apId = null,
        wifiSSID = null,
        connectionMetered = false,
        connectionExpensive = false,
        deviceName = "Pixel",
        carrierName = null,
        availableStorageMb = null,
        systemLanguage = "pt",
        thermalState = "nominal",
        systemUptimeMs = 1_000L,
        sdkVersion = 34
    )
}

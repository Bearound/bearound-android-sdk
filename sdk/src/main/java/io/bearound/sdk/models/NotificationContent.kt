package io.bearound.sdk.models

/**
 * Dynamic notification content returned by
 * [BeAroundSDKListener.onProvideNotificationContent] to update
 * the foreground service notification when beacons are detected.
 *
 * Use this to show contextual, user-friendly messages instead of
 * a generic "scanning" text. Be creative! Examples:
 *
 * - title = "Store ABC"           , text = "Exclusive offers waiting for you!"
 * - title = "Shopping Center"     , text = "Discover deals on this floor"
 * - title = "Event XYZ"           , text = "Welcome! Check the schedule"
 * - title = "Local Pharmacy"      , text = "Special discounts nearby"
 * - title = "Restaurant Flavor"   , text = "Today's menu is ready!"
 *
 * Return `null` from [onProvideNotificationContent] to keep the default
 * text defined in [ForegroundScanConfig].
 */
data class NotificationContent(
    /** Notification title — e.g., the app or location name. */
    val title: String,
    /** Notification body — e.g., "We found offers for you!" */
    val text: String
)

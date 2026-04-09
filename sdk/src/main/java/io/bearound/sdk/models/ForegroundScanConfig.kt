package io.bearound.sdk.models

/**
 * Configuration for the optional foreground service notification.
 *
 * When enabled via `startScanning(foregroundScanConfig)`, a persistent notification
 * is shown while the SDK scans in background. This keeps the process alive on
 * aggressive OEMs (Xiaomi, Huawei, Samsung) that may kill PendingIntent scans.
 *
 * **Notification title**: defaults to the host app name (resolved at runtime).
 * Pass an empty string `""` to use the app name, or provide a custom title.
 *
 * **Notification text**: defaults to "Encontrando promoções".
 * This text is shown when the service starts. Once beacons are detected,
 * the SDK calls [BeAroundSDKListener.onProvideNotificationContent] so the host
 * app can return contextual text (e.g., "Ofertas disponíveis perto de você!").
 *
 * Examples of creative notification messages:
 * - "Buscando ofertas na sua região"
 * - "Descobrindo novidades por perto"
 * - "Conectando você a experiências próximas"
 * - "Rastreando promoções exclusivas"
 */
data class ForegroundScanConfig(
    val enabled: Boolean = false,
    /** Notification title. Empty string = app name (default). */
    val notificationTitle: String = "",
    /** Notification body text shown while scanning in background. */
    val notificationText: String = "Encontrando promoções",
    val notificationIcon: Int? = null,
    val notificationChannelId: String? = null,
    val notificationChannelName: String = "Serviço de monitoramento da região"
)

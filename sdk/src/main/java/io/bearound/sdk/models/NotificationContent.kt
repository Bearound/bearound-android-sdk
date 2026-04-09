package io.bearound.sdk.models

/**
 * Dynamic notification content returned by
 * [BeAroundSDKListener.onProvideNotificationContent] to update
 * the foreground service notification when beacons are detected.
 *
 * Use this to show contextual, user-friendly messages instead of
 * a generic "scanning" text. Be creative! Examples:
 *
 * - title = "Loja ABC"          , text = "Ofertas exclusivas esperando por você!"
 * - title = "Shopping Center"   , text = "Descubra promoções neste andar"
 * - title = "Evento XYZ"        , text = "Bem-vindo! Confira a programação"
 * - title = "Farmácia Popular"  , text = "Descontos especiais perto de você"
 * - title = "Restaurante Sabor" , text = "Cardápio do dia disponível!"
 *
 * Return `null` from [onProvideNotificationContent] to keep the default
 * text defined in [ForegroundScanConfig].
 */
data class NotificationContent(
    /** Notification title — e.g., the app or location name. */
    val title: String,
    /** Notification body — e.g., "Encontramos promoções para você!" */
    val text: String
)

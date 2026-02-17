package io.bearound.sdk.models

data class ForegroundScanConfig(
    val enabled: Boolean = false,
    val notificationTitle: String = "Monitorando região",
    val notificationText: String = "Verificando região em background",
    val notificationIcon: Int? = null,
    val notificationChannelId: String? = null,
    val notificationChannelName: String = "Serviço de monitoramento da região"
)

package io.bearound.sdk.utilities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Ajuda o app host a manter o processo elegível a acordar em background — o problema
 * real de robustez no Android (Doze + battery managers de OEM), que é o que o "segundo
 * olho" resolve no iOS. Nada aqui envolve location: são só as duas alavancas que de fato
 * aumentam a sobrevivência do scan em background, sem custo de policy do Google Play.
 *
 * 1. Isenção de otimização de bateria — via a TELA de Settings
 *    (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), que NÃO exige a permissão restrita
 *    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (essa dispara revisão do Google Play). O usuário
 *    isenta o app manualmente.
 * 2. Autostart de OEM — deep-link para a tela de "autostart"/"apps protegidos" de
 *    fabricantes agressivos (Xiaomi/MIUI, Huawei, Oppo/Vivo, OnePlus, Samsung…) que matam
 *    PendingIntent/broadcast receivers mesmo no Android 14+.
 */
object BackgroundReliabilityHelper {

    private const val TAG = "BeAroundSDK-Reliability"

    // region Battery optimization (método Settings — sem permissão restrita)

    /**
     * true se o app já está isento da otimização de bateria (ou se a versão do Android é
     * anterior ao Doze, onde não se aplica).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Abre a tela de Settings de otimização de bateria para o usuário isentar o app.
     * Usa ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (lista geral, sem permissão especial).
     * @return true se conseguiu abrir a tela.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open battery optimization settings", e)
            false
        }
    }

    // endregion

    // region OEM autostart deep-links

    /**
     * Pares (package, activity) das telas de autostart/apps-protegidos por OEM. Ordenados
     * do mais específico ao mais genérico por fabricante. Só é aberto o que resolver contra
     * o PackageManager do device, então listar candidatos de outros OEMs é inofensivo.
     */
    private val autostartComponents: List<Pair<String, String>> = listOf(
        // Xiaomi / Redmi / POCO (MIUI / HyperOS)
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor (EMUI / MagicOS)
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / Realme (ColorOS)
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo / iQOO (Funtouch / OriginOS)
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // OnePlus (OxygenOS)
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Letv
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
        // Samsung: DELIBERADAMENTE fora daqui. A tela de "apps que nunca dormem"
        // (AppPowerManagementActivity, com.samsung.android.lool) exige a permissão de
        // sistema READ_SEARCH_INDEXABLES para ser aberta por deep-link — inacessível a
        // apps de terceiros (verificado no A55/One UI, 2026-07-01: Permission Denial).
        // No Samsung o caminho correto é o [openBatteryOptimizationSettings] genérico.
    )

    /**
     * true se o device é de um OEM com tela de autostart conhecida e resolvível — ou seja,
     * se [openManufacturerAutostartSettings] tem chance de funcionar.
     */
    fun isAutostartManageable(context: Context): Boolean = resolveAutostartIntent(context) != null

    /**
     * Abre a tela de autostart/apps-protegidos do fabricante, se houver uma conhecida.
     * @return true se conseguiu abrir; false em Android stock (Pixel) ou OEM não mapeado.
     */
    fun openManufacturerAutostartSettings(context: Context): Boolean {
        val intent = resolveAutostartIntent(context) ?: run {
            Log.d(TAG, "No known autostart screen for manufacturer=${Build.MANUFACTURER}")
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open manufacturer autostart settings", e)
            false
        }
    }

    private fun resolveAutostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        for ((pkg, cls) in autostartComponents) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return intent
            }
        }
        return null
    }

    // endregion
}

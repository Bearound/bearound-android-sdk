package io.bearound.sdk

import android.content.Context
import org.json.JSONObject

/**
 * Collects SDK information for the BeAround SDK payload.
 */
class SdkInfoCollector(private val context: Context) {

    /**
     * Collects SDK information and returns it as a JSONObject.
     */
    fun collectSdkInfo(): JSONObject {
        return JSONObject().apply {
            put("version", io.bearound.sdk.BuildConfig.SDK_VERSION)
            put("platform", "android")
            put("appId", context.packageName)
            put("build", getBuildNumber())
        }
    }

    /**
     * Gets the app build number (version code).
     */
    private fun getBuildNumber(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }
}


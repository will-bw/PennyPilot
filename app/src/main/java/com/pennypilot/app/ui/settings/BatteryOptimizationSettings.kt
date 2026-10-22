package com.pennypilot.app.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

enum class BatterySettingsDestination { OPTIMIZATION_LIST, APP_DETAILS, UNAVAILABLE }

/** 仅导航到用户可操作的系统设置，不直接请求电池优化豁免。 */
object BatteryOptimizationSettings {
    fun open(context: Context): BatterySettingsDestination {
        if (launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return BatterySettingsDestination.OPTIMIZATION_LIST
        }
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        return if (launch(context, details)) BatterySettingsDestination.APP_DETAILS
        else BatterySettingsDestination.UNAVAILABLE
    }

    /** null 代表无法读取，不能把未知状态展示成已豁免或正在优化。 */
    fun isExempt(context: Context): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    private fun launch(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

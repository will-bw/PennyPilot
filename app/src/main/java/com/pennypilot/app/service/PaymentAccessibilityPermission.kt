package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** 系统保存的用户授权与服务当前是否连接是两个状态，不能用连接列表代替授权记录。 */
object PaymentAccessibilityPermission {
    fun isGranted(context: Context): Boolean {
        val component = ComponentName(context, PaymentAccessibilityService::class.java)
        val configured = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        if (configured != null) {
            // 接受系统保存的完整/缩写组件名，并按组件精确匹配，避免误认其他应用。
            // 空字符串表示授权已关闭，不回退到可能尚未更新的连接列表。
            return configured.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
        // 部分系统不提供该设置值；此时才使用系统服务列表作为备用依据。
        return runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
                    val service = it.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) == component
                } == true
        }.getOrDefault(false)
    }
}

package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaymentAccessibilityPermissionTest {
    private lateinit var context: Context
    private lateinit var component: ComponentName
    private lateinit var manager: AccessibilityManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        component = ComponentName(context, PaymentAccessibilityService::class.java)
        manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabledAccessibilityServiceList(emptyList())
        setConfigured(null)
    }

    @Test fun authorizationSurvivesProcessRestartBeforeServiceReconnects() {
        val saved = component.flattenToString()
        setConfigured(saved)
        val restartedMonitor = CaptureServiceMonitor()

        assertTrue(PaymentAccessibilityPermission.isGranted(context))
        assertFalse(restartedMonitor.health.value.isAccessibilityBound)
        // 读取状态不能改写系统授权或宣称服务已经恢复。
        assertEquals(saved, Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun acceptsShortComponentAmongOtherServices() {
        setConfigured("other.package/.Service:${component.flattenToShortString()}")
        assertTrue(PaymentAccessibilityPermission.isGranted(context))
    }

    @Test fun otherAndMalformedComponentsDoNotGrantPermission() {
        setConfigured("invalid:other.package/.Service:${component.flattenToString()}Extra")
        assertFalse(PaymentAccessibilityPermission.isGranted(context))
    }

    @Test fun disablingInSettingsOverridesAStaleManagerList() {
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(serviceInfo()))
        setConfigured("")
        assertFalse(PaymentAccessibilityPermission.isGranted(context))
    }

    @Test fun removingOnlyOurComponentRevokesPermission() {
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(serviceInfo()))
        setConfigured("other.package/.Service")
        assertFalse(PaymentAccessibilityPermission.isGranted(context))
    }

    @Test fun missingSettingFallsBackToSystemServiceList() {
        assertFalse(PaymentAccessibilityPermission.isGranted(context))
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(serviceInfo()))
        assertTrue(PaymentAccessibilityPermission.isGranted(context))
    }

    private fun setConfigured(value: String?) {
        Settings.Secure.putString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    private fun serviceInfo(): AccessibilityServiceInfo {
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        }
        return AccessibilityServiceInfo().also {
            ReflectionHelpers.setField(it, "mResolveInfo", resolveInfo)
        }
    }
}

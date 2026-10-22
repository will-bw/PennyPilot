package com.pennypilot.app.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatteryOptimizationSettingsTest {
    private class LaunchContext(
        base: Context,
        private val failure: (Intent) -> RuntimeException? = { null }
    ) : ContextWrapper(base) {
        val attempts = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            attempts += intent
            failure(intent)?.let { throw it }
        }
    }

    @Test
    fun `opens general optimization list without a package exemption request`() {
        val context = LaunchContext(ApplicationProvider.getApplicationContext())
        assertEquals(BatterySettingsDestination.OPTIMIZATION_LIST, BatteryOptimizationSettings.open(context))
        assertEquals(listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), context.attempts.map { it.action })
        assertNull(context.attempts.single().data)
    }

    @Test
    fun `missing optimization screen falls back to this applications details`() {
        val context = LaunchContext(ApplicationProvider.getApplicationContext()) {
            if (it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) ActivityNotFoundException() else null
        }
        assertEquals(BatterySettingsDestination.APP_DETAILS, BatteryOptimizationSettings.open(context))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.attempts.last().action)
        assertEquals("package:${context.packageName}", context.attempts.last().data.toString())
    }

    @Test
    fun `restricted optimization screen can use application details`() {
        val context = LaunchContext(ApplicationProvider.getApplicationContext()) {
            if (it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) SecurityException() else null
        }
        assertEquals(BatterySettingsDestination.APP_DETAILS, BatteryOptimizationSettings.open(context))
    }

    @Test
    fun `unavailable screens return a failure for manual settings guidance`() {
        val context = LaunchContext(ApplicationProvider.getApplicationContext()) { ActivityNotFoundException() }
        assertEquals(BatterySettingsDestination.UNAVAILABLE, BatteryOptimizationSettings.open(context))
        assertEquals(2, context.attempts.size)
    }

    @Test
    fun `exemption state is read from the system on every refresh`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        assertEquals(false, BatteryOptimizationSettings.isExempt(context))
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        assertEquals(true, BatteryOptimizationSettings.isExempt(context))
    }
}

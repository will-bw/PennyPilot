package com.pennypilot.app.data.asr

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechRecognizerManagerTest {
    @Test fun `unavailable recognition service reports an actionable error`() {
        val manager = SpeechRecognizerManager(ApplicationProvider.getApplicationContext())
        var failure: String? = null
        manager.startListening({ fail("No recognition service") }, { failure = it })
        assertNotNull(failure)
        assertFalse(manager.isListening.value)
        manager.destroy()
    }

    @Test fun `recognition error and empty results both terminate recording with feedback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val service = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "test.speech"; name = "TestRecognizer" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service)
        val manager = SpeechRecognizerManager(app)
        var failure: String? = null
        manager.startListening({ fail("Error must not produce text") }, { failure = it })
        ShadowLooper.idleMainLooper()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(failure.orEmpty().contains("网络"))
        assertFalse(manager.isListening.value)
        failure = null
        manager.startListening({ fail("Empty result must not produce text") }, { failure = it })
        ShadowLooper.idleMainLooper()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle())
        assertTrue(failure.orEmpty().contains("未识别"))
        assertFalse(manager.isListening.value)
        manager.destroy()
    }
}

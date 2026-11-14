package com.pennypilot.app.data.recognition

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ModelRole
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.settings.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureAuthorizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context)
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private suspend fun id(packageName: String = wechat, page: Boolean = true, image: Boolean = false) =
        preferences.captureAuthorizationId(packageName, page, image)

    @Test fun `closing and reopening cloud images invalidates only that source and persists`() = runBlocking {
        preferences.setScreenCloudVisionEnabled(true)
        val image = id(image = true)
        val text = id()
        val notice = id(page = false)
        preferences.setScreenCloudVisionEnabled(false)
        val revoked = id(image = true)
        assertNotEquals(image, revoked)
        preferences.setScreenCloudVisionEnabled(false)
        preferences.setScreenCloudVisionEnabled(true)
        assertEquals(revoked, id(image = true))
        assertEquals(text, id())
        assertEquals(notice, id(page = false))
        assertEquals(revoked, AppPreferences(context).captureAuthorizationId(wechat, page = true, image = true))
    }

    @Test fun `disabling one app invalidates its material without cancelling other apps`() = runBlocking {
        preferences.setCaptureAppPackages(PaymentAppRegistry.packages)
        val page = id()
        val notice = id(page = false)
        val other = id(alipay)
        preferences.setCaptureAppPackages(PaymentAppRegistry.packages - wechat)
        val revoked = id()
        preferences.setCaptureAppPackages(PaymentAppRegistry.packages - wechat)
        preferences.setCaptureAppPackages(PaymentAppRegistry.packages)
        assertNotEquals(page, revoked)
        assertEquals(revoked, id())
        assertNotEquals(notice, id(page = false))
        assertEquals(other, id(alipay))
    }

    @Test fun `capture revocation covers both page channels but keeps notification grant independent`() = runBlocking {
        preferences.setScreenCaptureEnabled(true)
        preferences.setNotificationCaptureEnabled(true)
        val text = id()
        val image = id(image = true)
        val notice = id(page = false)
        preferences.setScreenCaptureEnabled(false)
        preferences.setScreenCaptureEnabled(true)
        assertNotEquals(text, id())
        assertNotEquals(image, id(image = true))
        assertEquals(notice, id(page = false))
        val currentPage = id()
        preferences.setNotificationCaptureEnabled(false)
        val revokedNotice = id(page = false)
        preferences.setNotificationCaptureEnabled(false)
        preferences.setNotificationCaptureEnabled(true)
        assertNotEquals(notice, revokedNotice)
        assertEquals(revokedNotice, id(page = false))
        assertEquals(currentPage, id())
    }

    @Test fun `closing already disabled cloud grants does not invalidate unrelated pending work`() = runBlocking {
        preferences.setScreenCloudParsingEnabled(false)
        preferences.setScreenCloudVisionEnabled(false)
        preferences.setNotificationCloudParsingEnabled(false)
        val page = id()
        val image = id(image = true)
        val notice = id(page = false)
        preferences.setScreenCloudParsingEnabled(false)
        preferences.setScreenCloudVisionEnabled(false)
        preferences.setNotificationCloudParsingEnabled(false)
        assertEquals(page, id())
        assertEquals(image, id(image = true))
        assertEquals(notice, id(page = false))
    }

    @Test fun `queued receipt cannot upload after cloud grant was turned off then back on offline`() = runBlocking {
        preferences.setScreenCaptureEnabled(true)
        preferences.setScreenCloudParsingEnabled(true)
        val file = File.createTempFile("capture-authorization", ".enc").apply { delete(); deleteOnExit() }
        val store = RecognitionTaskStore(file, object : TaskCipher {
            override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 63).toByte() }.toByteArray()
            override fun decrypt(bytes: ByteArray) = encrypt(bytes)
        })
        try {
            val task = RecognitionTask("old", "event", wechat, "支付成功\n¥18.00", System.currentTimeMillis(),
                0, "A", "ACCESSIBILITY", authorizationId = id())
            store.enqueue(task)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setScreenCloudParsingEnabled(true)
            var calls = 0
            val client = object : LLMClient {
                override suspend fun operationIdentity(role: ModelRole) = "A"
                override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String { calls++; error("revoked data") }
                override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("unexpected tools")
            }
            val runner = RecognitionTaskRunner(store, client,
                isAllowed = { preferences.screenCloudParsingEnabled.first() && it.authorizationId == id() },
                commit = { _, _, _, _ -> fail("revoked task must not commit"); false }, report = {})
            assertTrue(runner.run(task.id))
            assertEquals(0, calls)
            assertNull(store.get(task.id))
        } finally { store.clear() }
    }
}

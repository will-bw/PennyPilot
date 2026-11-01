package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import com.pennypilot.app.data.parser.NotificationParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationContentExtractorTest {
    private fun sbn(notification: Notification, packageName: String = "com.sankuai.meituan") = StatusBarNotification(
        packageName, packageName, 1, null, 1000, 0, 0,
        notification, Process.myUserHandle(), 1000L)

    private suspend fun parse(notification: Notification, packageName: String = "com.sankuai.meituan") =
        requireNotNull(NotificationContentExtractor.extract(sbn(notification, packageName))).let { input ->
            NotificationParser.parse(input.packageName, input.title, input.content, emptyList(), false) {
                error("通知凭证应在本地解析")
            }
        }

    @Test fun `expanded text takes priority over collapsed amount and inbox lines`() {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "支付成功")
            extras.putCharSequence(Notification.EXTRA_TEXT, "订单原价30元")
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "实付20元，优惠10元")
            extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("实付20元"))
        }
        assertEquals("实付20元，优惠10元", NotificationContentExtractor.extract(sbn(notification))?.content)
    }

    @Test fun `expanded title contributes completion evidence without replacing the source title`() = runTest {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "美团")
            extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功")
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "实付18元")
            extras.putCharSequence(Notification.EXTRA_TEXT, "订单原价30元")
        }
        val captured = NotificationContentExtractor.extract(sbn(notification))!!
        assertEquals("美团", captured.title)
        assertEquals("支付成功\n实付18元", captured.content)
        val result = parse(notification)
        assertEquals(1800L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
    }

    @Test fun `expanded title supplies a missing title and is not duplicated in the body`() = runTest {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功")
            extras.putCharSequence(Notification.EXTRA_TEXT, "实付18元")
        }
        assertEquals("支付成功", NotificationContentExtractor.extract(sbn(notification))?.title)
        assertEquals("实付18元", NotificationContentExtractor.extract(sbn(notification))?.content)
        assertEquals(1800L, parse(notification)?.amountCents)

        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "美团")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "支付成功\n实付18元")
        assertEquals("支付成功\n实付18元", NotificationContentExtractor.extract(sbn(notification))?.content)
    }

    @Test fun `expanded failure and conflicting amounts stay visible to receipt validation`() = runTest {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "美团")
            extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付失败")
            extras.putCharSequence(Notification.EXTRA_TEXT, "支付成功，实付18元")
        }
        assertNull(parse(notification))
        notification.extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功，实付28元")
        assertNull(parse(notification))
    }

    @Test fun `expanded payment text cannot bypass an untrusted chat source title`() = runTest {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "张三")
            extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功")
            extras.putCharSequence(Notification.EXTRA_TEXT, "实付18元")
        }
        assertNull(parse(notification, "com.tencent.mm"))
    }

    @Test fun `title only notifications reach the parser and empty notifications are excluded`() = runTest {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "支付成功18.00元")
        }
        assertEquals(1800L, parse(notification, "com.eg.android.AlipayGphone")?.amountCents)
        assertEquals("", NotificationContentExtractor.extract(sbn(notification))?.content)

        notification.extras.remove(Notification.EXTRA_TITLE)
        notification.extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功18.00元")
        assertEquals(1800L, parse(notification, "com.eg.android.AlipayGphone")?.amountCents)

        notification.extras.remove(Notification.EXTRA_TITLE_BIG)
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "支付宝")
        val titleOnly = NotificationContentExtractor.extract(sbn(notification, "com.eg.android.AlipayGphone"))!!
        assertEquals("支付宝", titleOnly.title)
        assertEquals("", titleOnly.content)
        assertNull(parse(notification, "com.eg.android.AlipayGphone"))
        assertNull(NotificationContentExtractor.extract(sbn(Notification())))
    }

    @Test fun `inbox lines are available without text but group summaries are excluded`() {
        val notification = Notification().apply {
            extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("支付成功", "实付20元"))
        }
        assertEquals("支付成功\n实付20元", NotificationContentExtractor.extract(sbn(notification))?.content)
        notification.flags = Notification.FLAG_GROUP_SUMMARY
        assertNull(NotificationContentExtractor.extract(sbn(notification)))
    }

    @Test fun `oversized text is rejected not truncated`() {
        val notification = Notification().apply { extras.putCharSequence(Notification.EXTRA_TEXT, "付款18元" + "x".repeat(8192)) }
        assertNull(NotificationContentExtractor.extract(sbn(notification)))
        notification.extras.remove(Notification.EXTRA_TEXT)
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "美团")
        notification.extras.putCharSequence(Notification.EXTRA_TITLE_BIG, "支付成功" + "x".repeat(8192))
        assertNull(NotificationContentExtractor.extract(sbn(notification)))
    }

    @Test fun `reconnect recovery excludes old and future notifications`() {
        assertTrue(NotificationContentExtractor.shouldRecover(400_000, 1_000_000))
        assertFalse(NotificationContentExtractor.shouldRecover(399_999, 1_000_000))
        assertFalse(NotificationContentExtractor.shouldRecover(1_000_001, 1_000_000))
    }

    @Test fun `reused notification key with a fresh issued time is not an update identity`() {
        val first = CapturedNotification("com.tencent.mm", "same", 1000, "微信支付", "付款18元", 900)
        assertEquals(first.updateIdentity, first.copy(postedAt = 1100).updateIdentity)
        assertNotEquals(first.updateIdentity, first.copy(issuedAt = 1000).updateIdentity)
        assertNull(first.copy(issuedAt = 0).updateIdentity)
    }
}

package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationContentExtractorTest {
    private fun sbn(notification: Notification) = StatusBarNotification(
        "com.sankuai.meituan", "com.sankuai.meituan", 1, null, 1000, 0, 0,
        notification, Process.myUserHandle(), 1000L)

    @Test fun `expanded text takes priority over collapsed amount and inbox lines`() {
        val notification = Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "支付成功")
            extras.putCharSequence(Notification.EXTRA_TEXT, "订单原价30元")
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "实付20元，优惠10元")
            extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("实付20元"))
        }
        assertEquals("实付20元，优惠10元", NotificationContentExtractor.extract(sbn(notification))?.content)
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

package com.pennypilot.app.service

import org.junit.Assert.*
import org.junit.Test

class CaptureEventIdentityTest {
    @Test
    fun `independent notifications are not combined by merchant or amount`() {
        val first = CaptureEventIdentity.key("wechat", "key-1", 1000)
        assertNotEquals(first, CaptureEventIdentity.key("wechat", "key-2", 1000))
        assertNotEquals(first, CaptureEventIdentity.key("wechat", "key-1", 1001))
        assertNotEquals(first, CaptureEventIdentity.key("alipay", "key-1", 1000))
    }

    @Test fun `payload changes distinguish notifications retaining the same timestamp`() {
        val first = CaptureEventIdentity.key("wechat", "key-1", 1000, "支付成功18元")
        assertNotEquals(first, CaptureEventIdentity.key("wechat", "key-1", 1000, "支付成功20元"))
        assertEquals(first, CaptureEventIdentity.key("wechat", "key-1", 1000, "  支付成功18元  "))
        assertEquals(CaptureEventIdentity.key("wechat", "key-1", 1000),
            CaptureEventIdentity.legacyNotificationKey(first))
    }

    @Test fun `page identities retain a session boundary for independent visits`() {
        val first = CaptureEventIdentity.pageKey("meituan", "session-1", "支付成功18元")
        assertEquals(first, CaptureEventIdentity.pageKey("meituan", "session-1", "支付成功18元"))
        assertNotEquals(first, CaptureEventIdentity.pageKey("meituan", "session-2", "支付成功18元"))
    }

    @Test fun `only complete order identifiers become strong evidence`() {
        assertEquals("mt202610230001", CaptureEventIdentity.reliableOrderId("mt202610230001"))
        assertNotEquals(CaptureEventIdentity.reliableOrderId("mt202610230001"),
            CaptureEventIdentity.reliableOrderId("MT202610230001"))
        listOf(null, "1234", "2026****0001", "订单未支付", "", "1234 5678", "unavailable").forEach {
            assertNull(CaptureEventIdentity.reliableOrderId(it))
        }
    }

    @Test fun `order domain groups application families but not independent payment tools`() {
        assertEquals(CaptureEventIdentity.domainFor("com.sankuai.meituan"),
            CaptureEventIdentity.domainFor("com.sankuai.meituan.takeoutnew"))
        assertEquals(CaptureEventIdentity.domainFor("com.ss.android.ugc.aweme"),
            CaptureEventIdentity.domainFor("com.ss.android.ugc.aweme.lite"))
        assertNotEquals(CaptureEventIdentity.domainFor("com.eg.android.AlipayGphone"),
            CaptureEventIdentity.domainFor("com.sankuai.meituan"))
        assertNotEquals(CaptureEventIdentity.domainFor("com.eg.android.AlipayGphone"),
            CaptureEventIdentity.domainFor("com.tencent.mm"))
    }
}

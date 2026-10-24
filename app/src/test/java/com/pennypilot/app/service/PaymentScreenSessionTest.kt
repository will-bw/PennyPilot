package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import org.junit.Assert.*
import org.junit.Test

class PaymentScreenSessionTest {
    private val payment = NotificationParseResult(1800, "麦当劳", "EXPENSE")
    private val pkg = "com.sankuai.meituan"

    @Test fun `redraws reuse identity and handled result is not submitted again`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment, 1000)!!
        assertEquals(key, session.observe(pkg, 1, payment, 1100))
        session.markHandled(key)
        assertNull(session.observe(pkg, 1, payment, 3000))
    }

    @Test fun `failed submission can retry with same event key`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment, 1000)
        assertEquals(key, session.observe(pkg, 1, payment, 3000))
    }

    @Test fun `independent same amount payment after leaving result gets new identity`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment, 1000)!!
        session.markHandled(key)
        session.observe(pkg, 1, null, 2000)
        assertNotEquals(key, session.observe(pkg, 1, payment, 4000))
    }

    @Test fun `brief loading state does not generate duplicate and changed order remains separate`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment.copy(orderId = "20261023000001"), 1000)!!
        session.markHandled(key)
        session.observe(pkg, 1, null, 2000)
        assertNull(session.observe(pkg, 1, payment.copy(orderId = "20261023000001"), 2100))
        assertNotEquals(key, session.observe(pkg, 1, payment.copy(orderId = "20261023000002"), 2200))
    }

    @Test fun `late merchant and order details do not create a second payment`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment.copy(merchant = null), 1000)!!
        session.markHandled(key)
        assertEquals(key, session.observe(pkg, 1, payment, 1500))
        session.markHandled(key)
        assertEquals(key, session.observe(pkg, 1, payment.copy(orderId = "202610240001"), 2000))
        session.markHandled(key)
        assertNull(session.observe(pkg, 1, payment.copy(merchant = null), 3000))
        assertNotEquals(key, session.observe(pkg, 1, payment.copy(orderId = "202610240002"), 4000))
    }

    @Test fun `enriched evidence retains known fields across incomplete redraw`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment.copy(orderId = "202610240001"), 1000)!!
        session.markHandled(key)
        assertNull(session.observe(pkg, 1, payment.copy(merchant = null), 1500))
        assertEquals("202610240001", session.evidence(key)?.orderId)
        assertEquals("麦当劳", session.evidence(key)?.merchant)
    }

    @Test fun `model candidate and later rule receipt share one identity`() {
        val session = PaymentScreenSession()
        val key = session.observeCandidate(pkg, 1, "本次付款成功\n￥18.00\n完成", 1000)!!
        session.markHandled(key)
        assertNull(session.observeCandidate(pkg, 1, "本次付款成功\n￥18.00\n完成", 1100))
        assertEquals(key, session.observe(pkg, 1, payment, 1200))
        session.markHandled(key)
        assertNull(session.observeCandidate(pkg, 1, "本次付款成功\n￥18.00\n完成", 1300))
    }

    @Test fun `changed model evidence resubmits once under the same logical identity`() {
        val session = PaymentScreenSession()
        val key = session.observeCandidate(pkg, 1, "本次付款成功\n￥18.00\n完成", 1000)!!
        session.markHandled(key)
        val richer = "本次付款成功\n￥18.00\n收款方 小明\n完成"
        assertEquals(key, session.observeCandidate(pkg, 1, richer, 1100))
        session.markHandled(key)
        assertNull(session.observeCandidate(pkg, 1, richer, 1200))
    }

    @Test fun `input boundary creates a new same amount transfer without an artificial delay`() {
        val session = PaymentScreenSession()
        val first = session.observe(pkg, 1, payment, 1000)!!
        session.markHandled(first)
        session.onInputPage(pkg, 1, 1050)
        assertNotEquals(first, session.observe(pkg, 1, payment, 1100))
    }

    @Test fun `separate windows do not erase each others handled identity`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment, 1000)!!
        session.markHandled(key)
        val other = session.observe("com.tencent.mm", 2, payment, 1100)!!
        session.markHandled(other)
        assertNotEquals(key, other)
        assertNull(session.observe(pkg, 1, payment, 1200))
    }

    @Test fun `a long stable result does not become a fresh transaction when ttl passes`() {
        val session = PaymentScreenSession()
        val key = session.observe(pkg, 1, payment, 1000)!!
        session.markHandled(key)
        assertNull(session.observe(pkg, 1, payment, 20 * 60 * 1000L))
    }

}

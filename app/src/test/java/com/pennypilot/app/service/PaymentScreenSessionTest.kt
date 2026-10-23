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
}

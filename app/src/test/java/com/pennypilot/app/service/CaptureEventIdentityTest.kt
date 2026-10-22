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
}

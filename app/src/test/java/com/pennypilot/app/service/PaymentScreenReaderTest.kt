package com.pennypilot.app.service

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class PaymentScreenReaderTest {
    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.sankuai.meituan"
        isVisibleToUser = true
        text = "支付成功 实付18.00元"
    }

    @Test fun `visible readonly text is available only to matching app`() {
        val node = node()
        assertEquals("支付成功 实付18.00元", PaymentScreenReader.read(node, "com.sankuai.meituan"))
        assertNull(PaymentScreenReader.read(node, "com.tencent.mm"))
    }

    @Test fun `password input editable and invisible nodes never expose their text`() {
        val node = node()
        node.isPassword = true
        assertNull(PaymentScreenReader.read(node, "com.sankuai.meituan"))
        node.isPassword = false
        node.isEditable = true
        assertNull(PaymentScreenReader.read(node, "com.sankuai.meituan"))
        node.isEditable = false
        node.isVisibleToUser = false
        assertNull(PaymentScreenReader.read(node, "com.sankuai.meituan"))
    }
}

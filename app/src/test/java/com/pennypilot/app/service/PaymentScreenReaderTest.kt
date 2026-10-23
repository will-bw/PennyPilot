package com.pennypilot.app.service

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test fun `text and content description are both preserved without duplicating same label`() {
        val node = node().apply { text = "支付成功"; contentDescription = "￥18.00" }
        assertEquals("支付成功\n￥18.00", PaymentScreenReader.read(node, "com.sankuai.meituan"))
        node.contentDescription = "支付成功"
        assertEquals("支付成功", PaymentScreenReader.read(node, "com.sankuai.meituan"))
    }

    @Test fun `chat with editable child rejects even complete payment text`() {
        val root = node().apply { text = "支付成功\n￥18.00" }
        shadowOf(root).addChild(node().apply { text = "发送消息"; isEditable = true })
        assertNull(PaymentScreenReader.read(root, "com.sankuai.meituan"))
    }

    @Test fun `invisible input is ignored but visible password page is rejected`() {
        val root = node().apply { text = "支付成功\n￥18.00" }
        shadowOf(root).addChild(node().apply { text = "密码"; isPassword = true; isVisibleToUser = false })
        assertEquals("支付成功\n￥18.00", PaymentScreenReader.read(root, "com.sankuai.meituan"))
    }

    @Test fun `oversized page cannot hide failed state after truncation`() {
        val root = node().apply { text = "支付成功\n￥18.00" }
        repeat(251) { shadowOf(root).addChild(node().apply { text = "正文" }) }
        shadowOf(root).addChild(node().apply { text = "支付失败" })
        assertNull(PaymentScreenReader.read(root, "com.sankuai.meituan"))
    }

}

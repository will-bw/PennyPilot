package com.pennypilot.app.service

import android.app.Application
import android.graphics.Rect
import com.pennypilot.app.data.parser.PaymentScreenNode
import com.pennypilot.app.data.parser.PaymentScreenStructure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaymentVisualRegionTest {
    @Test fun `scoped receipt bounds use the application window offset`() {
        val result = PaymentVisualRegion.bounds(null, 1000, 1800, windowRelative = true,
            windowBounds = listOf(0, 100, 1000, 1900), receiptBounds = listOf(50, 200, 950, 1200))
        assertEquals(Rect(50, 100, 950, 1100), result)
    }

    @Test fun `display screenshot cannot include pixels outside verified window bounds`() {
        assertEquals(Rect(30, 100, 970, 1900), PaymentVisualRegion.windowBounds(listOf(30, 100, 970, 1900), 1000, 2000, false))
        assertNull(PaymentVisualRegion.windowBounds(listOf(-1, 100, 970, 1900), 1000, 2000, false))
        assertNull(PaymentVisualRegion.windowBounds(emptyList(), 1000, 2000, false))
    }

    @Test fun `crop follows visible evidence and excludes hidden unrelated container bounds`() {
        val nodes = listOf(
            PaymentScreenNode(1, null, bounds = listOf(0, 0, 1080, 2400)),
            PaymentScreenNode(2, 1, text = "转账成功", bounds = listOf(100, 300, 900, 400)),
            PaymentScreenNode(3, 1, text = "￥18.00", bounds = listOf(200, 450, 800, 600)),
            PaymentScreenNode(4, 1, text = "hidden", bounds = listOf(0, 0, 1080, 2400), visible = false))
        assertEquals(Rect(100, 300, 900, 600), PaymentVisualRegion.bounds(PaymentScreenStructure(nodes), 1080, 2400))
    }
    @Test fun `missing or stale bounds cannot justify a screenshot region`() {
        assertNull(PaymentVisualRegion.bounds(null, 1080, 2400))
        val nodes = listOf(PaymentScreenNode(1, null, text = "转账成功", bounds = listOf(0, 0, 0, 0)),
            PaymentScreenNode(2, 1, text = "￥18.00", bounds = listOf(0, 0, 2400, 1080)))
        assertNull(PaymentVisualRegion.bounds(PaymentScreenStructure(nodes), 1080, 2400))
    }

    @Test fun `verified interior result panel includes visible image details without expanding to root`() {
        val nodes = listOf(
            PaymentScreenNode(1, null, bounds = listOf(0, 0, 1080, 2400)),
            PaymentScreenNode(2, 1, bounds = listOf(80, 250, 1000, 1500)),
            PaymentScreenNode(3, 2, text = "支付成功", bounds = listOf(100, 300, 900, 400)),
            PaymentScreenNode(4, 2, text = "￥18.00", bounds = listOf(200, 450, 800, 600)),
            PaymentScreenNode(5, 2, className = "android.widget.ImageView", bounds = listOf(100, 700, 900, 1200)),
            PaymentScreenNode(6, 1, bounds = listOf(0, 1600, 1080, 2300)))
        assertEquals(Rect(80, 250, 1000, 1500), PaymentVisualRegion.bounds(
            PaymentScreenStructure(nodes), 1080, 2400, includePanel = true))
        assertEquals(Rect(100, 300, 900, 600), PaymentVisualRegion.bounds(PaymentScreenStructure(nodes), 1080, 2400))
    }

    @Test fun `root and hidden container cannot justify detail expansion`() {
        val nodes = listOf(
            PaymentScreenNode(1, null, bounds = listOf(0, 0, 1080, 2400)),
            PaymentScreenNode(2, 1, bounds = listOf(80, 250, 1000, 1500), visible = false),
            PaymentScreenNode(3, 2, text = "支付成功", bounds = listOf(100, 300, 900, 400)),
            PaymentScreenNode(4, 2, text = "￥18.00", bounds = listOf(200, 450, 800, 600)))
        assertEquals(Rect(100, 300, 900, 600), PaymentVisualRegion.bounds(
            PaymentScreenStructure(nodes), 1080, 2400, includePanel = true))
    }

    @Test fun `window screenshot translates absolute node coordinates inside that window`() {
        val nodes = listOf(
            PaymentScreenNode(1, null, bounds = listOf(100, 200, 900, 1400)),
            PaymentScreenNode(2, 1, text = "支付成功", bounds = listOf(200, 300, 800, 400)),
            PaymentScreenNode(3, 1, text = "￥18.00", bounds = listOf(300, 450, 700, 600)))
        assertEquals(Rect(100, 100, 700, 400), PaymentVisualRegion.bounds(
            PaymentScreenStructure(nodes), 800, 1200, includePanel = true, windowRelative = true))
    }
}

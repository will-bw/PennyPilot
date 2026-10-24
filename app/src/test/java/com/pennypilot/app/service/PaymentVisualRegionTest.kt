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
}

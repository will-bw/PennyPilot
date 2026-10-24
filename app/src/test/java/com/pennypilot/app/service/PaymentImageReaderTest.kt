package com.pennypilot.app.service

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentImageReaderTest {
    private fun image(): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        for (x in 10 until 150) {
            for (y in 10 until 30) setPixel(x, y, Color.RED) // completion
            for (y in 60 until 80) setPixel(x, y, Color.GREEN) // paid amount
            for (y in 100 until 120) setPixel(x, y, Color.BLUE) // private order identifier
        }
    }
    private fun lines() = listOf(
        PaymentImageReader.OcrLine("付款成功", Rect(10, 10, 150, 30)),
        PaymentImageReader.OcrLine("￥18.00", Rect(10, 60, 150, 80)),
        PaymentImageReader.OcrLine("交易单号4200001234567890", Rect(10, 100, 150, 120)))

    @Test fun `automatic upload preview contains only status and amount pixels not order area`() {
        val source = image()
        val preview = PaymentImageReader.automaticCloudPreview(source, lines(), 1800)!!
        assertEquals(140, preview.width)
        assertEquals(48, preview.height)
        assertEquals(Color.RED, preview.getPixel(20, 5))
        assertEquals(Color.GREEN, preview.getPixel(20, 35))
        for (y in 0 until preview.height) for (x in 0 until preview.width) {
            assertNotEquals("order pixels must never be uploaded", Color.BLUE, preview.getPixel(x, y))
        }
        // User-selected image encoding still receives the original image, unchanged.
        assertEquals(Color.BLUE, source.getPixel(20, 110))
        assertFalse(source.isRecycled)
        preview.recycle()
        source.recycle()
    }

    @Test fun `identifier overlaps or missing identifier bounds prohibit automatic upload`() {
        val source = image()
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            lines().dropLast(1) + PaymentImageReader.OcrLine("订单号123456789012", Rect(10, 60, 150, 80)), 1800))
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            lines().dropLast(1) + PaymentImageReader.OcrLine("订单号123456789012", null), 1800))
        source.recycle()
    }

    @Test fun `unverified amount missing status and compound identifier amount lines have no cloud image`() {
        val source = image()
        assertNull(PaymentImageReader.automaticCloudPreview(source, lines(), 2800))
        assertNull(PaymentImageReader.automaticCloudPreview(source, lines().drop(1), 1800))
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            listOf(lines().first(), PaymentImageReader.OcrLine("￥18.00 订单号123456789012", Rect(10, 60, 150, 80))), 1800))
        assertNull(PaymentImageReader.automaticCloudPreview(source, emptyList(), 1800))
        source.recycle()
    }

    @Test fun `out of image geometry conflicting states and overlapping crops are rejected`() {
        val source = image()
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            listOf(lines().first().copy(bounds = Rect(-1, 10, 150, 30)), lines()[1]), 1800))
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            lines() + PaymentImageReader.OcrLine("收款成功", Rect(10, 35, 150, 55)), 1800))
        assertNull(PaymentImageReader.automaticCloudPreview(source,
            listOf(lines().first(), lines()[1].copy(bounds = Rect(10, 15, 150, 35))), 1800))
        source.recycle()
    }
}

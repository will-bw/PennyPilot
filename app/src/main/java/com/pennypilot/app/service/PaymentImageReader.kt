package com.pennypilot.app.service

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Bounded image preparation only. Recognition is performed by the configured vision model. */
object PaymentImageReader {
    fun jpegBytes(bitmap: Bitmap): ByteArray {
        val ratio = minOf(1.0, 1800.0 / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap,
            maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true) else bitmap
        return try {
            var bytes = ByteArray(0)
            for (quality in listOf(92, 80, 65, 45)) {
                bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
                if (bytes.size <= 2 * 1024 * 1024) break
            }
            require(bytes.size <= 2 * 1024 * 1024) { "图片过大，请裁剪支付凭证后重试。" }
            bytes
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    fun dataUrl(bitmap: Bitmap): String = "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes(bitmap), Base64.NO_WRAP)
}

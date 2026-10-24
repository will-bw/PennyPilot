package com.pennypilot.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.pennypilot.app.util.MoneyUtil

/** Local Chinese OCR. Nothing leaves the device through this class. */
object PaymentImageReader {
    internal data class OcrLine(val text: String, val bounds: Rect?)
    private data class OcrResult(val text: String, val lines: List<OcrLine>)

    suspend fun ocrBitmap(bitmap: Bitmap): String = recognize(bitmap).text

    private suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(OcrResult(
                result.text.take(24000), result.textBlocks.flatMap { it.lines }.map { line ->
                    OcrLine(line.text, line.boundingBox?.let(::Rect))
                })) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }

    /** Automatic uploads contain only independently located status/amount strips, never a full page. */
    suspend fun automaticCloudDataUrl(bitmap: Bitmap, expectedAmountCents: Long): String? {
        val result = recognize(bitmap)
        val preview = automaticCloudPreview(bitmap, result.lines, expectedAmountCents) ?: return null
        return try { dataUrl(preview) } finally { preview.recycle() }
    }

    internal fun automaticCloudPreview(bitmap: Bitmap, lines: List<OcrLine>, expectedAmountCents: Long): Bitmap? {
        if (!MoneyUtil.isValidAmountCents(expectedAmountCents) || lines.size > 500) return null
        val complete = Regex("""^(?:本次)?(?:支付|付款|消费|交易|转账|扣款|收款|收钱|退款|退回)(?:已)?(?:成功|完成|到账)[!！。]?$|^(?:已转账|已支付|已付款|已收钱|已收款|到账成功|入账成功)[!！。]?$""")
        val amount = Regex("""^(?:(?:实付(?:金额)?|实际支付|实际付款|付款金额|支付金额|转账金额|到账金额|收款金额|退款金额)\s*[:：]?\s*)?(?:[¥￥]\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*(?:元)?|([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元)$""")
        val identifier = Regex("""订单(?:号|编号)|交易(?:号|单号)|流水号|商户单号|商家单号|退款单号|卡号|账号|账户号|[A-Za-z0-9]{10,}""")
        fun validRect(line: OcrLine): Rect? = line.bounds?.takeIf {
            it.left >= 0 && it.top >= 0 && it.right <= bitmap.width && it.bottom <= bitmap.height &&
                it.width() > 0 && it.height() > 0 && it.height() <= bitmap.height / 3
        }
        val statuses = lines.filter { complete.matches(it.text.trim()) }
        // Ambiguous or missing completion must never trigger a broad screenshot fallback.
        if (statuses.map { it.text.trim() }.distinct().size != 1) return null
        val monetary = lines.filter { line ->
            val match = amount.matchEntire(line.text.trim()) ?: return@filter false
            val value = match.groupValues.drop(1).firstOrNull(String::isNotBlank) ?: return@filter false
            MoneyUtil.parsePositiveYuanToCents(value) == expectedAmountCents
        }
        if (monetary.isEmpty()) return null
        val selected = listOf(statuses.first(), monetary.first())
        val regions = selected.map { validRect(it) ?: return null }
        // A recognizer may emit overlapping blocks; any intersecting identifier invalidates the crop.
        if (lines.any { line -> identifier.containsMatchIn(line.text) &&
                (line.bounds == null || regions.any { Rect.intersects(it, line.bounds) }) }) return null
        if (Rect.intersects(regions[0], regions[1])) return null
        val gap = 8
        val preview = Bitmap.createBitmap(regions.maxOf { it.width() }, regions.sumOf { it.height() } + gap,
            Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview).apply { drawColor(Color.WHITE) }
        var y = 0
        regions.forEach { region ->
            canvas.drawBitmap(bitmap, region, Rect(0, y, region.width(), y + region.height()), null)
            y += region.height() + gap
        }
        return preview
    }

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

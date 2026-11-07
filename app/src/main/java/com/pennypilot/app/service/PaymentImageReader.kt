package com.pennypilot.app.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Local Chinese OCR. Nothing leaves the device through this class. */
object PaymentImageReader {
    internal data class OcrLine(val text: String, val bounds: Rect?)
    private data class OcrResult(val text: String, val lines: List<OcrLine>)
    private val recognizerSlot = Semaphore(1)

    suspend fun ocrBitmap(bitmap: Bitmap): String = recognize(bitmap).text

    internal suspend fun ocrLines(bitmap: Bitmap): List<OcrLine> = recognize(bitmap).lines

    private suspend fun recognize(bitmap: Bitmap): OcrResult {
        recognizerSlot.acquire()
        return suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) {
                recognizerSlot.release()
                return@suspendCancellableCoroutine
            }
            var ownedImage: Bitmap? = null
            var recognizer: TextRecognizer? = null
            fun release() {
                ownedImage?.recycle()
                try { recognizer?.close() } finally { recognizerSlot.release() }
            }
            try {
                // ML Kit may finish after the waiting coroutine is cancelled. It must not
                // use the screenshot that the caller recycles on window navigation.
                val input = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { ownedImage = it }
                val engine = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    .also { recognizer = it }
                engine.process(InputImage.fromBitmap(input, 0)).addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) {
                                val result = task.result
                                continuation.resume(OcrResult(result.text.take(24_000),
                                    result.textBlocks.flatMap { it.lines }.map { line ->
                                        OcrLine(line.text, line.boundingBox?.let(::Rect))
                                    }))
                            } else if (task.isCanceled) continuation.cancel()
                            else continuation.resumeWithException(checkNotNull(task.exception))
                        }
                    } finally { release() }
                }
            } catch (failure: Throwable) {
                release()
                throw failure
            }
        }
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

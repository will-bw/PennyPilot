package com.pennypilot.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.pennypilot.app.service.PaymentImageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAF/share inputs are read once, bounded and never retained outside app memory. */
object RecognitionDocuments {
    private fun bytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        require(uri.scheme == "content") { "请选择系统文件选择器中的文件。" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件。" }.use {
            it.readBytesBounded(maxBytes)
        }
        require(bytes.isNotEmpty()) { "文件为空。" }
        return bytes
    }

    suspend fun image(context: Context, uri: Uri): Map<String, Any?> = withContext(Dispatchers.IO) {
        val bytes = bytes(context, uri, 12 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "图片尺寸无效，请裁剪后重试。" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 2400) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "暂不支持此图片，请选择 JPEG 或 PNG。" }
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        val bitmap = if (matrix.isIdentity) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        try {
            val ocr = try { PaymentImageReader.ocrBitmap(bitmap) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { "" }
            mapOf("base64" to Base64.encodeToString(PaymentImageReader.jpegBytes(bitmap), Base64.NO_WRAP),
                "mimeType" to "image/jpeg", "ocrText" to ocr)
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    suspend fun bill(context: Context, uri: Uri): Map<String, Any?> = withContext(Dispatchers.IO) {
        val input = bytes(context, uri, 8 * 1024 * 1024)
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        val text = runCatching { utf8.decode(ByteBuffer.wrap(input)).toString() }.getOrElse {
            java.nio.charset.Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString()
        }.removePrefix("\uFEFF")
        require(!text.contains('\u0000')) { "请选择微信或支付宝导出的 CSV 文本账单。" }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(200) else null
        } ?: "账单.csv"
        mapOf("name" to name, "text" to text)
    }

    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(out.size() + n <= max) { "文件过大，请选择较短期间的账单或裁剪图片。" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

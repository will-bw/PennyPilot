package com.pennypilot.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.data.parser.WalletReceiptDetailsParser
import com.pennypilot.app.service.PaymentImageReader
import com.pennypilot.app.service.WechatOpaqueTransferReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

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

    suspend fun image(context: Context, uri: Uri, allowCloud: Boolean): Map<String, Any?> = withContext(Dispatchers.IO) {
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
            if (allowCloud) return@withContext mapOf(
                "base64" to Base64.encodeToString(PaymentImageReader.jpegBytes(bitmap), Base64.NO_WRAP),
                "mimeType" to "image/jpeg",
            )
            val lines = try { PaymentImageReader.ocrLines(bitmap) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            val ocr = lines.joinToString("\n") { it.text }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val receipt = receiptPreview(ocr, fingerprint, System.currentTimeMillis()) {
                WechatOpaqueTransferReader.parse(lines, bitmap.width, bitmap.height)?.let {
                    // The layout can prove the receipt roles. An imported image does not prove its app.
                    it.copy(details = it.details.copy(paymentChannel = null,
                        fieldSources = it.details.fieldSources - "paymentChannel"))
                }
            }
            buildMap {
                put("localReceipt", receipt)
                if (receipt == null) put("localReceiptError", if (ocr.isBlank())
                    "没读出清晰的凭证文字。请选一张清晰的支付结果图，或手动补记。"
                else "没找到一笔有明确完成状态和金额的交易。请选择单笔支付结果或交易详情，也可以导入账单。")
            }
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun receiptPreview(text: String, fingerprint: String, observedAt: Long,
        readOpaqueTransfer: () -> NotificationParseResult?): Map<String, Any?>? {
        if (text.length !in 1..12_000) return null
        val parsed = WalletReceiptDetailsParser.parseSelectedReceipt(text, observedAt)
            ?: PaymentTextParser.analyzeSelectedReceipt(text) ?: readOpaqueTransfer() ?: return null
        val refund = parsed.scene in setOf("refund_received", "red_packet_returned")
        val time = parsed.occurredAt ?: WalletReceiptDetailsParser.completedTime(PaymentTextParser.receiptFacts(text),
            refund, observedAt, allowHistorical = true)?.occurredAt
        val details = parsed.details
        return mapOf("amount" to parsed.amountCents, "type" to parsed.direction, "occurredAt" to time,
            "merchant" to parsed.merchant, "accountTag" to details.fundingAccount, "businessKind" to parsed.businessKind,
            "note" to listOfNotNull(details.productDescription, details.remark).distinct().joinToString("\n"),
            "metadata" to mapOf(
                "operationId" to "local-receipt:$fingerprint", "receiptInput" to "local_image",
                "scene" to parsed.scene, "orderIdKind" to parsed.orderIdKind,
                "receiptDetails" to (mapOf("merchantName" to details.merchantName, "payeeName" to details.payeeName,
                    "productDescription" to details.productDescription, "remark" to details.remark,
                    "paymentChannel" to details.paymentChannel, "fundingAccount" to details.fundingAccount)
                    .filterValues { it != null } + ("fieldSources" to details.fieldSources)),
                "receiptContext" to details.context.map { mapOf("label" to it.label, "value" to it.value, "source" to it.source) },
                "fieldEvidence" to parsed.fieldEvidence))
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

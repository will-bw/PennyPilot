package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import java.util.UUID

/** 页面反复重绘复用一次身份；离开支付结果后，独立同额支付获得新身份。 */
class PaymentScreenSession {
    private var window: String? = null
    private var receipt: NotificationParseResult? = null
    private var key: String? = null
    private var absentSince: Long? = null
    private var handledKey: String? = null

    fun observe(packageName: String, windowId: Int, parsed: NotificationParseResult?, now: Long): String? {
        val currentWindow = "$packageName:$windowId"
        if (window != currentWindow) {
            reset()
            window = currentWindow
        }
        if (parsed == null) {
            if (absentSince == null) absentSince = now
            return null
        }
        if (absentSince?.let { now - it >= 1_500 } == true) {
            receipt = null
            handledKey = null
        }
        absentSince = null
        val currentSignature = "${parsed.direction}:${parsed.amountCents}:${parsed.orderId.orEmpty()}:${parsed.merchant.orEmpty()}"
        val previous = receipt
        val sameReceipt = previous != null && previous.direction == parsed.direction &&
            previous.amountCents == parsed.amountCents &&
            (previous.orderId == null || parsed.orderId == null || previous.orderId == parsed.orderId) &&
            (previous.merchant.isNullOrBlank() || parsed.merchant.isNullOrBlank() || previous.merchant == parsed.merchant)
        if (!sameReceipt) {
            receipt = parsed
            key = CaptureEventIdentity.pageKey(packageName, UUID.randomUUID().toString(), currentSignature)
            handledKey = null
        } else {
            // 页面分批加载商户/单号不代表第二笔支付；保留已知值防止后续重绘丢字段。
            receipt = parsed.copy(orderId = parsed.orderId ?: previous?.orderId,
                merchant = parsed.merchant?.takeIf { it.isNotBlank() } ?: previous?.merchant)
        }
        return key?.takeUnless { it == handledKey }
    }

    fun markHandled(eventKey: String) { handledKey = eventKey }
    fun reset() { window = null; receipt = null; key = null; absentSince = null; handledKey = null }
}

package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import java.util.UUID

/** 页面反复重绘复用一次身份；离开支付结果后，独立同额支付获得新身份。 */
class PaymentScreenSession {
    private var window: String? = null
    private var signature: String? = null
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
            signature = null
            handledKey = null
        }
        absentSince = null
        val currentSignature = "${parsed.direction}:${parsed.amountCents}:${parsed.orderId.orEmpty()}:${parsed.merchant.orEmpty()}"
        if (currentSignature != signature) {
            signature = currentSignature
            key = CaptureEventIdentity.pageKey(packageName, UUID.randomUUID().toString(), currentSignature)
            handledKey = null
        }
        return key?.takeUnless { it == handledKey }
    }

    fun markHandled(eventKey: String) { handledKey = eventKey }
    fun reset() { window = null; signature = null; key = null; absentSince = null; handledKey = null }
}

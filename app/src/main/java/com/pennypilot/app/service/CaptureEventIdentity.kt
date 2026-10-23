package com.pennypilot.app.service

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object CaptureEventIdentity {
    /**
     * 同一 package/key/postTime 的重复回调是同一事件，跨服务重建也保持一致。
     * text 可选用于区分相同 postTime 下内容已经变更的通知；支付去重由持久化证据另行判断。
     */
    fun key(packageName: String, notificationKey: String, postTime: Long, text: String? = null): String {
        val base = "v1:$packageName:${notificationKey.length}:$notificationKey:$postTime"
        return if (text == null) base else "$base:${fingerprint(text)}"
    }

    fun pageKey(packageName: String, sessionId: String, text: String): String =
        "page:v1:$packageName:$sessionId:${fingerprint(text)}"

    internal fun legacyNotificationKey(eventKey: String): String? = eventKey
        .takeIf { it.startsWith("v1:") && it.substringAfterLast(':').matches(Regex("[0-9a-f]{64}")) }
        ?.substringBeforeLast(':')

    /** 仅同一订单命名空间才能用订单号自动合并，支付工具不与电商平台共享命名空间。 */
    fun domainFor(packageName: String): String = when (packageName) {
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew" -> "meituan"
        "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite" -> "douyin"
        "com.eg.android.AlipayGphone" -> "alipay"
        "com.tencent.mm" -> "wechat"
        else -> packageName
    }

    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalize(text).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")

    /** 短号、脱敏号、状态文本均不能作为自动合并的订单证据。 */
    internal fun reliableOrderId(value: String?): String? = value?.let(::normalize)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,79}")) && it.any(Char::isDigit) }

    internal fun merchantFingerprint(value: String?): String? = value?.let(::normalize)
        ?.lowercase(Locale.ROOT)?.replace(" ", "")
        ?.takeIf { it.length >= 2 && it !in genericMerchants }

    private val genericMerchants = setOf(
        "未知", "未知商户", "商户", "收款方", "付款方", "微信", "微信支付", "支付宝",
        "美团", "美团外卖", "美团支付", "抖音", "抖音支付", "抖音商城", "支付成功", "付款成功"
    )
}

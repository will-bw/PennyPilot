package com.pennypilot.app.data.parser

/** 通知与页面捕获共用的显式应用白名单，不读取其他应用的内容。 */
object PaymentAppRegistry {
    private val names = linkedMapOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.ugc.aweme.lite" to "抖音极速版"
    )

    val packages: Set<String> = names.keys.toSet()

    fun displayName(packageName: String): String = names[packageName] ?: packageName
}

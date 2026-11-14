package com.pennypilot.app.data.parser

/** Explicit application profiles. A profile is a routing rule, not a claim that every page is readable. */
object PaymentAppRegistry {
    enum class Family { WALLET, SHOPPING, FOOD, VIDEO_SHOPPING }
    data class App(val packageName: String, val name: String, val family: Family,
        val limitedByPlatform: Boolean = false)

    val apps = listOf(
        App("com.tencent.mm", "微信", Family.WALLET),
        App("com.eg.android.AlipayGphone", "支付宝", Family.WALLET),
        App("com.unionpay", "云闪付", Family.WALLET),
        App("com.jd.jrapp", "京东金融", Family.WALLET),
        App("com.huawei.wallet", "华为钱包", Family.WALLET),
        App("com.ccb.longjiLife", "建行生活", Family.WALLET),
        App("com.jingdong.app.mall", "京东", Family.SHOPPING),
        App("com.xunmeng.pinduoduo", "拼多多", Family.SHOPPING),
        App("com.alibaba.wireless", "1688", Family.SHOPPING),
        App("com.taobao.idlefish", "闲鱼", Family.SHOPPING),
        App("com.taobao.taobao", "淘宝", Family.SHOPPING, limitedByPlatform = true),
        App("com.sankuai.meituan", "美团", Family.FOOD),
        App("com.dianping.v1", "大众点评", Family.FOOD),
        App("com.sankuai.meituan.takeoutnew", "美团外卖", Family.FOOD),
        App("me.ele", "饿了么", Family.FOOD),
        App("com.wudaokou.hippo", "盒马", Family.FOOD),
        App("com.ss.android.ugc.lifeservices", "抖省省", Family.FOOD),
        App("com.smile.gifmaker", "快手", Family.VIDEO_SHOPPING),
        App("com.kuaishou.nebula", "快手极速版", Family.VIDEO_SHOPPING),
        App("com.ss.android.ugc.aweme", "抖音", Family.VIDEO_SHOPPING, limitedByPlatform = true),
        App("com.ss.android.ugc.livelite", "抖音商城", Family.VIDEO_SHOPPING, limitedByPlatform = true),
        App("com.ss.android.ugc.aweme.lite", "抖音极速版", Family.VIDEO_SHOPPING, limitedByPlatform = true),
        App("com.ss.android.yumme.video", "抖音精选", Family.VIDEO_SHOPPING, limitedByPlatform = true)
    )
    private val byPackage = apps.associateBy(App::packageName)
    val packages: Set<String> = byPackage.keys
    fun profile(packageName: String): App? = byPackage[packageName]
    fun displayName(packageName: String): String = profile(packageName)?.name ?: packageName
}

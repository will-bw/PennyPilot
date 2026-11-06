package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test

/** 合成样本模拟通知/可访问节点结构，不代表已覆盖所有厂商版本或真实设备。 */
class PaymentTextParserTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val meituan = "com.sankuai.meituan"
    private val douyin = "com.ss.android.ugc.aweme"

    @Test
    fun `registry includes marketplace and lite apps`() {
        assertEquals(7, PaymentAppRegistry.packages.size)
        assertEquals("云闪付", PaymentAppRegistry.displayName("com.unionpay"))
        assertEquals("美团外卖", PaymentAppRegistry.displayName("com.sankuai.meituan.takeoutnew"))
        assertEquals("抖音极速版", PaymentAppRegistry.displayName("com.ss.android.ugc.aweme.lite"))
        assertEquals("unknown.app", PaymentAppRegistry.displayName("unknown.app"))
    }

    @Test
    fun `wechat completion reads counterparty without treating payee as income`() {
        val result = PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n收款方\n星巴克\n完成")
        assertEquals(1800L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
        assertEquals("星巴克", result?.merchant)
        assertEquals("微信支付", result?.paymentMethod)
    }

    @Test
    fun `payee fields never become income actions`() {
        listOf("收款方", "收款商户", "收款账户", "收款人", "收款码").forEach { field ->
            assertEquals(field, "EXPENSE", PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n$field\n星巴克")?.direction)
        }
    }

    @Test
    fun `actual amount wins over original total discounts and delivery fee`() {
        val result = PaymentTextParser.parseScreen(meituan, """
            支付成功
            商家：蜀香小馆
            订单金额：￥35.00
            配送费：￥3.00
            优惠：￥5.00
            实付款：￥30.00
            支付方式：微信支付
            订单号：MT20261023123456
            查看订单详情
        """.trimIndent())
        assertEquals(3000L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
        assertEquals("蜀香小馆", result?.merchant)
        assertEquals("MT20261023123456", result?.orderId)
        assertEquals("微信支付", result?.paymentMethod)
    }

    @Test
    fun `douyin and lite split accessibility nodes are supported`() {
        listOf(douyin, "com.ss.android.ugc.aweme.lite").forEach { pkg ->
            val result = PaymentTextParser.parseScreen(pkg, "支付成功\n实付金额\n￥\n１９.９０\n商家名称\n测试食品旗舰店\n订单编号\n202610239876543210")
            assertEquals(pkg, 1990L, result?.amountCents)
            assertEquals("测试食品旗舰店", result?.merchant)
            assertEquals("202610239876543210", result?.orderId)
        }
    }

    @Test
    fun `symbol and prominent amount may be split across nodes`() {
        assertEquals(1850L, PaymentTextParser.parseScreen(alipay, "付款成功\n¥\n18.50\n完成")?.amountCents)
        assertEquals(1850L, PaymentTextParser.parseScreen(alipay, "付款成功\n付款金额\n18.50\n完成")?.amountCents)
    }

    @Test
    fun `incoming receipt is income despite wallet name`() {
        val result = PaymentTextParser.parseScreen(wechat, "收款到账\n￥88.80\n完成")
        assertEquals(8880L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
    }

    @Test
    fun `refund captures returned amount rather than original expense`() {
        val result = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n原支付金额：30.00元\n订单号：MT20261023123456")
        assertEquals(500L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
        assertNull(result?.orderId)
    }

    @Test
    fun `partial refunds retain their own refund identities rather than original merchant order`() {
        val first = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n商户订单号：MT20261023123456\n退款编号：RF20261023000001")
        val second = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n商户订单号：MT20261023123456\n退款编号：RF20261023000002")
        assertEquals(500L, first?.amountCents)
        assertEquals(500L, second?.amountCents)
        assertEquals("RF20261023000001", first?.orderId)
        assertEquals("RF20261023000002", second?.orderId)
        assertEquals("refund", first?.orderIdKind)
        assertEquals("refund", second?.orderIdKind)
    }

    @Test
    fun `refund never inherits original wallet transaction identity`() {
        val result = PaymentTextParser.parseScreen(alipay, "退款已到账\n退款金额：5.00元\n原支付金额：30.00元\n交易单号：2026102322001234567890123456")
        assertEquals(500L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
        assertNull(result?.orderId)
    }

    @Test
    fun `duplicate visible amount is one transaction`() {
        assertEquals(1800L, PaymentTextParser.parseScreen(douyin, "支付成功\n¥18.00\n实付金额：18.00元")?.amountCents)
    }

    @Test
    fun `account balances do not conflict with actual payment amount`() {
        listOf("账户余额", "可用余额", "余额").forEach { label ->
            val result = PaymentTextParser.parseScreen(alipay, "支付成功\n实付18元\n$label：100元")
            assertEquals(label, 1800L, result?.amountCents)
            assertEquals("EXPENSE", result?.direction)
        }
    }

    @Test
    fun `balance only insufficient balance and failed payment are not receipts`() {
        listOf("账户余额100元", "支付成功\n账户余额100元", "支付成功\n实付18元\n余额不足",
            "支付失败\n支付金额18元\n账户余额100元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(alipay, it)) }
    }

    @Test
    fun `different unexplained amounts and conflicting actual amounts are rejected`() {
        listOf("支付成功\n¥18.00\n¥28.00", "支付成功\n实付18.00元\n实付28.00元",
            "支付成功\n实付18.00元\n另收5.00元", "退款成功\n退款5.00元\n退款10.00元",
            "收款成功\n收款金额28元\n付款金额18元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(meituan, it)) }
    }

    @Test
    fun `invalid or signed currency cannot be partially captured`() {
        listOf("0.00", "10000000.00", "1.234", "1,80.00", "1e3", "-18.00", "+18.00", "−18.00").forEach { amount ->
            assertNull(amount, PaymentTextParser.parseScreen(wechat, "支付成功\n￥$amount"))
        }
        assertEquals(180000L, PaymentTextParser.parseScreen(wechat, "支付成功\n￥1,800.00")?.amountCents)
        assertEquals(999999999L, PaymentTextParser.parseScreen(wechat, "支付成功\n￥9,999,999.99")?.amountCents)
    }

    @Test
    fun `wrong currency and unsupported app are rejected`() {
        assertNull(PaymentTextParser.parseScreen("org.example.chat", "支付成功\n¥18.00"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n支付金额：18.00美元"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n￥1800\n币种：日元"))
    }

    @Test
    fun `order amounts alone do not establish what was actually paid`() {
        assertNull(PaymentTextParser.parseScreen(meituan, "支付成功\n订单金额18元\n优惠5元"))
        assertNull(PaymentTextParser.parseScreen(meituan, "实付18元\n提交订单"))
        assertNull(PaymentTextParser.parseScreen(meituan, "支付成功\n18.00"))
    }

    @Test
    fun `pending failed cancelled advertising and refund requests are not receipts`() {
        listOf("待支付\n实付18元", "支付失败\n￥18.00", "订单已取消\n实付18元",
            "退款申请中\n退款金额18元", "退款处理中\n退款金额18元", "申请退款\n原支付18元",
            "支付成功后可享优惠\n￥18.00", "支付成功\n请支付18元", "优惠券已到账\n18元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(meituan, it)) }
    }

    @Test
    fun `history pages never become fresh transactions`() {
        listOf("账单详情", "交易详情", "订单详情", "退款详情", "我的订单", "全部订单", "订单列表", "交易记录", "历史订单").forEach { header ->
            assertNull(header, PaymentTextParser.parseScreen(douyin, "$header\n支付成功\n实付18元"))
        }
    }

    @Test
    fun `payment wording in chats and video comments is not a receipt screen`() {
        listOf("张三：支付成功 ¥18.00", "支付成功 ¥18.00", "我刚才支付成功\n¥18.00",
            "聊天记录\n支付成功\n¥18.00", "支付成功\n¥18.00\n按住说话", "视频评论\n支付成功\n¥18.00",
            "支付成功\n¥18.00\n说点什么", "推荐\n支付成功\n¥18.00\n评论"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(wechat, it)) }
    }

    @Test
    fun `only complete nonmasked identifiers become dedup evidence`() {
        listOf("12345", "123456****7890", "123456••7890", "123456…7890", "1234567890复制", "A".repeat(20), "1".repeat(65)).forEach { id ->
            assertNull(id, PaymentTextParser.parseScreen(meituan, "支付成功\n实付18元\n订单号：$id")?.orderId)
        }
        val result = PaymentTextParser.parseScreen(meituan, "支付成功\n实付18元\n交易单号：4200001234567890\n商户订单号：MT20261023123456")
        assertEquals("MT20261023123456", result?.orderId)
    }

    @Test
    fun `oversized screen is rejected`() {
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n" + "长页面".repeat(6000)))
    }

    @Test
    fun `completed sender transfer is expense while recipient has not accepted`() {
        val titles = listOf("转账成功", "转账已成功", "已转账", "转账完成", "转账已完成")
        val statuses = listOf("待对方收款", "等待对方确认收款", "对方待收款", "对方未收款", "对方尚未收款", "对方已收款", "对方已确认收款")
        titles.forEach { title -> statuses.forEach { status ->
            val text = "$title\n￥18.00\n收款方\n小明\n$status\n完成"
            val parsed = PaymentTextParser.parseScreen(wechat, text)
            assertEquals(text, 1800L, parsed?.amountCents)
            assertEquals(text, "EXPENSE", parsed?.direction)
            assertEquals(text, "小明", parsed?.merchant)
        } }
        assertEquals("EXPENSE", PaymentTextParser.parseScreen(wechat,
            "已转账，待对方确认收款\n￥18.00\n完成")?.direction)
    }

    @Test
    fun `recipient waiting alone and unconfirmed transfer are never expenses`() {
        listOf("待对方收款", "对方待收款", "转账给小明", "确认转账", "转账中", "转账失败",
            "转账已取消", "等待收款", "转账成功\n待付款", "已转账\n转账失败").forEach { title ->
            assertNull(title, PaymentTextParser.parseScreen(wechat, "$title\n￥18.00\n完成"))
        }
        assertNull(PaymentTextParser.parseScreen(wechat, "聊天记录\n已转账\n￥18.00\n待对方收款"))
        assertNull(PaymentTextParser.parseScreen(wechat, "账单详情\n已转账\n￥18.00\n待对方收款"))
    }

    @Test
    fun `all registered app packages parse complete receipts without notification evidence`() {
        PaymentAppRegistry.packages.forEach { packageName ->
            assertEquals(packageName, 1850L, PaymentTextParser.parseScreen(packageName,
                "支付成功\n￥18.50\n收款方\n便利店\n完成")?.amountCents)
        }
    }

    @Test
    fun `explicit incomplete status overrides a completed header in every capture path`() {
        listOf("交易状态：处理中", "退款状态\n受理", "状态：未成功", "支付未成功",
            "支付尚未成功", "付款没有完成", "交易状态：已关闭", "入账中").forEach { status ->
            val text = "支付成功\n实付金额18元\n$status"
            PaymentAppRegistry.packages.forEach { pkg ->
                assertNull("$pkg $status", PaymentTextParser.parseScreen(pkg, text))
                assertFalse("$pkg $status", PaymentTextParser.isScreenModelCandidate(pkg, text))
                assertFalse("$pkg $status", PaymentTextParser.isScreenVisualCandidate(pkg, text))
            }
            assertFalse(status, PaymentTextParser.canUseFallback(text))
        }
    }

    @Test
    fun `unionpay completed page keeps payment method and reliable identity`() {
        val result = PaymentTextParser.analyzeScreen("com.unionpay", """
            支付成功
            实付金额：1,234.56元
            收款方：测试商户
            交易单号：UP20261106000001
            完成
        """.trimIndent()).parsed!!
        assertEquals(123456L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("云闪付", result.paymentMethod)
        assertEquals("UP20261106000001", result.orderId)
        assertTrue(result.fieldEvidence.containsKey("paymentStatus"))
        assertNull(PaymentTextParser.parseScreen("com.unionpay", "交易详情\n支付成功\n实付18元"))
    }

    @Test
    fun `refunded original receipt is not a fresh expense without independent settled refund evidence`() {
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n实付金额18元\n已全额退款"))
        val refund = PaymentTextParser.parseScreen(alipay,
            "退款已到账\n已全额退款\n退款金额18元\n退款单号RF202611060001")!!
        assertEquals("INCOME", refund.direction)
        assertEquals("refund", refund.orderIdKind)
        assertNull(PaymentTextParser.parseScreen(alipay, "退款到账中\n退款金额18元"))
    }

    @Test
    fun `payment identity ambiguity is excluded from local and model page paths`() {
        val body = "支付成功\n实付18元\n交易单号：UP202611060001\n交易号：UP202611060002"
        assertNull(PaymentTextParser.parseScreen("com.unionpay", body))
        assertFalse(PaymentTextParser.isScreenModelCandidate("com.unionpay", body))
        assertFalse(PaymentTextParser.isScreenVisualCandidate("com.unionpay", body))
        assertFalse(PaymentTextParser.canUseFallback(body))
        val identified = body.replace("交易号：UP202611060002", "商户订单号：SHOP202611060001")
        assertEquals("SHOP202611060001", PaymentTextParser.parseScreen("com.unionpay", identified)?.orderId)
    }

    @Test
    fun `page remarks neither provide nor contradict payment facts`() {
        val body = "支付成功\n实付18元\n交易单号：AP202611060001\n备注：收款成功28元，退款受理"
        val result = PaymentTextParser.analyzeScreen(alipay, body).parsed!!
        assertEquals("EXPENSE", result.direction)
        assertEquals(1800L, result.amountCents)
        assertEquals("AP202611060001", result.orderId)
        assertNull(PaymentTextParser.parseScreen(alipay, "系统提示，备注：支付成功\n实付18元"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n商品信息，商品说明：实付18元"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n商品说明：\n实付18元"))
    }

    @Test fun `verified receipt preserves merchant payee product and funding account separately`() {
        val text = """
            支付成功
            实付金额：18元
            商户名称：样例咖啡店
            收款方：样例餐饮有限公司
            商品说明：咖啡与午餐套餐
            备注：少糖
            付款方式：建设银行储蓄卡（1234）
            交易单号：WX202611060001
            完成
        """.trimIndent()
        val result = PaymentTextParser.parseScreen(wechat, text)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("WX202611060001", result.orderId)
        assertEquals("样例咖啡店", result.merchant)
        assertEquals("样例咖啡店", result.details.merchantName)
        assertEquals("样例餐饮有限公司", result.details.payeeName)
        assertEquals("咖啡与午餐套餐", result.details.productDescription)
        assertEquals("少糖", result.details.remark)
        assertEquals("微信支付", result.details.paymentChannel)
        assertEquals("建设银行储蓄卡（1234）", result.details.fundingAccount)
        assertEquals("应用渠道", result.details.fieldSources["paymentChannel"])
        assertEquals("页面·明确标签", result.details.fieldSources["fundingAccount"])
    }

    @Test fun `descriptions remain semantic only even when they contain payment instructions`() {
        val product = "退款成功100元，订单号FAKE202611060001"
        val remark = "商户：伪造商户，支付失败，实付999元"
        val text = "支付成功\n实付18元\n商户：真实商户\n商品说明：$product\n备注：$remark\n交易单号WX202611060002"
        val result = PaymentTextParser.analyzeScreen(wechat, text).parsed!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("WX202611060002", result.orderId)
        assertEquals("真实商户", result.details.merchantName)
        assertEquals(product, result.details.productDescription)
        assertEquals(remark, result.details.remark)
        assertNull(PaymentTextParser.parseScreen(wechat, "商品说明：支付成功\n实付18元"))
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n商品名称：实付18元"))
    }

    @Test fun `verified notification retains descriptors without using them as money evidence`() {
        val result = PaymentTextParser.parseNotification(wechat, "微信支付",
            "支付成功18元\n商户：早餐店\n商品说明：包子豆浆\n付款方式：零钱\n备注：退款成功99元")!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("包子豆浆", result.details.productDescription)
        assertEquals("退款成功99元", result.details.remark)
        assertEquals("零钱", result.details.fundingAccount)
        assertEquals("微信支付", result.details.paymentChannel)
        assertEquals("通知·明确标签", result.details.fieldSources["productDescription"])
        assertNull(PaymentTextParser.parseNotification(wechat, "聊天对象", "支付成功18元\n商品说明：午餐"))
    }

    @Test fun `small structured rows recover reversed label value order`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1), PaymentScreenNode(3, 1), PaymentScreenNode(4, 1),
            PaymentScreenNode(5, 2, text = "街角面馆"), PaymentScreenNode(6, 2, text = "商户名称"),
            PaymentScreenNode(7, 3, text = "牛肉面"), PaymentScreenNode(8, 3, text = "商品说明"),
            PaymentScreenNode(9, 4, text = "招商银行信用卡(5678)"), PaymentScreenNode(10, 4, text = "付款方式")
        ))
        val text = "支付成功\n实付18元\n街角面馆\n商户名称\n牛肉面\n商品说明\n招商银行信用卡(5678)\n付款方式\n完成"
        val result = PaymentTextParser.analyzeScreen(wechat, text, structure).parsed!!
        assertEquals("街角面馆", result.merchant)
        assertEquals("街角面馆", result.details.merchantName)
        assertEquals("牛肉面", result.details.productDescription)
        assertEquals("招商银行信用卡(5678)", result.details.fundingAccount)
        assertEquals("页面·同一字段行", result.details.fieldSources["merchantName"])
    }

    @Test fun `unlabelled heading needs a unique semantic resource id`() {
        val text = "支付成功\n实付18元\n街角面馆\n完成"
        assertNull(PaymentTextParser.analyzeScreen(wechat, text).parsed!!.details.merchantName)
        val structure = PaymentScreenStructure(listOf(PaymentScreenNode(1, null, text = "街角面馆",
            resourceId = "example.wallet:id/merchant_name")))
        assertEquals("街角面馆", PaymentTextParser.analyzeScreen(wechat, text, structure).parsed!!.details.merchantName)
        val conflicting = PaymentScreenStructure(structure.nodes + PaymentScreenNode(2, null, text = "另一店铺",
            resourceId = "example.wallet:id/merchant_name"))
        assertNull(PaymentTextParser.analyzeScreen(wechat, text, conflicting).parsed!!.details.merchantName)
    }

    @Test fun `payment channel is never silently promoted to a funding account`() {
        val result = PaymentTextParser.parseScreen(meituan,
            "支付成功\n实付18元\n付款方式：微信支付\n收款方：样例商户")!!
        assertEquals("微信支付", result.details.paymentChannel)
        assertNull(result.details.fundingAccount)
        assertNull(result.details.merchantName)
        assertEquals("样例商户", result.details.payeeName)
    }

    @Test fun `inline notification fields stop before the next transaction field`() {
        val result = PaymentTextParser.parseNotification(meituan, "美团支付",
            "订单支付成功，商家：样例茶饮，原价25元，实付18元，订单号：MT202611060001")!!
        assertEquals("样例茶饮", result.merchant)
        assertEquals("样例茶饮", result.details.merchantName)
        assertEquals("MT202611060001", result.orderId)
        assertEquals("午餐", PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n商品说明：\n午餐")!!.details.productDescription)
    }

    @Test fun `inline party account and description fields keep their own boundaries`() {
        val result = PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n商户：测试店，收款方：测试公司，支付方式：测试银行(1234)\n商品说明：面包，牛奶，水果\n备注：商户：不应覆盖商户")!!
        assertEquals("测试店", result.merchant)
        assertEquals("测试店", result.details.merchantName)
        assertEquals("测试公司", result.details.payeeName)
        assertEquals("测试银行(1234)", result.details.fundingAccount)
        assertEquals("面包，牛奶，水果", result.details.productDescription)
        assertEquals("商户：不应覆盖商户", result.details.remark)
    }

    @Test fun `conflicting rows never fall back to a flattened or resource id merchant guess`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1), PaymentScreenNode(3, 1),
            PaymentScreenNode(4, 2, text = "商户名称"),
            PaymentScreenNode(5, 2, text = "第一商户", resourceId = "example:id/merchant_name"),
            PaymentScreenNode(6, 3, text = "商户名称"), PaymentScreenNode(7, 3, text = "第二商户")
        ))
        val result = PaymentTextParser.analyzeScreen(wechat,
            "支付成功\n实付18元\n商户名称\n第一商户", structure).parsed!!
        assertEquals(1800L, result.amountCents)
        assertNull(result.merchant)
        assertNull(result.details.merchantName)
        assertFalse(result.details.fieldSources.containsKey("merchantName"))
        val textConflict = PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n商户：第一商户，商户：第二商户")!!
        assertNull(textConflict.merchant)
        assertNull(textConflict.details.merchantName)
        val ambiguousRow = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1),
            PaymentScreenNode(3, 2, text = "商户名称"), PaymentScreenNode(4, 2, text = "第一商户"),
            PaymentScreenNode(5, 2, text = "第二商户")
        ))
        val rowConflict = PaymentTextParser.analyzeScreen(wechat,
            "支付成功\n实付18元\n商户名称\n第一商户", ambiguousRow).parsed!!
        assertNull(rowConflict.merchant)
        assertNull(rowConflict.details.merchantName)
    }

    @Test fun `brand merchant and branch are retained without changing a known merchant identity`() {
        val result = PaymentTextParser.parseScreen(wechat, """
            支付成功
            实付18元
            商户名称：样例餐饮公司
            品牌：样例茶饮
            门店名称：广场店
            店铺名称：广场店
            交易单号WX202611070001
            完成
        """.trimIndent())!!
        assertEquals("样例餐饮公司", result.merchant)
        assertEquals("样例餐饮公司", result.details.merchantName)
        assertTrue(result.details.context.any { it.label == "品牌" && it.value == "样例茶饮" })
        assertTrue(result.details.context.any { it.label == "门店名称" && it.value == "广场店" })
        assertEquals("WX202611070001", result.orderId)
    }

    @Test fun `multiple products and their multiline specifications remain semantic context`() {
        val result = PaymentTextParser.parseScreen(wechat, """
            支付成功
            实付18元
            商品名称：面包
            规格：全麦
            数量：2
            商品名称：牛奶
            商品明细
            原味牛奶 × 1
            每盒250ml
            单价5元
            订单描述：早餐到店自取
            交易单号WX202611070002
            完成
        """.trimIndent())!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("WX202611070002", result.orderId)
        assertEquals(listOf("面包", "牛奶"), result.details.context.filter { it.label == "商品名称" }.map { it.value })
        assertTrue(result.details.context.any { it.label == "商品明细" && it.value == "原味牛奶 × 1\n每盒250ml\n单价5元" })
        assertTrue(result.details.context.any { it.label == "规格" && it.value == "全麦" })
        assertTrue(result.details.context.any { it.label == "数量" && it.value == "2" })
        assertTrue(result.details.productDescription!!.contains("面包\n牛奶"))
        assertFalse(PaymentReceiptDetailsReader.read(wechat,
            "商品名称：面包\n商品名称：牛奶", null, "页面").ambiguousFields.contains("productDescription"))
    }

    @Test fun `multiline descriptions cannot supply status amounts identity or settlement time`() {
        val body = """
            支付成功
            实付18元
            商品明细
            退款成功纪念卡 × 1
            单价99元
            交易说明：商品附带文字
            订单号示例FAKE202611070001
            商品展示时间2026-11-07 12:00:00
            交易单号WX202611070003
            完成
        """.trimIndent()
        val result = PaymentTextParser.parseScreen(wechat, body)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("WX202611070003", result.orderId)
        assertNull(result.occurredAt)
        assertTrue(result.details.context.any { it.value.contains("退款成功纪念卡") })
        assertFalse(PaymentTextParser.receiptFacts(body).contains("单价99元"))
        assertNull(PaymentTextParser.parseScreen(wechat, "商品明细\n样例商品\n支付成功\n实付18元\n完成"))
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n商品明细\n样例商品\n单价18元\n完成"))
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n实付18元\n商品说明：面包\n支付失败\n完成"))
    }

    @Test fun `verified service order and discount descriptions are preserved without broad page capture`() {
        val result = PaymentTextParser.parseScreen(alipay, """
            付款成功
            实付48元
            服务名称：自行车保养
            交易说明：更换刹车线
            优惠说明：会员优惠10元
            联系电话：13800000000
            收货地址：测试路1号
            交易单号AP202611070004
            完成
        """.trimIndent())!!
        assertEquals(4800L, result.amountCents)
        assertEquals("AP202611070004", result.orderId)
        assertTrue(result.details.context.any { it.label == "服务名称" && it.value == "自行车保养" })
        assertTrue(result.details.context.any { it.label == "优惠说明" && it.value == "会员优惠10元" })
        assertFalse(result.details.context.any { it.value.contains("13800000000") || it.value.contains("测试路") })
    }

    @Test fun `structured multiple product rows are not a party conflict`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1), PaymentScreenNode(3, 1),
            PaymentScreenNode(4, 2, text = "商品名称"), PaymentScreenNode(5, 2, text = "面包"),
            PaymentScreenNode(6, 3, text = "商品名称"), PaymentScreenNode(7, 3, text = "牛奶")
        ))
        val result = PaymentTextParser.analyzeScreen(wechat,
            "支付成功\n实付18元\n商品名称\n面包\n商品名称\n牛奶\n完成", structure).parsed!!
        assertEquals("面包\n牛奶", result.details.productDescription)
        assertEquals(listOf("面包", "牛奶"), result.details.context.filter { it.label == "商品名称" }.map { it.value })
        assertTrue(result.details.context.all { it.source == "页面·同一字段行" })
    }

    @Test fun `inline product context stops before an explicit order field`() {
        val result = PaymentTextParser.parseNotification(wechat, "微信支付",
            "支付成功，实付18.00元，商品说明：到店午餐，订单号：WX20261107000001")!!
        assertEquals(1800L, result.amountCents)
        assertEquals("WX20261107000001", result.orderId)
        assertEquals("到店午餐", result.details.productDescription)
        assertEquals("到店午餐", result.details.context.single { it.label == "商品说明" }.value)
        val remarked = PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n备注：订单号：FAKE202611070001")!!
        assertNull(remarked.orderId)
    }

    @Test fun `empty description headings never absorb private fields or negative states`() {
        val result = PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n商品说明\n收货地址：测试地址\n13900000000\n完成")!!
        assertNull(result.details.productDescription)
        assertTrue(result.details.context.isEmpty())
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n实付18元\n商品说明\n支付失败\n完成"))
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n商品说明\n18元\n完成"))
        val withAccount = PaymentTextParser.parseScreen(wechat,
            "支付成功\n实付18元\n商品说明\n付款方式：零钱\n完成")!!
        assertNull(withAccount.details.productDescription)
        assertEquals("零钱", withAccount.details.fundingAccount)
    }

}

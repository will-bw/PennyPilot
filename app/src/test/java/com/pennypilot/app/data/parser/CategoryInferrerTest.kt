package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.domain.model.TxType
import org.junit.Assert.*
import org.junit.Test

class CategoryInferrerTest {

    /** 预置分类补上 id（预置列表 id 为 0，模拟入库后的状态） */
    private val categories = AppDatabase.PRESET_CATEGORIES.mapIndexed { i, c ->
        c.copy(id = i + 1L)
    }

    @Test
    fun `merchant keyword hits dining`() {
        val cat = CategoryInferrer.infer("沙县小吃", categories, TxType.EXPENSE)
        assertNotNull(cat)
        assertEquals("餐饮", cat!!.name)
    }

    @Test
    fun `notification text hits transport`() {
        val cat = CategoryInferrer.infer("微信支付: 你向滴滴出行付款32.00元", categories, TxType.EXPENSE)
        assertNotNull(cat)
        assertEquals("交通", cat!!.name)
    }

    @Test
    fun `expense red packet hits social gift`() {
        val cat = CategoryInferrer.infer("你给张三转账200.00元", categories, TxType.EXPENSE)
        assertNotNull(cat)
        assertEquals("人情", cat!!.name)
    }

    @Test
    fun `income red packet hits income red packet category`() {
        val cat = CategoryInferrer.infer("收到红包88.00元", categories, TxType.INCOME)
        assertNotNull(cat)
        assertEquals("红包", cat!!.name)
        assertEquals(TxType.INCOME, cat.type)
    }

    @Test
    fun `no keyword match returns null for caller fallback`() {
        assertNull(CategoryInferrer.infer("张三邀请你加入群聊", categories, TxType.EXPENSE))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(CategoryInferrer.infer("   ", categories, TxType.EXPENSE))
    }

    @Test
    fun `disabled category is skipped`() {
        val disabled = categories.map { if (it.name == "餐饮" && it.type == TxType.EXPENSE) it.copy(enabled = false) else it }
        // 餐饮停用后"沙县小吃"无其他命中 → 返回 null 由调用方兜底"其他"
        assertNull(CategoryInferrer.infer("沙县小吃", disabled, TxType.EXPENSE))
    }

    @Test
    fun `never infers the fallback category itself`() {
        // 即使文案含"其他"字样也不返回"其他"分类（兜底由调用方决定）
        val cat = CategoryInferrer.infer("其他支出100元", categories, TxType.EXPENSE)
        assertTrue(cat == null || cat.name != "其他")
    }
}

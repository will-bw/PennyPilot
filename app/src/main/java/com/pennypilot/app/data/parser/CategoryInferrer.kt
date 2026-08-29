package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType

/**
 * 商户/文案关键词 → 分类推断（设计文档 §4.1 分类推断）。
 *
 * 按分类名维护关键词表；推断时只在「启用中 + 类型匹配」的分类里找，
 * 用户停用或改名后自动失效。全部未命中返回 null，由调用方兜底到"其他"。
 */
object CategoryInferrer {

    /** 分类名 → 关键词列表；同义词分类（如收入"红包"与支出"人情"）各自独立映射 */
    private val KEYWORDS_BY_NAME = mapOf(
        "餐饮" to listOf(
            "餐", "饭店", "小吃", "外卖", "奶茶", "咖啡", "面馆", "火锅", "烧烤",
            "麦当劳", "肯德基", "星巴克", "瑞幸", "饿了么", "早餐", "夜宵", "烘焙", "茶饮"
        ),
        "交通" to listOf(
            "打车", "滴滴", "地铁", "公交", "加油", "停车", "高铁", "火车", "机票",
            "航空", "出租", "骑行", "哈啰", "青桔", "充电", "通行费"
        ),
        "购物" to listOf(
            "超市", "商城", "淘宝", "京东", "拼多多", "商场", "便利店", "旗舰店", "购物", "百货"
        ),
        "居住" to listOf(
            "房租", "物业", "电费", "水费", "燃气", "供暖", "租金"
        ),
        "娱乐" to listOf(
            "电影", "游戏", "演出", "门票", "KTV", "会员", "视频", "音乐", "剧本杀"
        ),
        "医疗" to listOf(
            "医院", "药房", "药店", "诊所", "挂号", "体检", "医药"
        ),
        "教育" to listOf(
            "书店", "课程", "培训", "学费", "考试", "文具"
        ),
        "人情" to listOf(
            "红包", "转账", "份子", "礼金"
        ),
        // 收入侧同义词：收入红包 / 工资代发
        "红包" to listOf("红包"),
        "工资" to listOf("工资", "薪资", "代发", "奖金")
    )

    /**
     * 在给定分类列表中推断最匹配的分类。
     * @return 命中的分类；未命中返回 null（调用方兜底"其他"）
     */
    fun infer(text: String, categories: List<CategoryEntity>, type: TxType): CategoryEntity? {
        if (text.isBlank()) return null
        for (cat in categories) {
            if (!cat.enabled || cat.type != type || cat.name == "其他") continue
            val keywords = KEYWORDS_BY_NAME[cat.name] ?: continue
            if (keywords.any { text.contains(it) }) return cat
        }
        return null
    }
}

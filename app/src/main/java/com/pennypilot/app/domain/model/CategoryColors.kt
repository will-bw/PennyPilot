package com.pennypilot.app.domain.model

import androidx.compose.ui.graphics.Color

data class ColorPair(val bg: Color, val fg: Color)

object CategoryColors {
    val PAIRS = listOf(
        ColorPair(Color(0xFFFFEFE2), Color(0xFFD96A24)),  // 0 餐饮
        ColorPair(Color(0xFFE7F0FF), Color(0xFF3B6FE0)),  // 1 交通
        ColorPair(Color(0xFFFDE9F1), Color(0xFFD8498B)),  // 2 购物
        ColorPair(Color(0xFFE4F6F0), Color(0xFF1E9E7E)),  // 3 居住
        ColorPair(Color(0xFFF0EAFF), Color(0xFF7A5AE0)),  // 4 娱乐
        ColorPair(Color(0xFFFDECEC), Color(0xFFD84C4C)),  // 5 医疗
        ColorPair(Color(0xFFFCEBE8), Color(0xFFDF4438)),  // 6 收入
    )

    fun of(index: Int): ColorPair = PAIRS[index % PAIRS.size]
    fun nextIndex(currentMax: Int): Int = (currentMax + 1) % PAIRS.size
}

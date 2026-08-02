package com.pennypilot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.domain.model.CategoryColors

@Composable
fun CatIcon(colorIndex: Int, emoji: String, size: Int = 42) {
    val pair = CategoryColors.of(colorIndex)
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(pair.bg, RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 19.sp)
    }
}

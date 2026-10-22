package com.pennypilot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.domain.model.CategoryColors

data class CategoryIconOption(val value: String, val label: String, val icon: ImageVector)

/** Keep persisted category values compatible with existing ledgers and backups. */
val CategoryIconOptions = listOf(
    CategoryIconOption("🍜", "餐饮", Icons.Outlined.Restaurant),
    CategoryIconOption("🚕", "交通", Icons.Outlined.LocalTaxi),
    CategoryIconOption("🛍️", "购物", Icons.Outlined.ShoppingBag),
    CategoryIconOption("🏠", "居住", Icons.Outlined.Home),
    CategoryIconOption("🎬", "娱乐", Icons.Outlined.Movie),
    CategoryIconOption("💊", "医疗", Icons.Outlined.LocalHospital),
    CategoryIconOption("📚", "教育", Icons.AutoMirrored.Outlined.MenuBook),
    CategoryIconOption("🧧", "礼金", Icons.Outlined.CardGiftcard),
    CategoryIconOption("📦", "物品", Icons.Outlined.Inventory2),
    CategoryIconOption("💼", "工作", Icons.Outlined.WorkOutline),
    CategoryIconOption("💰", "收入", Icons.Outlined.AccountBalanceWallet),
    CategoryIconOption("📌", "标记", Icons.Outlined.PushPin)
)

@Composable
fun CatIcon(colorIndex: Int, emoji: String, size: Int = 42) {
    val pair = CategoryColors.of(colorIndex)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val foreground = if (dark) lerp(pair.fg, Color.White, 0.5f) else lerp(pair.fg, Color.Black, 0.2f)
    val background = if (dark) lerp(MaterialTheme.colorScheme.surface, pair.fg, 0.14f) else pair.bg
    val vector = CategoryIconOptions.firstOrNull { it.value.replace("\uFE0F", "") == emoji.replace("\uFE0F", "") }?.icon
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(background, RoundedCornerShape((size / 3f).dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = null, tint = foreground,
                modifier = Modifier.size((size * 0.56f).coerceIn(20f, 24f).dp))
        } else {
            // User-defined symbols remain available; built-in categories share the app's vector family.
            Text(emoji, fontSize = 19.sp, maxLines = 1)
        }
    }
}

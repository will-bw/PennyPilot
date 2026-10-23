package com.pennypilot.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pennypilot.app.ui.theme.PennyMotion

enum class TabItem(val label: String, val route: String, val icon: ImageVector, val selectedIcon: ImageVector = icon) {
    TRANSACTIONS("流水", "transactions", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
    CHAT("问问", "chat", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    ADD("记一笔", "add", Icons.Outlined.Add),
    REPORT("报告", "report", Icons.Outlined.InsertChart, Icons.Filled.InsertChart),
    SETTINGS("我的", "settings", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle)
}

/** Material 导航负责系统底部留白与选中语义；记账入口始终保留文字标签。 */
@Composable
fun PennyPilotBottomBar(currentRoute: String?, onTabClick: (TabItem) -> Unit, enabled: Boolean = true) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        TabItem.entries.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                enabled = enabled,
                onClick = { onTabClick(item) },
                icon = { TabIcon(item, selected) },
                label = { TabLabel(item, selected) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = if (item == TabItem.ADD) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
fun PennyPilotNavigationRail(currentRoute: String?, onTabClick: (TabItem) -> Unit, enabled: Boolean = true) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.height(16.dp))
        TabItem.entries.forEach { item ->
            val selected = currentRoute == item.route
            NavigationRailItem(
                selected = selected,
                enabled = enabled,
                onClick = { onTabClick(item) },
                icon = { TabIcon(item, selected) },
                label = { TabLabel(item, selected) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = if (item == TabItem.ADD) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TabIcon(item: TabItem, selected: Boolean) {
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = PennyMotion.spatial(), label = "navigationEmphasis"
    )
    // Every destination has the same icon slot, keeping all five labels on one baseline.
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (item == TabItem.ADD) {
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                PennyMotion.effects(), label = "addDestinationContainer"
            )
            val foreground by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                PennyMotion.effects(), label = "addDestinationIcon"
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = container,
                contentColor = foreground,
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp)
                        .graphicsLayer { scaleX = emphasis; scaleY = emphasis })
                }
            }
        } else {
            Crossfade(selected, animationSpec = PennyMotion.effects(), label = "navigationIcon") { active ->
                Icon(if (active) item.selectedIcon else item.icon, contentDescription = null,
                    modifier = Modifier.size(24.dp).graphicsLayer { scaleX = emphasis; scaleY = emphasis })
            }
        }
    }
}

@Composable
private fun TabLabel(item: TabItem, selected: Boolean) {
    Text(item.label, style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
}

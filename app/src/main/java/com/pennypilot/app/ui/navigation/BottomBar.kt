package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pennypilot.app.R

enum class TabItem(val label: String, val route: String, val iconRes: Int) {
    TRANSACTIONS("流水", "transactions", R.drawable.ic_list),
    CHAT("问问", "chat", R.drawable.ic_message_sparkle),
    ADD("记一笔", "add", R.drawable.ic_plus),
    REPORT("报告", "report", R.drawable.ic_chart_line),
    SETTINGS("我的", "settings", R.drawable.ic_circle_user)
}

/** Material 导航负责系统底部留白与选中语义；记账入口始终保留文字标签。 */
@Composable
fun PennyPilotBottomBar(currentRoute: String?, onTabClick: (TabItem) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        TabItem.entries.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onTabClick(item) },
                icon = { TabIcon(item) },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
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
fun PennyPilotNavigationRail(currentRoute: String?, onTabClick: (TabItem) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.height(16.dp))
        TabItem.entries.forEach { item ->
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { onTabClick(item) },
                icon = { TabIcon(item) },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TabIcon(item: TabItem) {
    if (item == TabItem.ADD) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
            Icon(painterResource(item.iconRes), contentDescription = null,
                modifier = Modifier.padding(5.dp).size(22.dp))
        }
    } else {
        Icon(painterResource(item.iconRes), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TabItem(val label: String, val route: String, val icon: String) {
    TRANSACTIONS("流水", "transactions", "☰"),
    CHAT("问问", "chat", "💬"),
    ADD("记一笔", "add", "+"),
    REPORT("报告", "report", "📊"),
    SETTINGS("我的", "settings", "👤")
}

@Composable
fun PennyPilotBottomBar(
    currentRoute: String?,
    onTabClick: (TabItem) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 26.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            TabButton(TabItem.TRANSACTIONS, currentRoute, onTabClick)
            TabButton(TabItem.CHAT, currentRoute, onTabClick)

            // C-position raised FAB
            Box(
                modifier = Modifier.width(64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                FloatingActionButton(
                    onClick = { onTabClick(TabItem.ADD) },
                    modifier = Modifier
                        .size(54.dp)
                        .offset(y = (-26).dp),
                    shape = RoundedCornerShape(19.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                ) {
                    Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            TabButton(TabItem.REPORT, currentRoute, onTabClick)
            TabButton(TabItem.SETTINGS, currentRoute, onTabClick)
        }
    }
}

@Composable
private fun TabButton(
    item: TabItem,
    currentRoute: String?,
    onTabClick: (TabItem) -> Unit
) {
    val selected = currentRoute == item.route
    val color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .width(56.dp)
            .clickable { onTabClick(item) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = item.icon, fontSize = 21.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

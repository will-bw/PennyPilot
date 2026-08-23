package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.ui.theme.priGradient

enum class TabItem(val label: String, val route: String, val icon: ImageVector) {
    TRANSACTIONS("流水", "transactions", Icons.AutoMirrored.Outlined.FormatListBulleted),
    CHAT("问问", "chat", Icons.Outlined.ChatBubbleOutline),
    ADD("记一笔", "add", Icons.Outlined.Add),
    REPORT("报告", "report", Icons.AutoMirrored.Outlined.ShowChart),
    SETTINGS("我的", "settings", Icons.Outlined.Person)
}

@Composable
fun PennyPilotBottomBar(
    currentRoute: String?,
    onTabClick: (TabItem) -> Unit
) {
    // 外层 Box 不裁剪：FAB 必须放在 Surface 之外，
    // 否则凸出栏顶的部分会被 Surface 按形状裁剪掉
    Box(modifier = Modifier.fillMaxWidth()) {
        // 毛玻璃 TabBar：88% 透明卡片色 + 顶部 1px 发丝线（原型 tabbar）
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box {
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

                    // 中央 FAB 占位槽（FAB 本体在 Surface 外绝对定位）
                    Spacer(Modifier.width(64.dp))

                    TabButton(TabItem.REPORT, currentRoute, onTabClick)
                    TabButton(TabItem.SETTINGS, currentRoute, onTabClick)
                }

                // 顶部发丝分割线
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }

        // C-position raised FAB：54dp 靛蓝渐变圆角方块，凸出栏顶 16dp（原型 .fab top:-26px 等效）
        FloatingActionButton(
            onClick = { onTabClick(TabItem.ADD) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-16).dp)
                .size(54.dp),
            shape = RoundedCornerShape(19.dp),
            containerColor = Color.Transparent,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(MaterialTheme.priGradient),
                        RoundedCornerShape(19.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "记一笔",
                    tint = Color.White
                )
            }
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
        // 21dp 线性图标，选中态主色
        Icon(
            item.icon,
            contentDescription = item.label,
            tint = color,
            modifier = Modifier.size(21.dp)
        )
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

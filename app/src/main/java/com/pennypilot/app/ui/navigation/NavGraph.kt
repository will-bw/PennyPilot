package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pennypilot.app.data.settings.SecureKeyStore
import com.pennypilot.app.ui.categories.CategoriesScreen
import com.pennypilot.app.ui.edit.EditTransactionScreen
import com.pennypilot.app.ui.placeholder.PlaceholderScreen
import com.pennypilot.app.ui.settings.LlmSettingsScreen
import com.pennypilot.app.ui.transactions.TransactionsScreen

@Composable
fun PennyPilotNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            PennyPilotBottomBar(currentRoute = currentRoute) { tab ->
                navController.navigate(tab.route) {
                    popUpTo(TabItem.TRANSACTIONS.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TabItem.TRANSACTIONS.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(TabItem.TRANSACTIONS.route) { TransactionsScreen() }
            composable(TabItem.CHAT.route) { PlaceholderScreen("问问") }
            composable(TabItem.ADD.route) { EditTransactionScreen(onBack = { navController.popBackStack() }) }
            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { EditTransactionScreen(onBack = { navController.popBackStack() }) }
            composable(TabItem.REPORT.route) { PlaceholderScreen("报告") }
            composable(TabItem.SETTINGS.route) {
                SettingsPlaceholder(
                    onNavigateToCategories = { navController.navigate("categories") },
                    onNavigateToLlmSettings = { navController.navigate("llm_settings") }
                )
            }
            composable("categories") { CategoriesScreen(onBack = { navController.popBackStack() }) }
            composable("llm_settings") {
                val context = LocalContext.current
                // SecureKeyStore is cheap to create (just opens prefs)
                LlmSettingsScreen(
                    onBack = { navController.popBackStack() },
                    keyStore = SecureKeyStore(context)
                )
            }
        }
    }
}

@Composable
private fun SettingsPlaceholder(
    onNavigateToCategories: () -> Unit,
    onNavigateToLlmSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Card(onClick = onNavigateToCategories, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🏷️", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("分类管理", style = MaterialTheme.typography.titleMedium)
                    Text("增删改 · 排序 · 停用", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Card(onClick = onNavigateToLlmSettings, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✨", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("AI 设置", style = MaterialTheme.typography.titleMedium)
                    Text("LLM 供应商 · API Key", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

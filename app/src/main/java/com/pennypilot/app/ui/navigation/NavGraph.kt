package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pennypilot.app.ui.categories.CategoriesScreen
import com.pennypilot.app.ui.chat.ChatScreen
import com.pennypilot.app.ui.edit.EditTransactionScreen
import com.pennypilot.app.ui.input.AiInputScreen
import com.pennypilot.app.ui.placeholder.PlaceholderScreen
import com.pennypilot.app.ui.report.ReportScreen
import com.pennypilot.app.ui.settings.ColorOsGuideScreen
import com.pennypilot.app.ui.settings.LlmSettingsScreen
import com.pennypilot.app.ui.settings.LlmSettingsViewModel
import com.pennypilot.app.ui.settings.SettingsScreen
import com.pennypilot.app.ui.settings.SettingsViewModel
import com.pennypilot.app.ui.transactions.TransactionsScreen

@Composable
fun PennyPilotNavGraph(startEditTxId: Long = -1L) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 通知「编辑」动作深链：跳转到对应账单编辑页
    LaunchedEffect(startEditTxId) {
        if (startEditTxId > 0) {
            navController.navigate("edit/$startEditTxId") { launchSingleTop = true }
        }
    }

    Scaffold(
        // imePadding：软键盘弹出时整体上移，保证输入框不被输入法遮挡
        modifier = Modifier.imePadding(),
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
            composable(TabItem.TRANSACTIONS.route) {
                TransactionsScreen(onEditTx = { id -> navController.navigate("edit/$id") })
            }
            composable(TabItem.CHAT.route) { ChatScreen() }
            composable(TabItem.ADD.route) {
                AiInputScreen(onNavigateToManual = { navController.navigate("manual_add") })
            }
            composable("manual_add") {
                EditTransactionScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { EditTransactionScreen(onBack = { navController.popBackStack() }) }
            composable(TabItem.REPORT.route) { ReportScreen() }
            composable(TabItem.SETTINGS.route) {
                val settingsViewModel = hiltViewModel<SettingsViewModel>()
                SettingsScreen(
                    onNavigateToCategories = { navController.navigate("categories") },
                    onNavigateToLlmSettings = { navController.navigate("llm_settings") },
                    onNavigateToColorOsGuide = { navController.navigate("coloros_guide") },
                    serviceMonitor = settingsViewModel.serviceMonitor
                )
            }
            composable("coloros_guide") { ColorOsGuideScreen(onBack = { navController.popBackStack() }) }
            composable("categories") { CategoriesScreen(onBack = { navController.popBackStack() }) }
            composable("llm_settings") {
                // 复用 Hilt 单例 SecureKeyStore，避免重复创建加密存储实例
                val llmSettingsVm = hiltViewModel<LlmSettingsViewModel>()
                LlmSettingsScreen(
                    onBack = { navController.popBackStack() },
                    keyStore = llmSettingsVm.keyStore
                )
            }
        }
    }
}

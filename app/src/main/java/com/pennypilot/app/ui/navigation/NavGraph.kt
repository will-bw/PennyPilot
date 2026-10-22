package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import com.pennypilot.app.ui.report.ReportScreen
import com.pennypilot.app.ui.rules.ParseRulesScreen
import com.pennypilot.app.ui.settings.ColorOsGuideScreen
import com.pennypilot.app.ui.settings.LlmSettingsScreen
import com.pennypilot.app.ui.settings.LlmSettingsViewModel
import com.pennypilot.app.ui.settings.SettingsScreen
import com.pennypilot.app.ui.settings.SettingsViewModel
import com.pennypilot.app.ui.transactions.TransactionsScreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PennyPilotNavGraph(startEditTxId: Long = -1L, startRoute: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 通知「编辑」动作深链：跳转到对应账单编辑页
    LaunchedEffect(startEditTxId) {
        if (startEditTxId > 0) {
            navController.navigate("edit/$startEditTxId") { launchSingleTop = true }
        }
    }

    // 系统提醒通知深链（如开机后通知使用权未授权 → ColorOS 引导页）
    LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank()) {
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }

    val isTopLevel = TabItem.entries.any { it.route == currentRoute }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val selectTab: (TabItem) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(TabItem.TRANSACTIONS.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useRail && isTopLevel) PennyPilotNavigationRail(currentRoute, selectTab)
            Scaffold(
                modifier = Modifier.weight(1f).imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (!useRail && isTopLevel && !keyboardVisible) {
                        PennyPilotBottomBar(currentRoute, selectTab)
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    contentAlignment = Alignment.TopCenter) {
                    NavHost(
                        navController = navController,
                        startDestination = TabItem.TRANSACTIONS.route,
                        // Tabs respond immediately. Detail pages fade through the background, avoiding overlapping text.
                        enterTransition = {
                            if (isTabChange(initialState.destination.route, targetState.destination.route)) EnterTransition.None
                            else fadeIn(tween(durationMillis = 140, delayMillis = 90))
                        },
                        exitTransition = {
                            if (isTabChange(initialState.destination.route, targetState.destination.route)) ExitTransition.None
                            else fadeOut(tween(durationMillis = 90))
                        },
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()
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
                                onNavigateToParseRules = { navController.navigate("parse_rules") },
                                viewModel = settingsViewModel
                            )
                        }
                        composable("coloros_guide") { ColorOsGuideScreen(onBack = { navController.popBackStack() }) }
                        composable("categories") { CategoriesScreen(onBack = { navController.popBackStack() }) }
                        composable("parse_rules") { ParseRulesScreen(onBack = { navController.popBackStack() }) }
                        composable("llm_settings") {
                            // 复用 Hilt 单例 SecureKeyStore，避免重复创建加密存储实例
                            val llmSettingsVm = hiltViewModel<LlmSettingsViewModel>()
                            LlmSettingsScreen(
                                onBack = { navController.popBackStack() },
                                viewModel = llmSettingsVm
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isTabChange(from: String?, to: String?): Boolean =
    TabItem.entries.any { it.route == from } && TabItem.entries.any { it.route == to }

package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pennypilot.app.ui.placeholder.PlaceholderScreen
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
            composable(TabItem.ADD.route) { PlaceholderScreen("记一笔") }
            composable(TabItem.REPORT.route) { PlaceholderScreen("报告") }
            composable(TabItem.SETTINGS.route) { PlaceholderScreen("我的") }
        }
    }
}

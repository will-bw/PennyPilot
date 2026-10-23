package com.pennypilot.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
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
import com.pennypilot.app.ui.theme.PennyMotion

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PennyPilotNavGraph(startEditTxId: Long = -1L, startRoute: String? = null) {
    val navController = rememberNavController()
    val focus = LocalFocusManager.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // An outgoing destination remains composed during its transition. Its stale controls must not navigate again.
    fun navigateFrom(entry: NavBackStackEntry, route: String) {
        if (navController.currentBackStackEntry === entry) {
            focus.clearFocus()
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    fun backFrom(entry: NavBackStackEntry) {
        if (navController.currentBackStackEntry === entry) {
            focus.clearFocus()
            navController.popBackStack()
        }
    }

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
    val density = LocalDensity.current
    val keyboardInsets = WindowInsets.ime
    val keyboardVisible = keyboardInsets.getBottom(density) > 0
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val travel = with(density) { 24.dp.roundToPx() }
    val selectTab: (TabItem) -> Unit = { tab ->
        val liveRoute = navController.currentDestination?.route
        if (TabItem.entries.any { it.route == liveRoute } && liveRoute != tab.route) {
            focus.clearFocus()
            navController.navigate(tab.route) {
                popUpTo(TabItem.TRANSACTIONS.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = useRail && isTopLevel,
                modifier = if (!isTopLevel) Modifier.clearAndSetSemantics {} else Modifier,
                enter = expandHorizontally(PennyMotion.spatial(), expandFrom = Alignment.Start) + fadeIn(tween(PennyMotion.Enter)),
                exit = shrinkHorizontally(PennyMotion.spatial(), shrinkTowards = Alignment.Start) + fadeOut(tween(PennyMotion.Exit))
            ) { PennyPilotNavigationRail(currentRoute, selectTab, enabled = isTopLevel) }
            Scaffold(
                modifier = Modifier.weight(1f).imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    AnimatedVisibility(
                        visible = !useRail && isTopLevel,
                        modifier = if (!isTopLevel) Modifier.clearAndSetSemantics {} else Modifier,
                        enter = expandVertically(PennyMotion.spatial(), expandFrom = Alignment.Bottom) + fadeIn(tween(PennyMotion.Enter)),
                        exit = shrinkVertically(PennyMotion.spatial(), shrinkTowards = Alignment.Bottom) + fadeOut(tween(PennyMotion.Exit))
                    ) {
                        // Follow the actual IME inset, including interactive dismissal. No second animation clock.
                        Box(Modifier.clipToBounds().layout { measurable, constraints ->
                            val bar = measurable.measure(constraints)
                            val height = (bar.height - keyboardInsets.getBottom(density)).coerceAtLeast(0)
                            layout(bar.width, height) { bar.placeRelative(0, 0) }
                        }.then(if (keyboardVisible) Modifier.clearAndSetSemantics {} else Modifier)) {
                            PennyPilotBottomBar(currentRoute, selectTab, enabled = isTopLevel && !keyboardVisible)
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    contentAlignment = Alignment.TopCenter) {
                    NavHost(
                        navController = navController,
                        startDestination = TabItem.TRANSACTIONS.route,
                        // Fade through keeps dense text legible. Short, mirrored travel communicates direction.
                        enterTransition = {
                            val direction = destinationDirection(initialState.destination.route, targetState.destination.route)
                            fadeIn(tween(PennyMotion.Enter, delayMillis = PennyMotion.Exit)) +
                                slideInHorizontally(tween(PennyMotion.Duration)) { travel * direction * layoutSign }
                        },
                        exitTransition = {
                            val direction = destinationDirection(initialState.destination.route, targetState.destination.route)
                            fadeOut(tween(PennyMotion.Exit)) +
                                slideOutHorizontally(tween(PennyMotion.Duration)) { -travel * direction * layoutSign }
                        },
                        popEnterTransition = {
                            val direction = destinationDirection(initialState.destination.route, targetState.destination.route, popping = true)
                            fadeIn(tween(PennyMotion.Enter, delayMillis = PennyMotion.Exit)) +
                                slideInHorizontally(tween(PennyMotion.Duration)) { travel * direction * layoutSign }
                        },
                        popExitTransition = {
                            val direction = destinationDirection(initialState.destination.route, targetState.destination.route, popping = true)
                            fadeOut(tween(PennyMotion.Exit)) +
                                slideOutHorizontally(tween(PennyMotion.Duration)) { -travel * direction * layoutSign }
                        },
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().clipToBounds()
                    ) {
                        composable(TabItem.TRANSACTIONS.route) { entry ->
                            TransactionsScreen(onEditTx = { id -> navigateFrom(entry, "edit/$id") })
                        }
                        composable(TabItem.CHAT.route) { ChatScreen() }
                        composable(TabItem.ADD.route) { entry ->
                            AiInputScreen(
                                onNavigateToManual = { navigateFrom(entry, "manual_add") },
                                onViewLedger = { if (navController.currentBackStackEntry === entry) selectTab(TabItem.TRANSACTIONS) }
                            )
                        }
                        composable("manual_add") { entry ->
                            EditTransactionScreen(onBack = { backFrom(entry) })
                        }
                        composable(
                            "edit/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) { entry -> EditTransactionScreen(onBack = { backFrom(entry) }) }
                        composable(TabItem.REPORT.route) { ReportScreen() }
                        composable(TabItem.SETTINGS.route) { entry ->
                            val settingsViewModel = hiltViewModel<SettingsViewModel>()
                            SettingsScreen(
                                onNavigateToCategories = { navigateFrom(entry, "categories") },
                                onNavigateToLlmSettings = { navigateFrom(entry, "llm_settings") },
                                onNavigateToColorOsGuide = { navigateFrom(entry, "coloros_guide") },
                                onNavigateToParseRules = { navigateFrom(entry, "parse_rules") },
                                viewModel = settingsViewModel
                            )
                        }
                        composable("coloros_guide") { entry -> ColorOsGuideScreen(onBack = { backFrom(entry) }) }
                        composable("categories") { entry -> CategoriesScreen(onBack = { backFrom(entry) }) }
                        composable("parse_rules") { entry -> ParseRulesScreen(onBack = { backFrom(entry) }) }
                        composable("llm_settings") { entry ->
                            // 复用 Hilt 单例 SecureKeyStore，避免重复创建加密存储实例
                            val llmSettingsVm = hiltViewModel<LlmSettingsViewModel>()
                            LlmSettingsScreen(
                                onBack = { backFrom(entry) },
                                viewModel = llmSettingsVm
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun destinationDirection(from: String?, to: String?, popping: Boolean = false): Int {
    val fromIndex = TabItem.entries.indexOfFirst { it.route == from }
    val toIndex = TabItem.entries.indexOfFirst { it.route == to }
    return when {
        fromIndex >= 0 && toIndex >= 0 -> if (toIndex > fromIndex) 1 else -1
        popping -> -1
        else -> 1
    }
}

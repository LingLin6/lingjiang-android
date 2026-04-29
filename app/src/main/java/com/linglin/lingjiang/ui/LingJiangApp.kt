package com.linglin.lingjiang.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.linglin.lingjiang.data.SessionRepository
import com.linglin.lingjiang.session.SessionCoordinator
import kotlinx.coroutines.flow.flowOf

private sealed class Destination(val route: String, val label: String) {
    data object Live : Destination("live", "监听")
    data object History : Destination("history", "历史")
    data object Settings : Destination("settings", "设置")
}

@Composable
fun LingJiangApp(
    coordinator: SessionCoordinator,
    repository: SessionRepository,
    hasAudioPermission: Boolean,
    requestPermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route.orEmpty()

    LingJiangTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val items = listOf(Destination.Live, Destination.History, Destination.Settings)
                    items.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    popUpTo(Destination.Live.route) { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (destination) {
                                        Destination.Live -> Icons.Default.Home
                                        Destination.History -> Icons.Default.History
                                        Destination.Settings -> Icons.Default.Settings
                                    },
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Destination.Live.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Destination.Live.route) {
                    val uiState by coordinator.uiState.collectAsState()
                    val researchTasksFlow = remember(uiState.activeSessionId) {
                        uiState.activeSessionId?.let(repository::observeResearchTasks) ?: flowOf(emptyList())
                    }
                    val researchTasks by researchTasksFlow.collectAsState(initial = emptyList())
                    LiveScreen(
                        state = uiState,
                        researchTasks = researchTasks,
                        hasAudioPermission = hasAudioPermission,
                        requestPermissions = requestPermissions,
                        onStart = coordinator::startSession,
                        onPause = coordinator::pauseSession,
                        onResume = coordinator::resumeSession,
                        onEnd = coordinator::endSession,
                        onAnalyze = coordinator::analyzeCurrentSituation,
                        onToggleMute = coordinator::toggleAnalysisMuted,
                    )
                }
                composable(Destination.History.route) {
                    HistoryScreen(
                        repository = repository,
                        openReport = { sessionId -> navController.navigate("report/$sessionId") },
                    )
                }
                composable(Destination.Settings.route) {
                    val uiState by coordinator.uiState.collectAsState()
                    SettingsScreen(uiState = uiState)
                }
                composable(
                    route = "report/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { entry ->
                    ReportScreen(
                        repository = repository,
                        sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

package com.bitacoraviaje.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitacoraviaje.app.map.MapRenderer
import com.bitacoraviaje.app.ui.active.ActiveSessionRoute
import com.bitacoraviaje.app.ui.finish.FinishSegmentRoute
import com.bitacoraviaje.app.ui.home.HomeRoute
import com.bitacoraviaje.app.ui.newsession.NewSessionRoute
import com.bitacoraviaje.app.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** Type-safe destinations (DISENO §4.3 graph: Home → NewSession → ActiveSession → FinishSegment; Settings). */
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object NewSession : Route

    @Serializable
    data class ActiveSession(val segmentId: String) : Route

    @Serializable
    data class FinishSegment(val segmentId: String) : Route
}

@Composable
fun BitacoraNavHost(mapRenderer: MapRenderer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.Home) {
        composable<Route.Home> {
            HomeRoute(
                onOpenSettings = { navController.navigate(Route.Settings) },
                onNewSession = { navController.navigate(Route.NewSession) },
                onOpenActiveSession = { id -> navController.navigate(Route.ActiveSession(id)) },
            )
        }
        composable<Route.Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.NewSession> {
            NewSessionRoute(
                onBack = { navController.popBackStack() },
                onStarted = { id ->
                    navController.navigate(Route.ActiveSession(id)) { popUpTo(Route.Home) }
                },
            )
        }
        composable<Route.ActiveSession> {
            ActiveSessionRoute(mapRenderer = mapRenderer, onFinish = { id -> navController.navigate(Route.FinishSegment(id)) })
        }
        composable<Route.FinishSegment> {
            FinishSegmentRoute(
                onCancel = { navController.popBackStack() },
                onDone = { navController.popBackStack(Route.Home, inclusive = false) },
                onResumeParent = { parentId -> navController.navigate(Route.ActiveSession(parentId)) { popUpTo(Route.Home) } },
            )
        }
    }
}

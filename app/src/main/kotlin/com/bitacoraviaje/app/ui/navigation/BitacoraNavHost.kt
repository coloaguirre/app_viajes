package com.bitacoraviaje.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitacoraviaje.app.ui.active.ActiveSessionRoute
import com.bitacoraviaje.app.ui.home.HomeRoute
import com.bitacoraviaje.app.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** Type-safe destinations. New screens register here as the phases add them. */
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class ActiveSession(val segmentId: String) : Route
}

@Composable
fun BitacoraNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.Home) {
        composable<Route.Home> {
            HomeRoute(
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenActiveSession = { id -> navController.navigate(Route.ActiveSession(id)) },
            )
        }
        composable<Route.Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.ActiveSession> {
            ActiveSessionRoute(onClosed = { navController.popBackStack(Route.Home, inclusive = false) })
        }
    }
}

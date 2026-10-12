package com.bitacoraviaje.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitacoraviaje.app.ui.home.HomeScreen
import com.bitacoraviaje.app.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** Type-safe destinations. New screens register here as the phases add them. */
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun BitacoraNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.Home) {
        composable<Route.Home> {
            HomeScreen(onOpenSettings = { navController.navigate(Route.Settings) })
        }
        composable<Route.Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

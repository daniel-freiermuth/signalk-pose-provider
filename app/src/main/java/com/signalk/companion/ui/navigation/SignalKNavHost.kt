package com.signalk.companion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.signalk.companion.ui.main.MainScreen
import com.signalk.companion.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Settings : Screen("settings")
}

@Composable
fun SignalKNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Main.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

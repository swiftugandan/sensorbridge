package dev.sensorbridge.tilt

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.game.MicJump
import dev.sensorbridge.tilt.ui.home.HomeScreen
import dev.sensorbridge.tilt.ui.home.ProgressViewModel
import dev.sensorbridge.tilt.ui.levels.LevelsScreen
import dev.sensorbridge.tilt.ui.play.PlayScreen
import dev.sensorbridge.tilt.ui.play.PlayViewModel
import dev.sensorbridge.tilt.ui.settings.SettingsScreen
import dev.sensorbridge.tilt.ui.settings.SettingsViewModel
import dev.sensorbridge.tilt.ui.theme.TiltColors
import dev.sensorbridge.tilt.ui.theme.TiltTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            TiltTheme {
                // A Surface, not a bare Box, so text and icons take the theme's content colour.
                Surface(Modifier.fillMaxSize(), color = TiltColors.Walnut) { TiltNavHost() }
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val LEVELS = "levels"
    const val SETTINGS = "settings"
    const val PLAY = "play?level={level}"
    fun play(levelId: String) = "play?level=${Uri.encode(levelId)}"
}

@Composable
private fun TiltNavHost(nav: NavHostController = rememberNavController()) {
    val graph = LocalContext.current.graph
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel { ProgressViewModel(graph.progress) },
                onPlay = { nav.navigate(Routes.play(it)) },
                onLevels = { nav.navigate(Routes.LEVELS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.LEVELS) {
            LevelsScreen(
                viewModel = viewModel { ProgressViewModel(graph.progress) },
                onPlay = { nav.navigate(Routes.play(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(graph.settings, graph.progress, MicJump(graph.microphone)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PLAY, arguments = listOf(navArgument("level") { type = NavType.StringType })) { backStack ->
            val id = backStack.arguments?.getString("level")
            val entry = id?.let(LevelCatalog::byId)
            if (entry == null) {
                nav.popBackStack()
                return@composable
            }
            PlayScreen(
                viewModel = viewModel { PlayViewModel(entry, graph) },
                onQuit = { nav.popBackStack() },
                // The next level replaces this one, so Back from it returns to the menu, not to a finished level.
                onPlay = { next ->
                    nav.navigate(Routes.play(next)) { popUpTo(Routes.PLAY) { inclusive = true } }
                },
            )
        }
    }
}

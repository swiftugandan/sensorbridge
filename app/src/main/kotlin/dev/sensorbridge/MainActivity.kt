package dev.sensorbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.sensorbridge.ui.camera.CameraScreen
import dev.sensorbridge.ui.detail.SensorDetailScreen
import dev.sensorbridge.ui.detail.SensorDetailViewModel
import dev.sensorbridge.ui.list.SensorListScreen
import dev.sensorbridge.ui.microphone.MicrophoneScreen
import dev.sensorbridge.ui.theme.SensorBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SensorBridgeTheme {
                SensorBridgeNavHost()
            }
        }
    }
}

private object Routes {
    const val SENSORS = "sensors"
    const val MICROPHONE = "microphone"
    const val CAMERA = "camera"
    const val SENSOR = "sensor/{${SensorDetailViewModel.ARG_KEY}}"

    fun sensor(key: String) = "sensor/$key"
}

@Composable
private fun SensorBridgeNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Routes.SENSORS) {
        composable(Routes.SENSORS) {
            SensorListScreen(
                onOpenSensor = { key -> navController.navigate(Routes.sensor(key)) },
                onOpenMicrophone = { navController.navigate(Routes.MICROPHONE) },
                onOpenCamera = { navController.navigate(Routes.CAMERA) },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(onNavigateUp = navController::navigateUp)
        }
        composable(Routes.MICROPHONE) {
            MicrophoneScreen(onNavigateUp = navController::navigateUp)
        }
        composable(
            Routes.SENSOR,
            arguments = listOf(navArgument(SensorDetailViewModel.ARG_KEY) { type = NavType.StringType }),
        ) {
            SensorDetailScreen(onNavigateUp = navController::navigateUp)
        }
    }
}

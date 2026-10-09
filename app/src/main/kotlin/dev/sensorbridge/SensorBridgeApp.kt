package dev.sensorbridge

import android.app.Application
import android.content.Context
import android.os.Build
import dev.sensorbridge.bridge.BridgeController
import dev.sensorbridge.bridge.BridgeNotifications
import dev.sensorbridge.domain.SensorRepository
import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.domain.vision.CameraRepository
import dev.sensorbridge.domain.protocol.DeviceInfo
import dev.sensorbridge.domain.protocol.ProtocolCodec
import dev.sensorbridge.domain.protocol.RequestHandler
import dev.sensorbridge.domain.protocol.Response
import dev.sensorbridge.camera.AndroidCamera
import dev.sensorbridge.ui.camera.MediaStoreSketchGallery
import dev.sensorbridge.ui.camera.SketchGallery
import dev.sensorbridge.audio.AndroidMicrophone
import dev.sensorbridge.sensors.AndroidSensorRepository

class SensorBridgeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        BridgeNotifications.createChannel(this)
    }
}

/** Process-wide object graph. Constructed once; everything else receives its dependencies explicitly. */
class AppContainer(context: Context) {
    val sensorRepository: SensorRepository = AndroidSensorRepository(context)

    val microphone: MicrophoneRepository = AndroidMicrophone(context)

    val camera: CameraRepository = AndroidCamera(context)

    val sketchGallery: SketchGallery = MediaStoreSketchGallery(context)

    val bridgeController = BridgeController(context)

    val requestHandler = RequestHandler(
        repository = sensorRepository,
        hello = Response.Hello(
            protocol = ProtocolCodec.VERSION,
            app = "SensorBridge ${BuildConfig.VERSION_NAME}",
            device = DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                release = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
            ),
        ),
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SensorBridgeApp).container

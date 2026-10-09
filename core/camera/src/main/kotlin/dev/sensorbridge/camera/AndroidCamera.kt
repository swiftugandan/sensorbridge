package dev.sensorbridge.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import dev.sensorbridge.domain.vision.CameraAccessException
import dev.sensorbridge.domain.vision.CameraRepository
import dev.sensorbridge.domain.vision.Lens
import dev.sensorbridge.domain.vision.LumaFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [CameraRepository] backed by CameraX image analysis, delivering only the brightness plane at about 640×480.
 *
 * CameraX ties a camera's lifetime to a [LifecycleOwner]. Each collection gets its own owner, resumed when
 * collection starts and destroyed when it stops, so the camera is open exactly while someone is collecting.
 */
class AndroidCamera(context: Context) : CameraRepository {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun hasLens(lens: Lens): Boolean = appContext.packageManager.hasSystemFeature(
        when (lens) {
            Lens.BACK -> PackageManager.FEATURE_CAMERA
            Lens.FRONT -> PackageManager.FEATURE_CAMERA_FRONT
        },
    )

    override fun frames(lens: Lens): Flow<LumaFrame> = callbackFlow {
        if (!hasPermission()) throw CameraAccessException.PermissionRequired(Manifest.permission.CAMERA)

        val provider = cameraProvider()
        val selector = when (lens) {
            Lens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            Lens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (!provider.hasCamera(selector)) throw CameraAccessException.Unavailable("This device has no ${lens.name.lowercase()} camera")

        val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "SensorBridge-camera") }
        val buffers = LumaBuffers()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            // An unclosed ImageProxy stalls the pipeline once its small pool drains, so close it whatever happens.
            // Rendezvous hand-off: this thread waits until the frame is taken (CameraX drops frames meanwhile), so a
            // buffer is never refilled while the collector still reads it. Fails fast once the flow has closed.
            try {
                trySendBlocking(image.toLumaFrame(buffers, mirrored = lens == Lens.FRONT))
            } finally {
                image.close()
            }
        }

        val owner = FlowLifecycleOwner()
        val stateObserver = Observer<CameraState> { state ->
            val error = state.error ?: return@Observer
            if (state.type == CameraState.Type.CLOSED || error.type == CameraState.ErrorType.CRITICAL) {
                close(CameraAccessException.Unavailable(describe(error.code)))
            }
        }
        val camera = withContext(Dispatchers.Main.immediate) {
            provider.bindToLifecycle(owner, selector, analysis).also {
                it.cameraInfo.cameraState.observe(owner, stateObserver)
                owner.resume()
            }
        }

        awaitClose {
            // Teardown must run on the main thread; awaitClose's block is not suspending, so post it.
            mainHandler.post {
                camera.cameraInfo.cameraState.removeObserver(stateObserver)
                provider.unbind(analysis)
                owner.destroy()
                analysis.clearAnalyzer()
                analysisExecutor.shutdown()
            }
        }
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess(continuation::resume)
                    .onFailure { continuation.resumeWithException(CameraAccessException.Unavailable("Camera service unavailable: ${it.message}")) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun describe(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "The camera is in use by another app"
        CameraState.ERROR_CAMERA_DISABLED -> "The camera is disabled on this device"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "The camera is blocked by Do Not Disturb"
        else -> "The camera stopped unexpectedly (error $code)"
    }

    /** Copies the Y plane into a tightly packed array, dropping row padding. */
    private fun ImageProxy.toLumaFrame(buffers: LumaBuffers, mirrored: Boolean): LumaFrame {
        val plane = planes[0]
        check(plane.pixelStride == 1) { "Unexpected luma pixel stride ${plane.pixelStride}" }
        val data = buffers.next(width * height)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(data, row * width, width)
        }
        return LumaFrame(width, height, data, imageInfo.rotationDegrees, mirrored, imageInfo.timestamp)
    }

    /**
     * Rotates through three arrays. With the rendezvous hand-off at most one frame is being sketched and one
     * waiting while the next is captured, so the array being written is never one still in use.
     */
    private class LumaBuffers {
        private var arrays = emptyArray<ByteArray>()
        private var index = 0

        fun next(size: Int): ByteArray {
            if (arrays.isEmpty() || arrays[0].size != size) arrays = Array(BUFFER_COUNT) { ByteArray(size) }
            index = (index + 1) % BUFFER_COUNT
            return arrays[index]
        }
    }

    /** A lifecycle that exists only for the duration of one flow collection. Main thread only. */
    private class FlowLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        val ANALYSIS_SIZE = Size(640, 480)
        const val BUFFER_COUNT = 3
    }
}

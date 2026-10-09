package dev.sensorbridge.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sensorbridge.appContainer
import dev.sensorbridge.domain.vision.CameraAccessException
import dev.sensorbridge.domain.vision.CameraRepository
import dev.sensorbridge.domain.vision.Lens
import dev.sensorbridge.domain.vision.LumaFrame
import dev.sensorbridge.domain.vision.SketchParams
import dev.sensorbridge.domain.vision.SketchRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface CameraLive {
    data object Waiting : CameraLive

    /** [sequence] distinguishes frames that reuse the same bitmap. */
    data class Streaming(val image: ImageBitmap, val sequence: Long, val fps: Float?) : CameraLive

    data class NeedsPermission(val permission: String) : CameraLive
    data class Failed(val message: String) : CameraLive
}

data class CameraUiState(
    val lens: Lens,
    val lenses: List<Lens>,
    val params: SketchParams,
    val paused: Boolean,
    val live: CameraLive,
    val saving: Boolean,
)

/** One-off outcomes of a save, shown once as a snackbar. */
sealed interface SaveEvent {
    data class Saved(val uri: Uri, val location: String) : SaveEvent
    data class Failed(val message: String) : SaveEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(
    private val camera: CameraRepository,
    private val gallery: SketchGallery,
) : ViewModel() {

    private val lenses: List<Lens> = Lens.entries.filter(camera::hasLens)
    private val lens = MutableStateFlow(lenses.firstOrNull() ?: Lens.BACK)
    private val params = MutableStateFlow(SketchParams())
    private val paused = MutableStateFlow(false)
    private val permissionEpoch = MutableStateFlow(0)
    private val saving = MutableStateFlow(false)
    private var lastLive: CameraLive = CameraLive.Waiting

    /** Set by [saveSketch]; the pipeline fulfils it with a copy of the next frame it finishes. */
    private val pendingCapture = AtomicReference<CompletableDeferred<Bitmap>?>(null)

    private val saveEvents = Channel<SaveEvent>(Channel.BUFFERED)
    val events: Flow<SaveEvent> = saveEvents.receiveAsFlow()

    private val live: Flow<CameraLive> = combine(lens, paused, permissionEpoch) { l, p, _ -> l to p }
        .flatMapLatest { (selected, isPaused) -> if (isPaused) flowOf(lastLive) else sketches(selected) }
        .onEach { lastLive = it }

    /** Stops the instant nobody observes, so the camera is never open behind the user's back. */
    val uiState: StateFlow<CameraUiState> = combine(lens, params, paused, live, saving) { l, s, p, v, busy ->
        CameraUiState(l, lenses, s, p, v, busy)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(0),
        CameraUiState(lens.value, lenses, params.value, paused = false, live = CameraLive.Waiting, saving = false),
    )

    fun switchLens() {
        if (lenses.size < 2) return
        lens.update { current -> lenses[(lenses.indexOf(current) + 1) % lenses.size] }
    }

    fun setDetail(value: Float) = params.update { it.copy(detail = value.coerceIn(0f, 1f)) }

    fun setLineWeight(value: Float) = params.update { it.copy(lineWeight = value.coerceIn(0f, 1f)) }

    fun togglePaused() = paused.update { !it }

    fun onPermissionsChanged() = permissionEpoch.update { it + 1 }

    /**
     * Saves the sketch as currently shown, coloured with [ink] and [paper]. While live, the frame is copied by
     * the drawing pipeline itself right after it finishes one, so the copy can never catch a bitmap mid-write.
     * While paused nothing is drawing, so the frame on screen is copied directly.
     */
    fun saveSketch(ink: Color, paper: Color) {
        if (!saving.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val tone = captureFrame()
                    ?: return@launch saveEvents.send(SaveEvent.Failed("There's no sketch to save yet"))
                try {
                    saveEvents.send(SaveEvent.Saved(gallery.save(tone, ink, paper), gallery.location))
                } finally {
                    tone.recycle()
                }
            } catch (e: IOException) {
                saveEvents.send(SaveEvent.Failed(e.message ?: "Couldn't write the image"))
            } finally {
                saving.value = false
            }
        }
    }

    private suspend fun captureFrame(): Bitmap? {
        val shown = lastLive as? CameraLive.Streaming ?: return null
        if (paused.value) return shown.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val request = CompletableDeferred<Bitmap>()
        pendingCapture.set(request)
        // A stalled camera would otherwise leave the save waiting forever.
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { request.await() }
            ?: shown.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false).also { pendingCapture.set(null) }
    }

    /**
     * Sketching runs off the main thread and the camera drops frames while it is busy, so the screen always
     * shows the newest frame the phone can keep up with. Slider changes apply to the next frame without
     * reopening the camera; switching lens reopens it.
     */
    private fun sketches(selected: Lens): Flow<CameraLive> {
        // Ink and paper are black and white here; the screen recolours them for the current theme.
        val renderer = SketchRenderer(ink = BLACK, paper = WHITE)
        val surfaces = BitmapRing()
        val fps = FrameRate()
        var sequence = 0L
        return camera.frames(selected)
            .map<LumaFrame, CameraLive> { frame ->
                val bitmap = surfaces.next(renderer.outputWidth(frame), renderer.outputHeight(frame))
                renderer.render(frame, params.value, surfaces.pixels)
                bitmap.setPixels(surfaces.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                pendingCapture.getAndSet(null)?.complete(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                CameraLive.Streaming(bitmap.asImageBitmap(), sequence++, fps.tick(frame.timestampNanos))
            }
            .flowOn(Dispatchers.Default)
            .onStart { emit(CameraLive.Waiting) }
            .catch { e ->
                emit(
                    when (e) {
                        is CameraAccessException.PermissionRequired -> CameraLive.NeedsPermission(e.permission)
                        else -> CameraLive.Failed(e.message ?: e::class.simpleName.orEmpty())
                    },
                )
            }
    }

    /**
     * Three bitmaps used in turn, so the one being written is never the one on screen, nor the one just
     * handed to the screen and not yet drawn.
     */
    private class BitmapRing {
        private var bitmaps = emptyList<Bitmap>()
        private var index = 0
        var pixels = IntArray(0)
            private set

        fun next(width: Int, height: Int): Bitmap {
            if (bitmaps.isEmpty() || bitmaps[0].width != width || bitmaps[0].height != height) {
                bitmaps = List(RING_SIZE) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                pixels = IntArray(width * height)
            }
            index = (index + 1) % RING_SIZE
            return bitmaps[index]
        }
    }

    /** Frames per second over roughly the last second, by camera timestamps. */
    private class FrameRate {
        private val times = ArrayDeque<Long>()

        fun tick(nanos: Long): Float? {
            times.addLast(nanos)
            while (times.size > 2 && nanos - times.first() > WINDOW_NANOS) times.removeFirst()
            val span = nanos - times.first()
            return if (times.size >= 3 && span > 0) (times.size - 1) * 1e9f / span else null
        }
    }

    companion object {
        private const val RING_SIZE = 3
        private const val CAPTURE_TIMEOUT_MS = 1_000L
        private const val WINDOW_NANOS = 1_000_000_000L
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        val Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                CameraViewModel(container.camera, container.sketchGallery)
            }
        }
    }
}

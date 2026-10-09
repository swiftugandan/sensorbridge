package dev.sensorbridge.ui.microphone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sensorbridge.appContainer
import dev.sensorbridge.domain.audio.AudioAccessException
import dev.sensorbridge.domain.audio.AudioLevel
import dev.sensorbridge.domain.audio.LevelMeter
import dev.sensorbridge.domain.audio.MicrophoneInfo
import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.domain.audio.SpectrumAnalyzer
import dev.sensorbridge.domain.audio.SpectrumFrame
import dev.sensorbridge.domain.audio.ToneTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

sealed interface MicrophoneLive {
    data object Waiting : MicrophoneLive

    /** [total] changes every frame, which is what makes the screen redraw. */
    data class Streaming(
        val total: Long,
        val level: AudioLevel?,
        val peak: SpectrumFrame.Peak?,
        /** Frequency of a clear, steady musical tone, or null when there is none (see [ToneTracker]). */
        val toneHz: Float?,
    ) : MicrophoneLive

    data class NeedsPermission(val permission: String) : MicrophoneLive
    data class Failed(val message: String) : MicrophoneLive
}

data class MicrophoneUiState(
    val info: MicrophoneInfo?,
    val scale: FrequencyScale,
    val paused: Boolean,
    val live: MicrophoneLive,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneViewModel(private val microphone: MicrophoneRepository) : ViewModel() {

    val info: MicrophoneInfo? = microphone.info

    /** Survives pauses so the picture continues where it left off. */
    val history = SpectrogramHistory(HISTORY_COLUMNS)

    val frameSeconds: Float = info?.let { HOP_SIZE.toFloat() / it.sampleRate } ?: 0f

    private val scale = MutableStateFlow(FrequencyScale.LOG)
    private val paused = MutableStateFlow(false)
    private val permissionEpoch = MutableStateFlow(0)
    private var lastLive: MicrophoneLive = MicrophoneLive.Waiting

    private val live: Flow<MicrophoneLive> = combine(paused, permissionEpoch) { isPaused, _ -> isPaused }
        .flatMapLatest { isPaused -> if (isPaused) flowOf(lastLive) else frames() }
        .onEach { lastLive = it }

    /** Stops the instant nobody observes, so the microphone is never open behind the user's back. */
    val uiState: StateFlow<MicrophoneUiState> = combine(scale, paused, live) { s, p, l -> MicrophoneUiState(info, s, p, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), MicrophoneUiState(info, scale.value, false, MicrophoneLive.Waiting))

    fun setScale(value: FrequencyScale) {
        scale.value = value
    }

    fun togglePaused() {
        paused.update { !it }
    }

    fun onPermissionsChanged() {
        permissionEpoch.update { it + 1 }
    }

    /** What lies under a point of the spectrogram, for the touch readout. */
    fun probe(frameIndex: Long, frequencyHz: Float): SpectrogramProbe {
        val frame = history.frame(frameIndex) ?: return SpectrogramProbe(frequencyHz, null, null)
        val latest = history.latest()
        val bin = (frequencyHz / frame.binHz).toInt().coerceIn(0, frame.decibels.lastIndex)
        val secondsAgo = latest?.let { (it.timestampNanos - frame.timestampNanos) / 1e9f }
        return SpectrogramProbe(frequencyHz, frame.decibels[bin], secondsAgo)
    }

    /**
     * Analysis runs off the main thread at audio rate (≈47 spectra a second); the UI samples the shared history
     * and level at frame rate.
     */
    private fun frames(): Flow<MicrophoneLive> = flow<MicrophoneLive> {
        val config = info ?: throw AudioAccessException.Unavailable("No supported recording format on this device")
        val analyzer = SpectrumAnalyzer(config.sampleRate, FFT_SIZE, HOP_SIZE)
        val meter = LevelMeter()
        val tracker = ToneTracker()
        val level = AtomicReference<AudioLevel?>(null)
        val tone = AtomicReference<Float?>(null)
        coroutineScope {
            launch(Dispatchers.Default) {
                microphone.pcm().collect { block ->
                    level.set(meter.update(block))
                    analyzer.process(block) { frame ->
                        history.add(frame)
                        tone.set(tracker.update(frame))
                    }
                }
            }
            while (true) {
                val peak = history.latest()?.peak()?.takeIf { it.decibels > PEAK_THRESHOLD_DB }
                emit(MicrophoneLive.Streaming(history.total, level.get(), peak, tone.get()))
                delay(FRAME_INTERVAL_MS)
            }
        }
    }.catch { e ->
        emit(
            when (e) {
                is AudioAccessException.PermissionRequired -> MicrophoneLive.NeedsPermission(e.permission)
                else -> MicrophoneLive.Failed(e.message ?: e::class.simpleName.orEmpty())
            },
        )
    }

    companion object {
        const val FFT_SIZE = 2048
        const val HOP_SIZE = 1024

        /** About 11 seconds at 48 kHz. */
        const val HISTORY_COLUMNS = 512
        const val FLOOR_DB = -100f
        const val CEILING_DB = -30f

        /** Below this a "peak" is just the noise floor and is not worth naming. */
        private const val PEAK_THRESHOLD_DB = -80f
        private const val FRAME_INTERVAL_MS = 33L

        val Factory = viewModelFactory {
            initializer { MicrophoneViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer.microphone) }
        }
    }
}

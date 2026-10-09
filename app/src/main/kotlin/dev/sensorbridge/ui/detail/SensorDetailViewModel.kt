package dev.sensorbridge.ui.detail

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sensorbridge.appContainer
import dev.sensorbridge.domain.ReportingMode
import dev.sensorbridge.domain.SensorAccessException
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorRepository
import dev.sensorbridge.ui.components.AxisScale
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

sealed interface LiveState {
    data object Waiting : LiveState
    data class Streaming(val snapshot: HistorySnapshot, val scale: AxisScale) : LiveState
    data class NeedsPermission(val permission: String) : LiveState
    data class Failed(val message: String) : LiveState
}

data class SensorDetailUiState(
    val rateHz: Int,
    val availableRates: List<Int>,
    val paused: Boolean,
    val live: LiveState,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SensorDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: SensorRepository,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ViewModel() {

    val key: String = checkNotNull(savedStateHandle[ARG_KEY]) { "Missing $ARG_KEY" }
    val descriptor: SensorDescriptor? = repository.descriptor(key)

    private val availableRates: List<Int> = descriptor?.let(::ratesFor).orEmpty()
    private val rate = MutableStateFlow(availableRates.lastOrNull { it <= DEFAULT_RATE_HZ } ?: DEFAULT_RATE_HZ)
    private val paused = MutableStateFlow(false)
    private val permissionEpoch = MutableStateFlow(0)

    /** Last frame shown, held on screen while paused. */
    private var lastLive: LiveState = LiveState.Waiting

    private val live: Flow<LiveState> = combine(rate, paused, permissionEpoch) { hz, isPaused, _ -> hz to isPaused }
        .flatMapLatest { (hz, isPaused) -> if (isPaused) flowOf(lastLive) else frames(hz) }
        .onEach { lastLive = it }

    val uiState: StateFlow<SensorDetailUiState> = combine(rate, paused, live) { hz, isPaused, liveState ->
        SensorDetailUiState(hz, availableRates, isPaused, liveState)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        SensorDetailUiState(rate.value, availableRates, paused.value, LiveState.Waiting),
    )

    fun setRate(hz: Int) {
        rate.value = hz
    }

    fun togglePaused() {
        paused.update { !it }
    }

    fun onPermissionsChanged() {
        permissionEpoch.update { it + 1 }
    }

    /**
     * Readings feed a rolling history as fast as they arrive; the UI receives one snapshot per frame, so a
     * 200 Hz sensor costs 30 recompositions a second, not 200. The frame clock also keeps sparse sensors scrolling.
     */
    private fun frames(rateHz: Int): Flow<LiveState> = flow<LiveState> {
        val sensor = descriptor ?: throw SensorAccessException.UnknownSensor(key)
        val history = ReadingHistory(WINDOW_NANOS, channels = sensor.axes.size.coerceAtLeast(1))
        var scale: AxisScale? = null
        coroutineScope {
            launch { repository.readings(key, rateHz).collect(history::add) }
            while (true) {
                val snapshot = history.snapshot(clockNanos())
                val range = snapshot.valueRange()
                if (range != null) scale = AxisScale.stable(scale, range.start, range.endInclusive)
                emit(scale?.let { LiveState.Streaming(snapshot, it) } ?: LiveState.Waiting)
                delay(FRAME_INTERVAL_MS)
            }
        }
    }.catch { e ->
        emit(
            when (e) {
                is SensorAccessException.PermissionRequired -> LiveState.NeedsPermission(e.permission)
                else -> LiveState.Failed(e.message ?: e::class.simpleName.orEmpty())
            },
        )
    }

    companion object {
        const val ARG_KEY = "key"
        const val WINDOW_SECONDS = 10
        private const val WINDOW_NANOS = WINDOW_SECONDS * 1_000_000_000L
        private const val FRAME_INTERVAL_MS = 33L
        private const val DEFAULT_RATE_HZ = 50
        private const val STOP_TIMEOUT_MS = 5_000L
        private val RATE_CHOICES_HZ = listOf(5, 25, 50, 100, 200)

        /** Rate choices only make sense for continuous sensors, and only up to what the hardware supports. */
        fun ratesFor(descriptor: SensorDescriptor): List<Int> {
            if (descriptor.reportingMode != ReportingMode.CONTINUOUS) return emptyList()
            val max = descriptor.maxRateHz ?: return RATE_CHOICES_HZ
            return RATE_CHOICES_HZ.filter { it <= max }.ifEmpty { listOf(RATE_CHOICES_HZ.first()) }
        }

        val Factory = viewModelFactory {
            initializer {
                SensorDetailViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    repository = checkNotNull(this[APPLICATION_KEY]).appContainer.sensorRepository,
                )
            }
        }
    }
}

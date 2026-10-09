package dev.sensorbridge.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sensorbridge.appContainer
import dev.sensorbridge.bridge.BridgeController
import dev.sensorbridge.bridge.BridgeStatus
import dev.sensorbridge.domain.SensorAccessException
import dev.sensorbridge.domain.SensorCategory
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.SensorRepository
import dev.sensorbridge.domain.audio.MicrophoneInfo
import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.domain.vision.CameraRepository
import dev.sensorbridge.domain.vision.Lens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SensorSection(val category: SensorCategory, val sensors: List<SensorDescriptor>)

data class SensorListUiState(
    val sections: List<SensorSection>,
    val totalCount: Int,
    val showWakeUpVariants: Boolean,
)

/** The microphone is listed but never opened from the list; it records only on its own screen. */
data class MicrophoneRowState(val info: MicrophoneInfo?, val hasPermission: Boolean)

/** Likewise the camera opens only on its own screen. */
data class CameraRowState(val lenses: List<Lens>, val hasPermission: Boolean)

/** What a list row shows on its trailing edge. */
sealed interface Preview {
    data object Waiting : Preview
    data class Live(val reading: SensorReading) : Preview
    data class NeedsPermission(val permission: String) : Preview
    data object Unavailable : Preview
}

@OptIn(ExperimentalCoroutinesApi::class)
class SensorListViewModel(
    private val repository: SensorRepository,
    private val microphone: MicrophoneRepository,
    private val camera: CameraRepository,
    private val bridge: BridgeController,
) : ViewModel() {

    private val showWakeUpVariants = MutableStateFlow(false)

    /** Bumped when permissions may have changed, so previews that failed for lack of one retry. */
    private val permissionEpoch = MutableStateFlow(0)

    val bridgeStatus: StateFlow<BridgeStatus> = bridge.status

    val uiState: StateFlow<SensorListUiState> = showWakeUpVariants
        .map(::buildState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildState(showWakeUpVariants.value))

    val microphoneRow: StateFlow<MicrophoneRowState> = permissionEpoch
        .map { MicrophoneRowState(microphone.info, microphone.hasPermission()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MicrophoneRowState(microphone.info, microphone.hasPermission()))

    val cameraRow: StateFlow<CameraRowState> = permissionEpoch
        .map { cameraRowState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, cameraRowState())

    private fun cameraRowState() = CameraRowState(Lens.entries.filter(camera::hasLens), camera.hasPermission())

    private val previews = HashMap<String, StateFlow<Preview>>()

    /**
     * A live preview for one row. Shared and started only while a row observes it, so off-screen sensors are
     * not registered; the short grace period avoids re-registering during scroll and configuration changes.
     */
    fun preview(key: String): StateFlow<Preview> = previews.getOrPut(key) {
        permissionEpoch
            .flatMapLatest {
                repository.readings(key, PREVIEW_RATE_HZ)
                    .map<SensorReading, Preview>(Preview::Live)
                    .catch { e ->
                        emit(if (e is SensorAccessException.PermissionRequired) Preview.NeedsPermission(e.permission) else Preview.Unavailable)
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PREVIEW_GRACE_MS), Preview.Waiting)
    }

    fun setShowWakeUpVariants(show: Boolean) {
        showWakeUpVariants.value = show
    }

    fun onPermissionsChanged() {
        permissionEpoch.update { it + 1 }
    }

    fun setBridgeEnabled(enabled: Boolean) {
        if (enabled) bridge.start() else bridge.stop()
    }

    private fun buildState(showWakeUp: Boolean): SensorListUiState {
        val all = repository.sensors
        val visible = all.filter { showWakeUp || !it.wakeUp }
        val sections = SensorCategory.entries.mapNotNull { category ->
            visible.filter { it.category == category }
                .takeIf { it.isNotEmpty() }
                ?.let { SensorSection(category, it) }
        }
        return SensorListUiState(sections, totalCount = all.size, showWakeUpVariants = showWakeUp)
    }

    companion object {
        private const val PREVIEW_RATE_HZ = 8
        private const val PREVIEW_GRACE_MS = 1_500L

        val Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                SensorListViewModel(container.sensorRepository, container.microphone, container.camera, container.bridgeController)
            }
        }
    }
}

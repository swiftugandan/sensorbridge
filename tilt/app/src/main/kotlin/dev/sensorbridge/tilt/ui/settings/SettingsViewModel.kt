package dev.sensorbridge.tilt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sensorbridge.domain.audio.AudioAccessException
import dev.sensorbridge.tilt.data.GameSettings
import dev.sensorbridge.tilt.data.JumpInput
import dev.sensorbridge.tilt.data.ProgressStore
import dev.sensorbridge.tilt.data.SettingsStore
import dev.sensorbridge.tilt.game.MicJump
import dev.sensorbridge.tilt.game.MicReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val store: SettingsStore,
    private val progress: ProgressStore,
    private val micJump: MicJump,
) : ViewModel() {

    val settings: StateFlow<GameSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val permissionEpoch = MutableStateFlow(0)
    private val hasPermission = MutableStateFlow(micJump.hasPermission())
    val micPermission: StateFlow<Boolean> = hasPermission

    /**
     * Live microphone readings for the "try it" meter, only while this screen is visible, sound jumping is chosen
     * and the microphone may be used.
     */
    val micTest: StateFlow<MicReading?> = combine(store.settings, permissionEpoch) { s, _ -> s.jump == JumpInput.SOUND && micJump.hasPermission() }
        .flatMapLatest { listen -> if (listen) micJump.readings().catch { if (it !is AudioAccessException) throw it } else emptyFlow<MicReading>() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    fun update(transform: (GameSettings) -> GameSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun onPermissionsChanged() {
        hasPermission.value = micJump.hasPermission()
        permissionEpoch.value++
    }

    fun resetProgress() {
        viewModelScope.launch { progress.reset() }
    }
}

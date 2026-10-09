package dev.sensorbridge.tilt.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sensorbridge.tilt.data.Progress
import dev.sensorbridge.tilt.data.ProgressStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Saved progress for the menus; null until it has been read from disk. */
class ProgressViewModel(store: ProgressStore) : ViewModel() {
    val progress: StateFlow<Progress?> = store.progress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

package dev.sensorbridge.bridge

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BridgeStatus {
    data object Stopped : BridgeStatus
    data object Starting : BridgeStatus
    data class Running(val socketName: String, val clients: Int) : BridgeStatus
    data class Failed(val reason: String) : BridgeStatus
}

/** Single source of truth for the bridge's state; the UI starts and stops the bridge only through this. */
class BridgeController(context: Context) {
    private val appContext = context.applicationContext
    private val mutableStatus = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)

    val status: StateFlow<BridgeStatus> = mutableStatus.asStateFlow()

    fun start() {
        if (mutableStatus.value is BridgeStatus.Running) return
        mutableStatus.value = BridgeStatus.Starting
        ContextCompat.startForegroundService(appContext, BridgeService.startIntent(appContext))
    }

    fun stop() {
        appContext.stopService(BridgeService.startIntent(appContext))
    }

    /** Called by [BridgeService] only. */
    internal fun report(status: BridgeStatus) {
        mutableStatus.value = status
    }
}

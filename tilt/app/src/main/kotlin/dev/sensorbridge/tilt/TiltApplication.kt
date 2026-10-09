package dev.sensorbridge.tilt

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.sensorbridge.audio.AndroidMicrophone
import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.tilt.data.ProgressStore
import dev.sensorbridge.tilt.data.SettingsStore
import dev.sensorbridge.tilt.game.LevelRepository
import dev.sensorbridge.tilt.platform.Haptics
import dev.sensorbridge.tilt.platform.OrientationSource
import dev.sensorbridge.tilt.platform.SoundOutput

class TiltApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

private val Context.settingsData by preferencesDataStore(name = "settings")
private val Context.progressData by preferencesDataStore(name = "progress")

/** The app's object graph, built once. Everything else receives what it needs from here. */
class AppGraph(private val context: Context) {
    val settings = SettingsStore(context.settingsData)
    val progress = ProgressStore(context.progressData)
    val levels = LevelRepository()
    val microphone: MicrophoneRepository = AndroidMicrophone(context)
    val haptics = Haptics(context)

    /**
     * Each level gets its own sensor reader and sound output, started and stopped by that level alone, so one
     * level being torn down can never switch off the next one's input or sound.
     */
    fun newOrientationSource() = OrientationSource(context)
    fun newSoundOutput() = SoundOutput(context)
}

val Context.graph: AppGraph get() = (applicationContext as TiltApplication).graph

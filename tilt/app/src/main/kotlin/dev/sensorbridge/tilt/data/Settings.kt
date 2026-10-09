package dev.sensorbridge.tilt.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.sensorbridge.tilt.engine.game.NeutralMode
import dev.sensorbridge.tilt.engine.game.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class Steering { TILT, TOUCH }

enum class JumpInput { SOUND, TAP }

data class GameSettings(
    val steering: Steering = Steering.TILT,
    val neutral: NeutralMode = NeutralMode.AUTO,
    val sensitivity: Double = 1.0,
    val jump: JumpInput = JumpInput.SOUND,
    val sound: Boolean = true,
    val volume: Double = 0.8,
    val haptics: Boolean = true,
) {
    /** Touch steering has no physical pose, so its neutral is always flat. */
    val sessionConfig: SessionConfig
        get() = SessionConfig(sensitivity, if (steering == Steering.TOUCH) NeutralMode.FLAT else neutral)
}

/** Player preferences, persisted. Unknown or corrupt values fall back to defaults rather than failing. */
class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<GameSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(transform: (GameSettings) -> GameSettings) {
        store.edit { p ->
            val next = transform(p.toSettings())
            p[STEERING] = next.steering.name
            p[NEUTRAL] = next.neutral.name
            p[SENSITIVITY] = next.sensitivity
            p[JUMP] = next.jump.name
            p[SOUND] = next.sound
            p[VOLUME] = next.volume
            p[HAPTICS] = next.haptics
        }
    }

    private fun Preferences.toSettings(): GameSettings {
        val defaults = GameSettings()
        return GameSettings(
            steering = this[STEERING].toEnum(defaults.steering),
            neutral = this[NEUTRAL].toEnum(defaults.neutral),
            sensitivity = (this[SENSITIVITY] ?: defaults.sensitivity)
                .coerceIn(SessionConfig.MIN_SENSITIVITY, SessionConfig.MAX_SENSITIVITY),
            jump = this[JUMP].toEnum(defaults.jump),
            sound = this[SOUND] ?: defaults.sound,
            volume = (this[VOLUME] ?: defaults.volume).coerceIn(0.0, 1.0),
            haptics = this[HAPTICS] ?: defaults.haptics,
        )
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val STEERING = stringPreferencesKey("steering")
        val NEUTRAL = stringPreferencesKey("neutral")
        val SENSITIVITY = doublePreferencesKey("sensitivity")
        val JUMP = stringPreferencesKey("jump")
        val SOUND = booleanPreferencesKey("sound")
        val VOLUME = doublePreferencesKey("volume")
        val HAPTICS = booleanPreferencesKey("haptics")
    }
}

package dev.sensorbridge.tilt.engine.input

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Hears a puff of breath on the microphone, or a clap, and turns it into one jump.
 *
 * Works on the loudness of each short block of audio. The room's background level is tracked continuously, so a
 * noisy café needs a louder sound than a quiet bedroom; a trigger needs a sound well above both that background and
 * an absolute floor, so speech across the room never jumps. One sustained blow gives one jump: the detector re-arms
 * only once the level falls back toward the background.
 */
class BlowDetector(
    /** How far above the background a sound must rise to count. */
    private val riseDb: Double = 18.0,
    /** Quieter sounds never count, however silent the room. */
    private val absoluteDb: Double = -32.0,
) {
    /** Loudness of the last block, in dB relative to full scale. */
    var levelDb = SILENCE_DB
        private set

    /** Estimated background level, in dBFS. */
    var backgroundDb = INITIAL_BACKGROUND_DB
        private set

    /** The level a sound must reach to trigger right now. */
    val thresholdDb: Double get() = maxOf(backgroundDb + riseDb, absoluteDb)

    private var armed = true
    private var cooldownSeconds = 0.0

    /** Feeds one block of samples in [-1, 1]; returns true if it starts a jump. */
    fun accept(samples: FloatArray, count: Int = samples.size, sampleRate: Int): Boolean {
        if (count == 0) return false
        var sum = 0.0
        for (i in 0 until count) sum += samples[i] * samples[i]
        val rms = sqrt(sum / count)
        levelDb = if (rms > 0) (20 * log10(rms)).coerceAtLeast(SILENCE_DB) else SILENCE_DB
        val seconds = count.toDouble() / sampleRate
        cooldownSeconds = (cooldownSeconds - seconds).coerceAtLeast(0.0)

        val loud = levelDb >= thresholdDb
        val triggered = loud && armed && cooldownSeconds == 0.0
        if (triggered) {
            armed = false
            cooldownSeconds = COOLDOWN_SECONDS
        }
        if (!armed && levelDb < backgroundDb + REARM_DB) armed = true
        if (!loud) trackBackground(seconds)
        return triggered
    }

    /** Falls to quieter levels quickly; rises slowly, so a sustained blow doesn't become the new background. */
    private fun trackBackground(seconds: Double) {
        val rate = if (levelDb < backgroundDb) FALL_PER_SECOND else RISE_PER_SECOND
        val k = (rate * seconds).coerceAtMost(1.0)
        backgroundDb += (levelDb - backgroundDb) * k
    }

    private companion object {
        const val SILENCE_DB = -100.0
        const val INITIAL_BACKGROUND_DB = -60.0
        const val REARM_DB = 10.0
        const val COOLDOWN_SECONDS = 0.3
        const val FALL_PER_SECOND = 8.0
        const val RISE_PER_SECOND = 0.7
    }
}

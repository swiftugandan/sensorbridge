package dev.sensorbridge.tilt.platform

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short, scaled vibrations for knocks and events. Rate-limited so a marble rattling in a corner doesn't buzz. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    @Volatile
    var enabled = true

    private var lastPulseAt = 0L

    /** A knock of [strength] 0–1. */
    fun knock(strength: Double) = pulse(KNOCK_MS, strength)

    fun fall() = pulse(FALL_MS, 1.0, force = true)

    fun checkpoint() = pulse(TICK_MS, 0.6, force = true)

    fun win() {
        val vibrator = vibrator?.takeIf { enabled } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30, 70, 60), intArrayOf(0, 160, 0, 200, 0, 255), -1))
    }

    private fun pulse(ms: Long, strength: Double, force: Boolean = false) {
        val vibrator = vibrator?.takeIf { enabled } ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPulseAt < MIN_GAP_MS) return
        lastPulseAt = now
        val amplitude = if (vibrator.hasAmplitudeControl()) (strength.coerceIn(0.0, 1.0) * 254 + 1).toInt() else VibrationEffect.DEFAULT_AMPLITUDE
        vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }

    private companion object {
        const val KNOCK_MS = 18L
        const val TICK_MS = 25L
        const val FALL_MS = 90L
        const val MIN_GAP_MS = 45L
    }
}

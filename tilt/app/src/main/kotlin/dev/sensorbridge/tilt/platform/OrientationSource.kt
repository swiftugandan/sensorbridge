package dev.sensorbridge.tilt.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.sensorbridge.tilt.engine.geometry.Vec3
import kotlin.math.sqrt

/**
 * The phone's "up" direction, in its natural axes, updated as fast as the hardware allows.
 *
 * Uses the best fused sensor the phone has:
 *  1. game rotation vector: gyroscope and accelerometer fused, smooth and quick, unaffected by magnets;
 *  2. gravity: the platform's own fused gravity estimate;
 *  3. accelerometer, low-pass filtered: works everywhere, but lags a little and twitches with hand shake.
 *
 * Events arrive on a dedicated thread and are kept as one immutable value, so the game loop reads the newest
 * orientation without locking and without waiting.
 */
class OrientationSource(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)

    enum class Kind { GAME_ROTATION_VECTOR, GRAVITY, ACCELEROMETER }

    private val choice: Pair<Kind, Sensor>? = listOf(
        Kind.GAME_ROTATION_VECTOR to Sensor.TYPE_GAME_ROTATION_VECTOR,
        Kind.GRAVITY to Sensor.TYPE_GRAVITY,
        Kind.ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
    ).firstNotNullOfOrNull { (kind, type) -> sensors?.getDefaultSensor(type)?.let { kind to it } }

    /** Which sensor steers, or null if the phone has none (touch steering still works). */
    val kind: Kind? = choice?.first

    @Volatile
    var up: Vec3 = Vec3.FLAT_UP
        private set

    private var thread: HandlerThread? = null
    private val rotation = FloatArray(9)
    private var smoothed: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            up = when (kind) {
                Kind.GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    // The third row of the device-to-world rotation is world "up" seen in device axes.
                    Vec3(rotation[6].toDouble(), rotation[7].toDouble(), rotation[8].toDouble())
                }
                Kind.GRAVITY -> unit(event.values) ?: return
                Kind.ACCELEROMETER -> unit(lowPass(event.values)) ?: return
                null -> return
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val (_, sensor) = choice ?: return
        if (thread != null) return
        val handlerThread = HandlerThread("tilt-orientation", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = handlerThread
        smoothed = null
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(handlerThread.looper))
    }

    fun stop() {
        val handlerThread = thread ?: return
        sensors.unregisterListener(listener)
        handlerThread.quitSafely()
        thread = null
    }

    private fun lowPass(values: FloatArray): FloatArray {
        val state = smoothed ?: values.copyOf(3).also { smoothed = it }
        for (i in 0..2) state[i] += (values[i] - state[i]) * ACCELEROMETER_SMOOTHING
        return state
    }

    private fun unit(v: FloatArray): Vec3? {
        val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (length < 1e-3f) return null
        return Vec3((v[0] / length).toDouble(), (v[1] / length).toDouble(), (v[2] / length).toDouble())
    }

    private companion object {
        /** Per-event blend toward the newest accelerometer reading (~50 ms time constant at 100 Hz). */
        const val ACCELEROMETER_SMOOTHING = 0.2f
    }
}

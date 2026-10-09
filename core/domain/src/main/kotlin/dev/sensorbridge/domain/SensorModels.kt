package dev.sensorbridge.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
enum class SensorCategory {
    @SerialName("motion") MOTION,
    @SerialName("position") POSITION,
    @SerialName("environment") ENVIRONMENT,
    @SerialName("other") OTHER,
}

/** Mirrors `Sensor.REPORTING_MODE_*`: decides how a sensor must be registered and how its stream behaves. */
@Serializable
enum class ReportingMode {
    /** Emits at the requested sampling rate. */
    @SerialName("continuous") CONTINUOUS,

    /** Emits once on registration, then only when the value changes. */
    @SerialName("on-change") ON_CHANGE,

    /** Fires a single event and disarms; must be re-armed with a trigger request. */
    @SerialName("one-shot") ONE_SHOT,

    /** Emits when a sensor-specific event occurs (e.g. a detected step). */
    @SerialName("special-trigger") SPECIAL_TRIGGER,
}

/** How a sensor's value array is best summarised for people. */
@Serializable
enum class ValueShape {
    /** A physical 3-vector whose magnitude is meaningful (acceleration, field strength, angular rate). */
    @SerialName("vector") VECTOR,

    /** A single physical quantity in `values[0]`. */
    @SerialName("scalar") SCALAR,

    /** A monotonically increasing count in `values[0]`. */
    @SerialName("counter") COUNTER,

    /** A rotation-vector quaternion (x, y, z[, w]). */
    @SerialName("rotation") ROTATION,

    /** Euler angles in degrees. */
    @SerialName("angles") ANGLES,

    /** The event itself is the information; values carry no physical quantity. */
    @SerialName("event") EVENT,

    /** Vendor-specific data without known semantics. */
    @SerialName("raw") RAW,
}

/** Everything a client needs to know about one hardware or virtual sensor on the device. */
@Serializable
data class SensorDescriptor(
    /** Stable identifier used by the UI and the bridge protocol, e.g. `accelerometer` or `light-wakeup`. */
    val key: String,
    val type: Int,
    val stringType: String,
    val displayName: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val category: SensorCategory,
    val shape: ValueShape,
    val unit: String? = null,
    val axes: List<String>,
    val reportingMode: ReportingMode,
    val wakeUp: Boolean,
    val maximumRange: Float,
    val resolution: Float,
    val powerMilliAmps: Float,
    val minDelayMicros: Int,
    val maxDelayMicros: Int,
    /** Runtime permission that must be granted before the sensor can be read, if any. */
    val requiredPermission: String? = null,
) {
    /** Highest rate the hardware advertises, or `null` for sensors that are not rate-driven. */
    val maxRateHz: Float?
        get() = if (minDelayMicros > 0) MICROS_PER_SECOND / minDelayMicros else null

    /** Label for the value at [index]; vendors may append undocumented values after the documented ones. */
    fun axisLabel(index: Int): String = axes.getOrNull(index) ?: "v$index"

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000f
    }
}

/** One sample from a sensor. */
@Serializable
data class SensorReading(
    val sensor: String,
    /** Event time on the device's `elapsedRealtimeNanos` clock; monotonic, use it for intervals. */
    val timestampNanos: Long,
    /** Event time converted to wall-clock milliseconds since the Unix epoch; use it for display. */
    val wallTimeMillis: Long,
    /** One of `SensorManager.SENSOR_STATUS_*`. */
    val accuracy: Int,
    val values: List<Float>,
)

/** Euclidean norm of the first [components] values. */
fun SensorReading.magnitude(components: Int = 3): Float {
    var sum = 0.0
    for (i in 0 until minOf(components, values.size)) {
        val v = values[i].toDouble()
        sum += v * v
    }
    return sqrt(sum).toFloat()
}

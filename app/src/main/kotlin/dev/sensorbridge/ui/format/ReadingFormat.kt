package dev.sensorbridge.ui.format

import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.ValueShape
import dev.sensorbridge.domain.magnitude
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/** Locale-aware number formatting for sensor values, with precision derived from the sensor's resolution. */
object ReadingFormat {

    /** Decimal places that show the sensor's resolution without inventing precision; capped for legibility. */
    fun decimalsFor(descriptor: SensorDescriptor): Int = when (descriptor.shape) {
        ValueShape.COUNTER, ValueShape.EVENT -> 0
        ValueShape.ROTATION -> 3
        else -> {
            val resolution = descriptor.resolution
            when {
                resolution >= 1f -> 0
                resolution <= 0f -> 2
                else -> ceil(-log10(resolution.toDouble())).toInt().coerceIn(1, 3)
            }
        }
    }

    fun number(value: Float, decimals: Int, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = abs(value) >= GROUPING_THRESHOLD
        }
        // Avoid "-0.00" flicker around zero.
        val shown = format.format(value.toDouble())
        return if (shown.trimStart('-', '−').all { it == '0' || !it.isDigit() }) shown.trimStart('-', '−') else shown
    }

    /**
     * A sensor value at display precision: the sensor's resolution, but never more than
     * [SIGNIFICANT_DIGITS] significant digits (58.971 lx reads as 58.97, 217.43 µT as 217.4).
     */
    fun value(value: Float, descriptor: SensorDescriptor, locale: Locale = Locale.getDefault()): String {
        val resolutionDecimals = decimalsFor(descriptor)
        val magnitude = abs(value)
        val decimals = if (magnitude < MIN_SIGNIFICANT_MAGNITUDE) {
            resolutionDecimals
        } else {
            minOf(resolutionDecimals, (SIGNIFICANT_DIGITS - 1 - floor(log10(magnitude.toDouble())).toInt()).coerceAtLeast(0))
        }
        return number(value, decimals, locale)
    }

    fun withUnit(value: String, unit: String?): String = if (unit.isNullOrEmpty()) value else "$value $unit"

    /** One compact line for list rows. */
    fun summary(descriptor: SensorDescriptor, reading: SensorReading, locale: Locale = Locale.getDefault()): String {
        val values = reading.values
        if (values.isEmpty()) return "—"
        return when (descriptor.shape) {
            ValueShape.VECTOR -> withUnit(value(reading.magnitude(), descriptor, locale), descriptor.unit)
            ValueShape.SCALAR, ValueShape.COUNTER -> withUnit(value(values[0], descriptor, locale), descriptor.unit)
            ValueShape.ROTATION -> "${number(rotationAngleDegrees(values), 0, locale)}°"
            ValueShape.ANGLES -> "${number(values[0], 0, locale)}°"
            ValueShape.RAW -> value(values[0], descriptor, locale) + if (values.size > 1) " …" else ""
            ValueShape.EVENT -> "●"
        }
    }

    /**
     * Total rotation, in degrees, encoded by a rotation-vector quaternion (x, y, z[, w]). Devices may omit w,
     * in which case it is reconstructed from the unit-norm constraint.
     */
    fun rotationAngleDegrees(values: List<Float>): Float {
        val w = values.getOrNull(3)
            ?: sqrt((1.0 - values.take(3).sumOf { it.toDouble() * it }).coerceAtLeast(0.0)).toFloat()
        return Math.toDegrees(2 * acos(w.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private const val GROUPING_THRESHOLD = 10_000f
    private const val SIGNIFICANT_DIGITS = 4
    private const val MIN_SIGNIFICANT_MAGNITUDE = 1e-6f
}

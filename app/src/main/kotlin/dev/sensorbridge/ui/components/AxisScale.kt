package dev.sensorbridge.ui.components

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** A y-axis range on "nice" tick boundaries (multiples of 1, 2, 2.5 or 5 × 10ⁿ). */
data class AxisScale(val min: Float, val max: Float, val step: Float) {
    val span: Float get() = max - min

    val ticks: List<Float>
        get() {
            val count = ((max - min) / step).roundToInt()
            return (0..count).map { min + it * step }
        }

    /** True when [lo]..[hi] sits inside this scale and still uses a reasonable share of it. */
    fun comfortablyFits(lo: Float, hi: Float): Boolean =
        lo >= min && hi <= max && (hi - lo) >= span * MIN_FILL

    companion object {
        private const val MIN_FILL = 0.3f
        private val NICE_MULTIPLES = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

        fun nice(dataMin: Float, dataMax: Float, targetTicks: Int = 4): AxisScale {
            require(targetTicks > 0)
            if (!dataMin.isFinite() || !dataMax.isFinite()) return AxisScale(0f, 1f, 0.25f)
            var lo = dataMin.toDouble()
            var hi = dataMax.toDouble()
            if (hi - lo < 1e-6) {
                // A flat signal still needs a visible band around it.
                val pad = max(abs(lo) * 0.1, 0.5)
                lo -= pad
                hi += pad
            }
            // Among nice steps, take the one whose rounded-out range wastes the least space, within a tick budget.
            val rawStep = (hi - lo) / targetTicks
            val magnitude = 10.0.pow(floor(log10(rawStep)))
            val best = NICE_MULTIPLES
                .flatMap { m -> listOf(m * magnitude / 10, m * magnitude) }
                .map { step -> Triple(step, floor(lo / step) * step, ceil(hi / step) * step) }
                .filter { (step, min, max) -> ((max - min) / step).roundToInt() <= targetTicks + 2 }
                .minWith(compareBy({ (_, min, max) -> max - min }, { (step) -> -step }))
            return AxisScale(min = best.second.toFloat(), max = best.third.toFloat(), step = best.first.toFloat())
        }

        /**
         * Keeps [previous] while the data still fits it comfortably, so the axis does not jump on every frame
         * of a noisy signal; otherwise computes a fresh nice scale.
         */
        fun stable(previous: AxisScale?, dataMin: Float, dataMax: Float): AxisScale =
            if (previous != null && previous.comfortablyFits(dataMin, dataMax)) previous else nice(dataMin, dataMax)
    }
}

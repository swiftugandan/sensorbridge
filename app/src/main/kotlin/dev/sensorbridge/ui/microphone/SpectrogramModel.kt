package dev.sensorbridge.ui.microphone

import dev.sensorbridge.domain.audio.SpectrumFrame
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The most recent [capacity] spectrum frames, oldest overwritten first. Written by the analysis thread and read
 * by the renderer, so access is synchronized. Frames are addressed by a monotonically increasing index.
 */
class SpectrogramHistory(val capacity: Int) {
    private val frames = arrayOfNulls<SpectrumFrame>(capacity)
    private var count = 0L

    /** Number of frames ever added; the newest frame has index `total - 1`. */
    val total: Long
        @Synchronized get() = count

    @Synchronized
    fun add(frame: SpectrumFrame) {
        frames[(count % capacity).toInt()] = frame
        count++
    }

    /** The frame at [index], or null if it was never added or has been overwritten. */
    @Synchronized
    fun frame(index: Long): SpectrumFrame? =
        if (index in max(0, count - capacity) until count) frames[(index % capacity).toInt()] else null

    @Synchronized
    fun latest(): SpectrumFrame? = if (count == 0L) null else frames[((count - 1) % capacity).toInt()]
}

enum class FrequencyScale {
    /** Equal space per octave, like hearing; resolves speech and music. */
    LOG,

    /** Equal space per hertz; shows harmonics as evenly spaced lines. */
    LINEAR,
}

/** Maps between vertical position (0 = top) and frequency for a given scale and range. */
class FrequencyAxis(val scale: FrequencyScale, val minHz: Float, val maxHz: Float) {
    init {
        require(maxHz > minHz && (scale == FrequencyScale.LINEAR || minHz > 0f)) { "invalid range $minHz..$maxHz" }
    }

    /** Frequency at fraction [y] of the height, measured from the top. */
    fun frequencyAt(y: Float): Float {
        val t = 1f - y.coerceIn(0f, 1f)
        return when (scale) {
            FrequencyScale.LINEAR -> minHz + t * (maxHz - minHz)
            FrequencyScale.LOG -> minHz * (maxHz / minHz).pow(t)
        }
    }

    /** Fraction of the height, from the top, at which [hz] sits. */
    fun positionOf(hz: Float): Float {
        val t = when (scale) {
            FrequencyScale.LINEAR -> (hz - minHz) / (maxHz - minHz)
            FrequencyScale.LOG -> (ln(hz / minHz) / ln(maxHz / minHz))
        }
        return 1f - t
    }

    /** Tick frequencies worth labelling: decades and their 2× and 5× steps on log, round kHz on linear. */
    fun ticks(): List<Float> = when (scale) {
        FrequencyScale.LOG -> listOf(50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f)
        FrequencyScale.LINEAR -> (0..maxHz.toInt() step 4_000).map { it.toFloat() }
    }.filter { it in minHz..maxHz }

    /**
     * For each of [rows] pixel rows, the inclusive range of FFT bins it covers. A row narrower than one bin
     * (low frequencies on a log axis) maps to the single nearest bin.
     */
    fun binRanges(rows: Int, binHz: Float, binCount: Int): List<IntRange> = List(rows) { row ->
        val hi = frequencyAt(row.toFloat() / rows)
        val lo = frequencyAt((row + 1).toFloat() / rows)
        val first = floor(lo / binHz).toInt().coerceIn(0, binCount - 1)
        val last = ceil(hi / binHz).toInt().coerceIn(0, binCount - 1)
        if (last - first <= 1) {
            val nearest = ((lo + hi) / 2 / binHz).toInt().coerceIn(0, binCount - 1)
            nearest..nearest
        } else {
            first until last
        }
    }

    companion object {
        const val LOG_MIN_HZ = 30f

        fun forFrame(scale: FrequencyScale, nyquistHz: Float) = when (scale) {
            FrequencyScale.LOG -> FrequencyAxis(scale, LOG_MIN_HZ, nyquistHz)
            FrequencyScale.LINEAR -> FrequencyAxis(scale, 0f, nyquistHz)
        }
    }
}

/** Highest level among [bins], so a narrow tone stays visible however many bins a row spans. */
fun SpectrumFrame.maxDecibels(bins: IntRange): Float {
    var best = Float.NEGATIVE_INFINITY
    for (k in bins) best = max(best, decibels[k])
    return best
}

/** Normalised position of [db] within [floorDb]..[ceilingDb], clamped to [0, 1]. */
fun normalizeDb(db: Float, floorDb: Float, ceilingDb: Float): Float =
    min(1f, max(0f, (db - floorDb) / (ceilingDb - floorDb)))

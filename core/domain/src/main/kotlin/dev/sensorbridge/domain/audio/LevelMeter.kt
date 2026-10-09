package dev.sensorbridge.domain.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

data class AudioLevel(
    /** Loudness: RMS with instant attack and a smoothed release, so speech reads steadily. */
    val rmsDbfs: Float,
    /** Highest sample in the latest block. */
    val peakDbfs: Float,
    /** Recent maximum peak, held briefly and then falling, as on a studio meter. */
    val peakHoldDbfs: Float,
    /** True when a sample reached full scale, meaning the input distorted. */
    val clipped: Boolean,
)

/** Level metering with conventional ballistics. Not thread-safe; use one instance per stream. */
class LevelMeter(
    private val releaseTimeConstantSeconds: Float = 0.3f,
    private val holdNanos: Long = 1_500_000_000L,
    private val fallDbPerSecond: Float = 20f,
) {
    private var rms = FLOOR_DB
    private var held = FLOOR_DB
    private var heldAt = 0L
    private var lastTimestamp: Long? = null

    fun update(block: PcmBlock): AudioLevel {
        val samples = block.samples
        var sumSquares = 0.0
        var peak = 0f
        for (s in samples) {
            sumSquares += s * s
            peak = max(peak, abs(s))
        }
        val blockRms = toDb(if (samples.isEmpty()) 0.0 else sumSquares / samples.size, power = true)
        val blockPeak = toDb(peak.toDouble(), power = false)

        val elapsedSeconds = lastTimestamp?.let { (block.timestampNanos - it) / 1e9f }?.coerceAtLeast(0f) ?: 0f
        lastTimestamp = block.timestampNanos

        rms = if (blockRms >= rms) blockRms else blockRms + (rms - blockRms) * exp(-elapsedSeconds / releaseTimeConstantSeconds)

        if (blockPeak >= held) {
            held = blockPeak
            heldAt = block.timestampNanos
        } else if (block.timestampNanos - heldAt > holdNanos) {
            held = max(blockPeak, held - fallDbPerSecond * elapsedSeconds)
        }

        return AudioLevel(rms, blockPeak, held, clipped = peak >= CLIP_THRESHOLD)
    }

    private fun toDb(value: Double, power: Boolean): Float =
        if (value <= 0.0) FLOOR_DB else max(FLOOR_DB, ((if (power) 10 else 20) * log10(value)).toFloat())

    companion object {
        const val FLOOR_DB = -120f
        private const val CLIP_THRESHOLD = 0.999f
    }
}

/** Nearest equal-tempered note to [frequencyHz] (A4 = 440 Hz) with the offset in cents, e.g. "A4 +3¢". */
fun noteName(frequencyHz: Float): String? {
    if (frequencyHz <= 0f || !frequencyHz.isFinite()) return null
    val semitonesFromA4 = 12 * log2(frequencyHz / 440.0)
    val nearest = semitonesFromA4.roundToInt()
    val cents = ((semitonesFromA4 - nearest) * 100).roundToInt()
    val midi = 69 + nearest
    if (midi !in 0..127) return null
    val name = NOTE_NAMES[midi % 12]
    val octave = midi / 12 - 1
    val sign = if (cents >= 0) "+" else "−"
    return "$name$octave $sign${abs(cents)}¢"
}

// ASCII "#" rather than "♯": the sharp sign is missing from common UI fonts, and the fallback font's taller line
// metrics would change the text height every time the note flips between natural and sharp.
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

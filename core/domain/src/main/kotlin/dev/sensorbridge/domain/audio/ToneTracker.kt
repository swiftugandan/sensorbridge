package dev.sensorbridge.domain.audio

import kotlin.math.abs
import kotlin.math.log2

/**
 * Finds a clear musical tone in a single spectrum, or null. A clear tone is a narrow spectral line inside the
 * range of musical fundamentals that is loud and stands well above the spectrum around it, which rejects
 * broadband noise (fans, traffic, speech consonants) and low-frequency rumble.
 */
fun SpectrumFrame.clearTone(
    minHz: Float = ToneTracker.MIN_HZ,
    maxHz: Float = ToneTracker.MAX_HZ,
    minLevelDb: Float = -70f,
    minProminenceDb: Float = 18f,
    minSharpnessDb: Float = 12f,
): SpectrumFrame.Peak? {
    val peak = peak(minHz, maxHz)
    if (peak.decibels < minLevelDb) return null
    val bin = (peak.frequencyHz / binHz).toInt().coerceIn(0, decibels.lastIndex)

    // Prominence: compared with the median of the surrounding octave, excluding the peak's own main lobe.
    val lo = (bin / OCTAVE_SPAN).toInt().coerceAtLeast(1)
    val hi = (bin * OCTAVE_SPAN).toInt().coerceAtMost(decibels.lastIndex)
    val surroundings = (lo..hi).filter { abs(it - bin) > MAIN_LOBE_BINS }.map { decibels[it] }.sorted()
    if (surroundings.isEmpty()) return null
    if (peak.decibels - surroundings[surroundings.size / 2] < minProminenceDb) return null

    // Sharpness: a tone's energy falls away just outside the Hann main lobe; a broad hump's does not.
    val shoulders = (SHOULDER_BINS).flatMap { listOf(bin - it, bin + it) }.filter { it in decibels.indices }
    if (shoulders.any { peak.decibels - decibels[it] < minSharpnessDb }) return null
    return peak
}

/**
 * Follows [clearTone] over time so a tone is named only once it has held steady for [confirmFrames], and stays
 * named through [releaseFrames] of dropouts (vibrato, a breath). Not thread-safe; use one per stream.
 */
class ToneTracker(
    private val confirmFrames: Int = 4,
    private val releaseFrames: Int = 12,
    private val toleranceCents: Float = 50f,
) {
    private var candidateHz: Float? = null
    private var candidateFrames = 0
    private var currentHz: Float? = null
    private var missedFrames = 0

    /** Feeds one frame and returns the frequency of the tone currently heard, or null. */
    fun update(frame: SpectrumFrame): Float? {
        val tone = frame.clearTone()?.frequencyHz
        if (tone == null) {
            candidateHz = null
            candidateFrames = 0
            if (currentHz != null && ++missedFrames >= releaseFrames) currentHz = null
            return currentHz
        }

        val candidate = candidateHz
        candidateFrames = if (candidate != null && centsBetween(tone, candidate) <= toleranceCents) candidateFrames + 1 else 1
        candidateHz = tone

        val current = currentHz
        when {
            candidateFrames >= confirmFrames -> {
                currentHz = tone
                missedFrames = 0
            }
            // Small drift of the tone already shown (vibrato, slow bends) updates it without re-confirming.
            current != null && centsBetween(tone, current) <= toleranceCents -> {
                currentHz = tone
                missedFrames = 0
            }
            // A different, not yet confirmed pitch counts as a dropout of the current one.
            current != null && ++missedFrames >= releaseFrames -> currentHz = null
        }
        return currentHz
    }

    companion object {
        /** Roughly G1 to C8: the fundamentals of voices and common instruments. */
        const val MIN_HZ = 50f
        const val MAX_HZ = 4_200f
    }
}

fun centsBetween(a: Float, b: Float): Float = abs(1200f * log2(a / b))

private const val OCTAVE_SPAN = 1.414f // ±half an octave either side, one octave in total
private const val MAIN_LOBE_BINS = 3
private val SHOULDER_BINS = 4..6

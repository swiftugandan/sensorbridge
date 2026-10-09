package dev.sensorbridge.domain.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Magnitude spectrum of one analysis window, in dB relative to a full-scale sine (dBFS). */
class SpectrumFrame(
    /** Time of the window's last sample on the `elapsedRealtimeNanos` clock. */
    val timestampNanos: Long,
    val sampleRate: Int,
    val fftSize: Int,
    /** `fftSize / 2 + 1` bins from DC to Nyquist. */
    val decibels: FloatArray,
) {
    val binHz: Float get() = sampleRate.toFloat() / fftSize
    val nyquistHz: Float get() = sampleRate / 2f

    data class Peak(val frequencyHz: Float, val decibels: Float)

    /**
     * The strongest component between [minHz] and [maxHz], refined between bins by fitting a parabola to the
     * log-magnitudes either side, which is accurate to a fraction of a bin for a Hann window.
     */
    fun peak(minHz: Float = 20f, maxHz: Float = nyquistHz): Peak {
        val first = max(1, ceil(minHz / binHz).toInt()).coerceAtMost(decibels.lastIndex)
        val last = (maxHz / binHz).toInt().coerceIn(first, decibels.lastIndex)
        var best = first
        for (k in first..last) if (decibels[k] > decibels[best]) best = k
        // Interpolation needs a neighbour on each side inside the searched range; otherwise report the bin itself.
        if (best == first || best == last) return Peak(best * binHz, decibels[best])
        val (l, c, r) = Triple(decibels[best - 1], decibels[best], decibels[best + 1])
        val denominator = l - 2 * c + r
        val offset = if (denominator != 0f) 0.5f * (l - r) / denominator else 0f
        return Peak((best + offset) * binHz, c - 0.25f * (l - r) * offset)
    }
}

/**
 * Short-time Fourier analysis: slides a Hann window of [fftSize] samples over the incoming audio, advancing
 * [hopSize] samples per frame. Feed blocks of any length; frames are emitted as soon as each hop completes.
 * Not thread-safe; use one instance per stream.
 */
class SpectrumAnalyzer(
    val sampleRate: Int,
    val fftSize: Int = DEFAULT_FFT_SIZE,
    val hopSize: Int = fftSize / 2,
) {
    init {
        require(hopSize in 1..fftSize) { "hopSize must be in 1..$fftSize" }
    }

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }

    /** A full-scale sine's peak magnitude after windowing: half the window's sum. Defines 0 dBFS. */
    private val fullScale = window.sum() / 2

    private val ring = FloatArray(fftSize)
    private var writeIndex = 0
    private var filled = 0
    private var sinceLastFrame = 0
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    val frameDurationNanos: Long get() = hopSize * NANOS_PER_SECOND / sampleRate

    fun process(block: PcmBlock, onFrame: (SpectrumFrame) -> Unit) {
        require(block.sampleRate == sampleRate) { "expected $sampleRate Hz audio, got ${block.sampleRate}" }
        val samples = block.samples
        for (i in samples.indices) {
            ring[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % fftSize
            if (filled < fftSize) filled++
            sinceLastFrame++
            if (filled == fftSize && sinceLastFrame >= hopSize) {
                sinceLastFrame = 0
                onFrame(analyze(block.timestampNanos + i * NANOS_PER_SECOND / sampleRate))
            }
        }
    }

    private fun analyze(timestampNanos: Long): SpectrumFrame {
        // writeIndex now points at the oldest sample, so the window reads the ring in chronological order.
        for (n in 0 until fftSize) {
            re[n] = ring[(writeIndex + n) % fftSize] * window[n]
            im[n] = 0f
        }
        fft.transform(re, im)
        val bins = FloatArray(fftSize / 2 + 1) { k ->
            val magnitude = sqrt(re[k] * re[k] + im[k] * im[k]) / fullScale
            (20 * log10(max(magnitude, SILENCE))).toFloat()
        }
        return SpectrumFrame(timestampNanos, sampleRate, fftSize, bins)
    }

    companion object {
        const val DEFAULT_FFT_SIZE = 2048
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** −200 dBFS floor so digital silence maps to a finite value. */
        private const val SILENCE = 1e-10f
    }
}

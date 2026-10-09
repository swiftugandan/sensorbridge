package dev.sensorbridge.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioDspTest {

    private val rate = 48_000

    @Test
    fun `fft puts a bin-centred sine in its bin`() {
        val n = 2048
        val bin = 43
        val re = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        val magnitudes = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(bin, magnitudes.indices.maxBy { magnitudes[it] })
        assertEquals(n / 2f, kotlin.math.sqrt(magnitudes[bin]), 1e-2f)
    }

    @Test
    fun `fft of a constant is all DC`() {
        val n = 64
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        assertEquals(n.toFloat(), re[0], 1e-4f)
        for (k in 1 until n) assertEquals(0f, abs(re[k]) + abs(im[k]), 1e-4f)
    }

    @Test
    fun `fft preserves energy`() {
        val n = 1024
        val signal = FloatArray(n) { sin(it * 0.37f) + 0.3f * sin(it * 2.1f) }
        val re = signal.copyOf()
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        val timeEnergy = signal.sumOf { it.toDouble() * it }
        val freqEnergy = re.indices.sumOf { (re[it].toDouble() * re[it] + im[it].toDouble() * im[it]) } / n
        assertEquals(timeEnergy, freqEnergy, timeEnergy * 1e-4)
    }

    @Test
    fun `analyzer finds a 1 kHz tone at full scale`() {
        val frames = analyze(sine(1_000f, amplitude = 1f, seconds = 0.5f))
        val peak = frames.last().peak()
        assertEquals(1_000f, peak.frequencyHz, 2f)
        // Between-bin tones lose up to ~1.4 dB to Hann scalloping before interpolation corrects most of it.
        assertTrue("peak was ${peak.decibels} dBFS", peak.decibels in -1.5f..0.2f)
    }

    @Test
    fun `peak never reports below the search floor`() {
        // Rumble concentrated at DC and the first bin must not interpolate down to a few hertz.
        val decibels = FloatArray(1025) { -120f }.also { it[0] = -10f; it[1] = -20f; it[2] = -60f }
        val peak = SpectrumFrame(0, 48_000, 2048, decibels).peak(minHz = 20f)
        assertTrue("peak was ${peak.frequencyHz} Hz", peak.frequencyHz >= 20f)
    }

    @Test
    fun `analyzer emits one frame per hop once the window is full`() {
        val analyzer = SpectrumAnalyzer(rate, fftSize = 2048, hopSize = 1024)
        var count = 0
        // Odd block sizes must not change the framing.
        val samples = FloatArray(rate)
        samples.toList().chunked(333).forEachIndexed { i, chunk ->
            analyzer.process(PcmBlock(rate, chunk.toFloatArray(), i * 333L * 1_000_000_000L / rate)) { count++ }
        }
        assertEquals((rate - 2048) / 1024 + 1, count)
    }

    @Test
    fun `quieter tone reads proportionally lower`() {
        val loud = analyze(sine(3_000f, amplitude = 1f, seconds = 0.2f)).last().peak().decibels
        val quiet = analyze(sine(3_000f, amplitude = 0.01f, seconds = 0.2f)).last().peak().decibels
        assertEquals(-40f, quiet - loud, 0.1f)
    }

    @Test
    fun `level of a full-scale sine is -3 dBFS rms and 0 dBFS peak`() {
        val level = LevelMeter().update(PcmBlock(rate, sine(1_000f, 1f, 0.1f), 0))
        assertEquals(-3.01f, level.rmsDbfs, 0.05f)
        assertEquals(0f, level.peakDbfs, 0.01f)
        assertTrue(level.clipped)
    }

    @Test
    fun `peak hold falls only after the hold time`() {
        val meter = LevelMeter(holdNanos = 1_000_000_000L, fallDbPerSecond = 20f)
        meter.update(PcmBlock(rate, sine(1_000f, 0.5f, 0.05f), 0))
        val silence = FloatArray(480)
        val during = meter.update(PcmBlock(rate, silence, 500_000_000L))
        assertEquals(-6.02f, during.peakHoldDbfs, 0.05f)
        assertFalse(during.clipped)
        meter.update(PcmBlock(rate, silence, 1_100_000_000L))
        val after = meter.update(PcmBlock(rate, silence, 1_600_000_000L))
        assertTrue("hold should be falling, was ${after.peakHoldDbfs}", after.peakHoldDbfs < -6.02f - 5f)
    }

    @Test
    fun `note names follow equal temperament`() {
        assertEquals("A4 +0¢", noteName(440f))
        assertEquals("C4 +0¢", noteName(261.6256f))
        assertEquals("A4 +20¢", noteName(445.11f))
        assertEquals("C#4 +0¢", noteName(277.1826f))
        assertEquals(null, noteName(0f))
    }

    private fun sine(hz: Float, amplitude: Float, seconds: Float) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun analyze(samples: FloatArray): List<SpectrumFrame> {
        val frames = mutableListOf<SpectrumFrame>()
        SpectrumAnalyzer(rate).process(PcmBlock(rate, samples, 0)) { frames += it }
        return frames
    }
}

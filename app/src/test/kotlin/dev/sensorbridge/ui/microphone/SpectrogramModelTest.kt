package dev.sensorbridge.ui.microphone

import dev.sensorbridge.domain.audio.SpectrumFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrogramModelTest {

    @Test
    fun `log axis gives each octave equal height`() {
        val axis = FrequencyAxis(FrequencyScale.LOG, 100f, 6_400f) // six octaves
        assertEquals(100f, axis.frequencyAt(1f), 0.01f)
        assertEquals(6_400f, axis.frequencyAt(0f), 0.1f)
        assertEquals(800f, axis.frequencyAt(0.5f), 0.1f)
        assertEquals(0.5f, axis.positionOf(800f), 1e-4f)
    }

    @Test
    fun `linear axis is proportional`() {
        val axis = FrequencyAxis(FrequencyScale.LINEAR, 0f, 24_000f)
        assertEquals(12_000f, axis.frequencyAt(0.5f), 0.01f)
        assertEquals(0.75f, axis.positionOf(6_000f), 1e-4f)
    }

    @Test
    fun `bin ranges cover the spectrum top to bottom without gaps on a linear axis`() {
        val ranges = FrequencyAxis(FrequencyScale.LINEAR, 0f, 24_000f).binRanges(rows = 64, binHz = 23.4375f, binCount = 1025)
        assertEquals(64, ranges.size)
        assertTrue(ranges.first().last >= 1_000) // top row holds the highest bins
        assertEquals(0, ranges.last().first)
        ranges.zipWithNext().forEach { (upper, lower) -> assertTrue(lower.last <= upper.first) }
    }

    @Test
    fun `narrow log rows map to a single nearest bin`() {
        val ranges = FrequencyAxis(FrequencyScale.LOG, 30f, 24_000f).binRanges(rows = 256, binHz = 23.4375f, binCount = 1025)
        assertEquals(1, ranges.last().count())
    }

    @Test
    fun `history keeps the newest frames and forgets overwritten ones`() {
        val history = SpectrogramHistory(capacity = 3)
        repeat(5) { history.add(frame(it.toLong())) }
        assertEquals(5L, history.total)
        assertNull(history.frame(1))
        assertEquals(2L, history.frame(2)!!.timestampNanos)
        assertEquals(4L, history.latest()!!.timestampNanos)
        assertNull(history.frame(5))
    }

    @Test
    fun `row level is the loudest bin it covers`() {
        val f = SpectrumFrame(0, 48_000, 8, floatArrayOf(-90f, -20f, -60f, -80f, -100f))
        assertEquals(-20f, f.maxDecibels(0..2), 0f)
    }

    @Test
    fun `decibels normalise into the colour range`() {
        assertEquals(0f, normalizeDb(-130f, -110f, -30f), 0f)
        assertEquals(0.5f, normalizeDb(-70f, -110f, -30f), 1e-6f)
        assertEquals(1f, normalizeDb(0f, -110f, -30f), 0f)
    }

    @Test
    fun `frequency labels are compact on the axis and precise in the readout`() {
        assertEquals("500", formatFrequency(500f))
        assertEquals("2k", formatFrequency(2_000f))
        assertEquals("1.02 kHz", formatFrequency(1_020f, precise = true).replace(',', '.'))
    }

    private fun frame(t: Long) = SpectrumFrame(t, 48_000, 8, FloatArray(5))
}

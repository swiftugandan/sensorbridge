package dev.sensorbridge.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class ToneTrackerTest {

    private val rate = 48_000
    private val random = Random(42)

    @Test
    fun `a whistle over room noise is a clear tone`() {
        val tone = frames { t -> 0.1 * sin(2 * PI * 1_760 * t) + noise(0.003) }.last().clearTone()
        assertNotNull(tone)
        assertEquals(1_760f, tone!!.frequencyHz, 3f)
    }

    @Test
    fun `broadband noise is not a tone`() {
        assertNull(frames { noise(0.05) }.last().clearTone())
    }

    @Test
    fun `strong low rumble is not a tone`() {
        assertNull(frames { t -> 0.3 * sin(2 * PI * 23 * t) + noise(0.003) }.last().clearTone())
    }

    @Test
    fun `a quiet tone near the noise floor is not named`() {
        assertNull(frames { t -> 0.0002 * sin(2 * PI * 440 * t) + noise(0.003) }.last().clearTone())
    }

    @Test
    fun `a tone is named only after it holds steady`() {
        val tracker = ToneTracker(confirmFrames = 4)
        val results = frames(seconds = 0.2) { t -> 0.1 * sin(2 * PI * 440 * t) + noise(0.003) }.map(tracker::update)
        assertNull("first frames are unconfirmed", results.first())
        assertEquals(440f, results.last()!!, 2f)
    }

    @Test
    fun `a blip shorter than the confirmation time is ignored`() {
        val tracker = ToneTracker(confirmFrames = 4)
        val tone = frames(seconds = 0.06) { t -> 0.1 * sin(2 * PI * 440 * t) + noise(0.003) }
        val quiet = frames(seconds = 0.2) { noise(0.003) }
        val results = (tone.take(2) + quiet).map(tracker::update)
        assertNull(results.firstOrNull { it != null })
    }

    @Test
    fun `a tone is held briefly after it stops, then cleared`() {
        val tracker = ToneTracker(confirmFrames = 4, releaseFrames = 12)
        frames(seconds = 0.3) { t -> 0.1 * sin(2 * PI * 440 * t) + noise(0.003) }.forEach { tracker.update(it) }
        val after = frames(seconds = 0.6) { noise(0.003) }.map(tracker::update)
        assertNotNull("held through a short dropout", after.first())
        assertNull("cleared after the release time", after.last())
    }

    @Test
    fun `a new note replaces the old one once confirmed`() {
        val tracker = ToneTracker(confirmFrames = 4)
        frames(seconds = 0.3) { t -> 0.1 * sin(2 * PI * 440 * t) + noise(0.003) }.forEach { tracker.update(it) }
        val next = frames(seconds = 0.3) { t -> 0.1 * sin(2 * PI * 660 * t) + noise(0.003) }.map(tracker::update)
        assertEquals(660f, next.last()!!, 3f)
    }

    @Test
    fun `cents measure musical distance`() {
        assertEquals(1200f, centsBetween(880f, 440f), 0.01f)
        assertEquals(100f, centsBetween(466.1638f, 440f), 0.01f)
    }

    private fun noise(amplitude: Double) = amplitude * random.nextGaussian()

    /** Analyses [seconds] of the signal [sample] (a function of time in seconds) into spectrum frames. */
    private fun frames(seconds: Double = 0.25, sample: (Double) -> Double): List<SpectrumFrame> {
        val samples = FloatArray((rate * seconds).toInt()) { sample(it.toDouble() / rate).toFloat() }
        val frames = mutableListOf<SpectrumFrame>()
        SpectrumAnalyzer(rate, 2048, 1024).process(PcmBlock(rate, samples, 0)) { frames += it }
        return frames
    }
}

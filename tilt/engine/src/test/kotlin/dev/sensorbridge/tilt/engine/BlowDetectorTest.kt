package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.input.BlowDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.pow

class BlowDetectorTest {
    private val rate = 48_000
    private val random = Random(1)

    /** One 20 ms block of noise at [db] dBFS RMS. */
    private fun block(db: Double): FloatArray {
        val amplitude = 10.0.pow(db / 20)
        return FloatArray(rate / 50) { (random.nextGaussian() * amplitude).toFloat() }
    }

    private fun BlowDetector.feed(db: Double, blocks: Int) = (1..blocks).count { accept(block(db), sampleRate = rate) }

    @Test
    fun `a quiet room never jumps`() {
        assertEquals(0, BlowDetector().feed(-65.0, 200))
    }

    @Test
    fun `a sustained blow jumps exactly once, and the next blow jumps again`() {
        val detector = BlowDetector()
        detector.feed(-60.0, 50)
        assertEquals(1, detector.feed(-12.0, 40))
        detector.feed(-60.0, 20)
        assertEquals(1, detector.feed(-12.0, 10))
    }

    @Test
    fun `a single clap block is enough`() {
        val detector = BlowDetector()
        detector.feed(-60.0, 50)
        assertEquals(1, detector.feed(-18.0, 1))
    }

    @Test
    fun `talking at a distance does not jump`() {
        val detector = BlowDetector()
        detector.feed(-60.0, 50)
        assertEquals(0, detector.feed(-40.0, 100))
    }

    @Test
    fun `in a noisy room it takes a louder sound`() {
        val detector = BlowDetector()
        detector.feed(-38.0, 400) // café background
        assertTrue("background ${detector.backgroundDb}", detector.backgroundDb > -42.0)
        assertEquals(0, detector.feed(-28.0, 5))
        assertEquals(1, detector.feed(-10.0, 5))
    }
}

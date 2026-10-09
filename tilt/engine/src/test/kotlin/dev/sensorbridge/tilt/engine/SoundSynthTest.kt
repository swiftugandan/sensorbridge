package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.audio.Cue
import dev.sensorbridge.tilt.engine.audio.SoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoundSynthTest {
    private val rate = 48_000
    private fun SoundSynth.peak(seconds: Double): Float {
        val buffer = FloatArray((rate * seconds).toInt())
        render(buffer, buffer.size)
        return buffer.maxOf { abs(it) }
    }

    @Test
    fun `silent until asked`() {
        assertEquals(0f, SoundSynth(rate).peak(0.5))
    }

    @Test
    fun `every cue sounds, stays in range, and dies away`() {
        for (cue in Cue.entries) {
            val synth = SoundSynth(rate)
            synth.play(cue, 1.0)
            val peak = synth.peak(0.6)
            assertTrue("$cue peak $peak", peak > 0.01f && peak <= 1f)
            synth.peak(10.0) // the win chime rings like a bell
            assertTrue("$cue tail", synth.peak(0.1) < 1e-3f)
        }
    }

    @Test
    fun `rolling gets louder with speed and stops with the marble`() {
        val synth = SoundSynth(rate)
        synth.setRolling(2.0)
        synth.peak(0.2)
        val slow = synth.peak(0.5)
        synth.setRolling(12.0)
        synth.peak(0.2)
        val fast = synth.peak(0.5)
        assertTrue("slow $slow fast $fast", fast > slow * 1.5)
        synth.setRolling(0.0)
        synth.peak(0.5)
        assertTrue(synth.peak(0.1) < 1e-3f)
    }

    @Test
    fun `a pile-up of cues never clips past full scale`() {
        val synth = SoundSynth(rate).apply { volume = 1.0 }
        repeat(60) { synth.play(Cue.entries[it % Cue.entries.size], 1.0) }
        synth.setRolling(30.0)
        assertTrue(synth.peak(1.0) <= 1f)
    }
}

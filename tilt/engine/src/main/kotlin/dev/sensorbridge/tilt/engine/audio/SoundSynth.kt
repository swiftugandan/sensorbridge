package dev.sensorbridge.tilt.engine.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** One-shot sounds the game can play. */
enum class Cue {
    /** A countdown beat. */
    TICK,
    GO,

    /** Marble against a wall; intensity is the impact speed scaled to 0–1. */
    IMPACT,

    /** Marble back on the board after a jump; intensity as for [IMPACT]. */
    LANDING,
    JUMP,
    FALL,
    CHECKPOINT,
    WIN,
}

/**
 * Synthesises every sound in the game in real time, so the rolling noise can follow the marble's speed exactly
 * and impacts sound as hard as they hit. No samples are shipped.
 *
 * - Rolling: a steel ball on a wooden board. The ball's contact excites the board's hollow body, so the sound
 *   is a resonant rumble, not a hiss: rough low-frequency excitation (surface roughness, its pace set by the
 *   ball's speed) and a crackle of grain bumps drive three resonant board modes.
 * - Impacts: a steel marble striking wood. A sharp contact click, the hollow thump of the board's body, and a
 *   short bright metallic ring from the ball, which grows with the force of the hit. Each hit varies slightly in
 *   pitch, so a rattle never sounds like a loop.
 * - Cues: short tones, pitch glides and chimes.
 *
 * [render] allocates nothing. Not thread-safe: the audio thread owns it, and other threads hand it work through
 * a queue (see the app's sound output).
 */
class SoundSynth(private val sampleRate: Int) {

    private val voices = Array(VOICE_COUNT) { Voice() }
    private var noiseState = 0x2545F491
    private var nextVoice = 0

    // Rolling: targets set by the game, smoothed per sample so changes never click.
    private var rollTarget = 0.0
    private var roll = 0.0
    private var lowA = 0.0
    private var lowB = 0.0
    private val modeCoefficient = DoubleArray(BOARD_MODES.size) { 2 * sin(PI * BOARD_MODES[it] / sampleRate) }
    private val modeLow = DoubleArray(BOARD_MODES.size)
    private val modeBand = DoubleArray(BOARD_MODES.size)
    private var grainEnvelope = 0.0
    private val grainDecay = exp(-1.0 / (0.0012 * sampleRate))

    /** Overall level, 0–1. */
    var volume = 0.8

    /** Sets how fast the marble rolls (cells/s), or 0 when it is still or in the air. */
    fun setRolling(speed: Double) {
        rollTarget = (speed / ROLL_FULL_SPEED).coerceIn(0.0, 1.0)
    }

    fun play(cue: Cue, intensity: Double = 1.0) {
        val i = intensity.coerceIn(0.0, 1.0)
        when (cue) {
            Cue.TICK -> tone(880.0, 0.0, 0.18, 0.06, 0.004)
            Cue.GO -> {
                tone(1318.5, 0.0, 0.2, 0.22, 0.004)
                tone(659.3, 0.0, 0.08, 0.22, 0.004)
            }
            Cue.IMPACT -> clack(i)
            Cue.LANDING -> {
                clack(0.35 + 0.65 * i)
                tone(110.0, 0.0, 0.18 + 0.2 * i, 0.07, 0.001) // the board thuds under the falling ball
            }
            Cue.JUMP -> sweep(fromHz = 380.0, toHz = 1800.0, seconds = 0.2, gain = 0.16)
            Cue.FALL -> {
                glide(fromHz = 620.0, toHz = 150.0, seconds = 0.38, gain = 0.22)
                tone(95.0, 0.3, 0.35, 0.18, 0.002)
            }
            Cue.CHECKPOINT -> {
                tone(783.99, 0.0, 0.16, 0.25, 0.004)
                tone(1174.66, 0.09, 0.16, 0.35, 0.004)
            }
            Cue.WIN -> listOf(1046.5, 1318.5, 1568.0, 2093.0).forEachIndexed { k, hz ->
                tone(hz, k * 0.09, 0.17, 0.9, 0.004)
                tone(hz * 2, k * 0.09, 0.03, 0.4, 0.004)
            }
        }
    }

    /** Fills [out] with [frames] mono samples in [-1, 1]. */
    fun render(out: FloatArray, frames: Int) {
        val smooth = 1 - exp(-1.0 / (ROLL_SMOOTHING_SECONDS * sampleRate))
        for (n in 0 until frames) {
            roll += (rollTarget - roll) * smooth
            var sample = rollingSample()
            for (voice in voices) if (voice.active) sample += voice.next()
            out[n] = (tanh(sample * volume * MASTER_DRIVE) / MASTER_DRIVE).toFloat()
        }
    }

    private fun rollingSample(): Double {
        if (roll < 1e-4) return 0.0
        // Roughness: noise smoothed so it fluctuates at a pace proportional to speed (bumps per second).
        val pace = 1 - exp(-2 * PI * (30.0 + 260.0 * roll) / sampleRate)
        lowA += (noise() - lowA) * pace
        lowB += (lowA - lowB) * pace

        // Grain: clicks at random moments, more often the faster the ball runs, each a ~1 ms burst.
        val clicksPerSecond = GRAIN_MIN_RATE + GRAIN_RATE * roll
        if ((noise() + 1) / 2 < clicksPerSecond / sampleRate) grainEnvelope = 0.4 + 0.6 * (noise() + 1) / 2
        grainEnvelope *= grainDecay
        val excitation = lowB * ROUGHNESS + grainEnvelope * noise() * GRAIN_GAIN

        // The board's body: resonant band-pass modes (Chamberlin state-variable filters).
        var body = 0.0
        for (m in BOARD_MODES.indices) {
            val f = modeCoefficient[m]
            modeLow[m] += f * modeBand[m]
            val high = excitation - modeLow[m] - MODE_DAMPING[m] * modeBand[m]
            modeBand[m] += f * high
            body += modeBand[m] * MODE_GAIN[m]
        }
        return ROLL_GAIN * roll.pow(0.6) * body
    }

    /** A sine partial: starts after [delay] s, fades in over [attack] s, then decays with time constant [decay]. */
    private fun tone(hz: Double, delay: Double, gain: Double, decay: Double, attack: Double) =
        voice().start(Kind.TONE, hz, hz, gain, delay, attack, decay, sweepSeconds = 0.0)

    private fun glide(fromHz: Double, toHz: Double, seconds: Double, gain: Double) =
        voice().start(Kind.TONE, fromHz, toHz, gain, 0.0, 0.005, seconds, sweepSeconds = seconds)

    private fun sweep(fromHz: Double, toHz: Double, seconds: Double, gain: Double) =
        voice().start(Kind.NOISE_BAND, fromHz, toHz, gain, 0.0, 0.03, seconds * 0.6, sweepSeconds = seconds)

    /** Steel on wood: contact click, wooden body, metallic ring. [force] is 0–1. */
    private fun clack(force: Double) {
        val gain = 0.05 + 0.55 * force.pow(1.2)
        val detune = 1 + 0.06 * noise() // every hit a little different
        // Contact: a millisecond of bright noise.
        voice().start(Kind.NOISE_BAND, 5200.0 * detune, 5200.0 * detune, gain * 0.9, 0.0, 0.0001, 0.0012, sweepSeconds = 0.0)
        // Wood: a low hollow mode and a mid "tock", both damped fast.
        tone(210.0 * detune, 0.0, gain * 0.55, 0.045, 0.0004)
        tone(780.0 * detune, 0.0, gain * 0.45, 0.018, 0.0003)
        // Steel: inharmonic partials that ring a little longer, louder the harder the hit.
        val ring = gain * (0.12 + 0.3 * force)
        tone(3150.0 * detune, 0.0, ring, 0.05, 0.0002)
        tone(5870.0 * detune, 0.0, ring * 0.6, 0.035, 0.0002)
        tone(8420.0 * detune, 0.0, ring * 0.35, 0.02, 0.0002)
    }

    /** The next free voice, or else the oldest one: new sounds always win. */
    private fun voice(): Voice {
        val free = voices.firstOrNull { !it.active }
        if (free != null) return free
        return voices[nextVoice].also { nextVoice = (nextVoice + 1) % VOICE_COUNT }
    }

    /** White noise in [-1, 1] from a xorshift generator: cheap and allocation-free. */
    private fun noise(): Double {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        noiseState = x
        return x / 2147483648.0
    }

    private enum class Kind { TONE, NOISE_BAND }

    private inner class Voice {
        var active = false
        private var kind = Kind.TONE
        private var fromHz = 0.0
        private var toHz = 0.0
        private var gain = 0.0
        private var delaySamples = 0
        private var attackSamples = 1.0
        private var decayFactor = 0.0
        private var sweepSamples = 0.0
        private var age = 0
        private var envelope = 1.0
        private var phase = 0.0
        private var band = 0.0
        private var low = 0.0

        fun start(kind: Kind, fromHz: Double, toHz: Double, gain: Double, delay: Double, attack: Double, decay: Double, sweepSeconds: Double) {
            this.kind = kind
            this.fromHz = fromHz
            this.toHz = toHz
            this.gain = gain
            delaySamples = (delay * sampleRate).toInt()
            attackSamples = (attack * sampleRate).coerceAtLeast(1.0)
            decayFactor = exp(-1.0 / (decay * sampleRate))
            sweepSamples = sweepSeconds * sampleRate
            age = 0
            envelope = 1.0
            phase = 0.0
            band = 0.0
            low = 0.0
            active = true
        }

        fun next(): Double {
            if (delaySamples > 0) {
                delaySamples--
                return 0.0
            }
            // Exponential glide, so the sweep sounds even in pitch.
            val hz = if (sweepSamples > 0) fromHz * (toHz / fromHz).pow(min(1.0, age / sweepSamples)) else fromHz
            val attack = min(1.0, age / attackSamples)
            age++
            if (age > attackSamples) envelope *= decayFactor
            if (envelope < SILENT) {
                active = false
                return 0.0
            }
            val raw = when (kind) {
                Kind.TONE -> {
                    phase += 2 * PI * hz / sampleRate
                    if (phase > 2 * PI) phase -= 2 * PI
                    sin(phase)
                }
                Kind.NOISE_BAND -> {
                    // Chamberlin state-variable filter, band-pass output.
                    val f = 2 * sin(PI * min(hz, sampleRate / 6.0) / sampleRate)
                    low += f * band
                    val high = noise() - low - BAND_DAMPING * band
                    band += f * high
                    band
                }
            }
            return raw * gain * attack * envelope
        }
    }

    private companion object {
        const val VOICE_COUNT = 24
        const val SILENT = 1e-4
        const val ROLL_FULL_SPEED = 12.0
        const val ROLL_GAIN = 1.0
        const val GRAIN_MIN_RATE = 25.0
        const val GRAIN_RATE = 500.0
        const val GRAIN_GAIN = 0.6
        const val ROUGHNESS = 3.0

        /** Body resonances of a small wooden board (Hz), their damping (1/Q) and mix. */
        val BOARD_MODES = doubleArrayOf(170.0, 330.0, 610.0)
        val MODE_DAMPING = doubleArrayOf(0.12, 0.16, 0.22)
        val MODE_GAIN = doubleArrayOf(0.5, 0.35, 0.22)
        const val ROLL_SMOOTHING_SECONDS = 0.03
        const val BAND_DAMPING = 0.5
        const val MASTER_DRIVE = 1.4
    }
}

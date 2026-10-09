package dev.sensorbridge.tilt.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import dev.sensorbridge.tilt.engine.audio.Cue
import dev.sensorbridge.tilt.engine.audio.SoundSynth
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Plays the game's synthesised sound through a low-latency [AudioTrack].
 *
 * The synthesiser runs on its own audio thread and is touched by nothing else: the game hands it cues through a
 * queue and the rolling speed through a volatile field, so a slow frame never glitches the audio and the audio
 * never blocks a frame.
 */
class SoundOutput(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sampleRate = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    private val burst = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

    private val cues = ConcurrentLinkedQueue<Pair<Cue, Double>>()

    @Volatile
    private var rolling = 0.0

    @Volatile
    var volume = 0.8

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun play(cue: Cue, intensity: Double = 1.0) {
        if (running) cues += cue to intensity
    }

    fun setRolling(speed: Double) {
        rolling = speed
    }

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::run, "tilt-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
        cues.clear()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = runCatching { openTrack() }.getOrNull() ?: return
        val synth = SoundSynth(sampleRate)
        val buffer = FloatArray(burst)
        try {
            track.play()
            while (running) {
                while (true) {
                    val (cue, intensity) = cues.poll() ?: break
                    synth.play(cue, intensity)
                }
                synth.setRolling(rolling)
                synth.volume = volume
                synth.render(buffer, buffer.size)
                if (track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private fun openTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, burst * BYTES_PER_FLOAT * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Keep only a couple of bursts queued: the rolling sound should follow the marble, not trail it.
        track.setBufferSizeInFrames(burst * QUEUED_BURSTS)
        return track
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val QUEUED_BURSTS = 3
        const val STOP_TIMEOUT_MS = 500L
    }
}

package dev.sensorbridge.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.sensorbridge.domain.audio.AudioAccessException
import dev.sensorbridge.domain.audio.AudioSource
import dev.sensorbridge.domain.audio.MicrophoneInfo
import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.domain.audio.PcmBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** [MicrophoneRepository] backed by [AudioRecord]: mono 16-bit PCM at the highest supported common rate. */
class AndroidMicrophone(context: Context) : MicrophoneRepository {

    private val appContext = context.applicationContext

    override val info: MicrophoneInfo? by lazy {
        val rate = CANDIDATE_RATES.firstOrNull { minBufferBytes(it) > 0 } ?: return@lazy null
        MicrophoneInfo(rate, preferredSource())
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun pcm(): Flow<PcmBlock> = flow {
        if (!hasPermission()) throw AudioAccessException.PermissionRequired(Manifest.permission.RECORD_AUDIO)
        val config = info ?: throw AudioAccessException.Unavailable("No supported recording format on this device")
        val record = open(config)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioAccessException.Unavailable("The microphone is in use by another app")
            }
            val buffer = ShortArray(config.sampleRate / BLOCKS_PER_SECOND)
            // read() blocks for at most one block (~20 ms), so cancellation is noticed promptly between reads.
            while (currentCoroutineContext().isActive) {
                val count = record.read(buffer, 0, buffer.size)
                if (count < 0) throw AudioAccessException.Unavailable("Recording failed (AudioRecord error $count)")
                if (count == 0) continue
                val capturedAt = SystemClock.elapsedRealtimeNanos() - count * NANOS_PER_SECOND / config.sampleRate
                emit(PcmBlock(config.sampleRate, FloatArray(count) { buffer[it] / PCM_FULL_SCALE }, capturedAt))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission") // Checked in pcm() before opening.
    private fun open(config: MicrophoneInfo): AudioRecord {
        val source = when (config.source) {
            AudioSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            AudioSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val bufferBytes = maxOf(minBufferBytes(config.sampleRate), config.sampleRate * BYTES_PER_SAMPLE / BUFFERED_BLOCKS_DIVISOR)
        val record = AudioRecord(source, config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioAccessException.Unavailable("The microphone could not be opened")
        }
        return record
    }

    /**
     * Unprocessed audio has no automatic gain control or noise suppression, which would otherwise flatten the
     * spectrum; voice recognition is the next least processed source.
     */
    private fun preferredSource(): AudioSource {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) AudioSource.UNPROCESSED else AudioSource.VOICE_RECOGNITION
    }

    private fun minBufferBytes(rate: Int): Int =
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    private companion object {
        val CANDIDATE_RATES = listOf(48_000, 44_100)
        const val BLOCKS_PER_SECOND = 50
        const val BYTES_PER_SAMPLE = 2

        /** Keep about a quarter second buffered so a briefly busy collector never drops audio. */
        const val BUFFERED_BLOCKS_DIVISOR = 4
        const val PCM_FULL_SCALE = 32_768f
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

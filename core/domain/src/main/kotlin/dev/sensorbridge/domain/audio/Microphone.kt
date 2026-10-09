package dev.sensorbridge.domain.audio

import kotlinx.coroutines.flow.Flow

/** A block of mono PCM samples normalised to [-1, 1]. */
class PcmBlock(
    val sampleRate: Int,
    val samples: FloatArray,
    /** Capture time of the first sample on the `elapsedRealtimeNanos` clock. */
    val timestampNanos: Long,
)

enum class AudioSource {
    /** Raw signal: no gain control, noise suppression or equalisation. Best for analysis. */
    UNPROCESSED,

    /** Tuned for speech recognition: no automatic gain control, minimal processing. */
    VOICE_RECOGNITION,
}

data class MicrophoneInfo(val sampleRate: Int, val source: AudioSource)

interface MicrophoneRepository {
    /** How the microphone will be opened, or null if no supported configuration exists. */
    val info: MicrophoneInfo?

    fun hasPermission(): Boolean

    /**
     * A cold stream of audio. The microphone is opened when collection starts and released as soon as it stops,
     * so it is only in use while someone is collecting.
     *
     * Fails with [AudioAccessException] when recording is not possible.
     */
    fun pcm(): Flow<PcmBlock>
}

sealed class AudioAccessException(message: String) : Exception(message) {
    class PermissionRequired(val permission: String) :
        AudioAccessException("Recording requires the $permission permission")

    /** The device has no usable microphone configuration, or another app (such as a call) holds it. */
    class Unavailable(reason: String) : AudioAccessException(reason)
}

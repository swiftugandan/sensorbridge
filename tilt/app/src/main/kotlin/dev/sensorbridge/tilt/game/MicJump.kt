package dev.sensorbridge.tilt.game

import dev.sensorbridge.domain.audio.MicrophoneRepository
import dev.sensorbridge.tilt.engine.input.BlowDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One block of listening: how loud it was, what would count as a jump, and whether this block did. */
data class MicReading(val levelDb: Double, val thresholdDb: Double, val jumped: Boolean)

/** Listens for a blow or clap. Cold: the microphone is open only while the flow is collected. */
class MicJump(private val microphone: MicrophoneRepository) {
    fun hasPermission() = microphone.hasPermission()

    fun readings(): Flow<MicReading> {
        val detector = BlowDetector()
        return microphone.pcm().map { block ->
            val jumped = detector.accept(block.samples, sampleRate = block.sampleRate)
            MicReading(detector.levelDb, detector.thresholdDb, jumped)
        }
    }
}

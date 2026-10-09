package dev.sensorbridge.tilt.platform

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A latch between whoever asks to jump (the microphone's thread, a tap on the UI thread) and the game loop,
 * which takes at most one jump per frame.
 */
class JumpTrigger {
    private val requested = AtomicBoolean(false)

    fun request() = requested.set(true)

    fun take(): Boolean = requested.getAndSet(false)

    fun clear() = requested.set(false)
}

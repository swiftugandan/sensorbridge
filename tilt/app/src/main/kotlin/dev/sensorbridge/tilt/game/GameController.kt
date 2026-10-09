package dev.sensorbridge.tilt.game

import dev.sensorbridge.tilt.engine.audio.Cue
import dev.sensorbridge.tilt.engine.catalog.PlayableLevel
import dev.sensorbridge.tilt.engine.game.GameEvent
import dev.sensorbridge.tilt.engine.game.GameSession
import dev.sensorbridge.tilt.engine.game.Input
import dev.sensorbridge.tilt.engine.game.Phase
import dev.sensorbridge.tilt.engine.game.SessionConfig
import dev.sensorbridge.tilt.engine.geometry.Vec3
import dev.sensorbridge.tilt.engine.input.Tilt
import dev.sensorbridge.tilt.engine.physics.Physics
import dev.sensorbridge.tilt.platform.Haptics
import dev.sensorbridge.tilt.platform.JumpTrigger
import dev.sensorbridge.tilt.platform.SoundOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** What the heads-up display shows. Changes a few times a second at most. */
data class Hud(
    val phase: Phase,
    val countdown: Int,
    /** Run time in tenths of a second, so the display updates only when its digits change. */
    val tenths: Long,
    val falls: Int,
    val paused: Boolean,
    /** Set once the goal is reached. */
    val finishedSeconds: Double?,
    /** True during the first countdown of a run, when the level's hint is shown. */
    val starting: Boolean,
    /** The countdown is over but waits for the phone to be held still. */
    val waitingForSteady: Boolean,
)

/**
 * Runs one level: steps the simulation at a fixed rate from display frames, feeds it the player's input, and
 * turns what happens into sound, vibration and HUD updates.
 *
 * Threading: [frame] and everything it touches run on the render thread only. Other threads talk to it through
 * [pause], [resume], [restart] and [updateConfig], which queue commands that the next frame applies, and read
 * [hud], a thread-safe snapshot.
 */
class GameController(
    val playable: PlayableLevel,
    config: SessionConfig,
    private val orientation: () -> Vec3,
    private val quarterTurns: () -> Int,
    private val jumps: JumpTrigger,
    private val sound: SoundOutput?,
    private val haptics: Haptics,
) {
    val session = GameSession(playable.level, config)
    private val input = Input()
    private var lastFrameNanos = 0L
    private var accumulator = 0.0
    private var starting = true

    /** Fraction of a step since the last one, for drawing between steps. Render thread only. */
    var interpolation = 0.0
        private set

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data object Restart : Command
        data class Configure(val config: SessionConfig) : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()

    @Volatile
    private var paused = false

    private val hudState = MutableStateFlow(hudNow())
    val hud: StateFlow<Hud> = hudState.asStateFlow()

    val isPaused: Boolean get() = paused

    /** Pauses now (the HUD reflects it at once); the simulation stops at the next frame. */
    fun pause() {
        if (paused || session.phase == Phase.WON) return
        paused = true
        commands += Command.Pause
        hudState.value = hudState.value.copy(paused = true)
    }

    fun resume() {
        if (!paused) return
        paused = false
        commands += Command.Resume
        hudState.value = hudState.value.copy(paused = false)
    }

    fun restart() {
        paused = false
        commands += Command.Restart
        hudState.value = hudState.value.copy(paused = false)
    }

    fun updateConfig(config: SessionConfig) {
        commands += Command.Configure(config)
    }

    /** Advances to the display frame at [frameNanos]. Render thread only. */
    fun frame(frameNanos: Long) {
        applyCommands()
        val dt = if (lastFrameNanos == 0L) 0.0 else ((frameNanos - lastFrameNanos) / 1e9).coerceIn(0.0, MAX_FRAME_SECONDS)
        lastFrameNanos = frameNanos
        if (paused) {
            sound?.setRolling(0.0)
            return
        }
        accumulator += dt
        input.up = Tilt.toScreen(orientation(), quarterTurns())
        input.jump = jumps.take() && session.phase == Phase.PLAYING
        while (accumulator >= Physics.STEP) {
            session.step(input)
            input.jump = false
            accumulator -= Physics.STEP
            session.drainEvents(::react)
        }
        interpolation = accumulator / Physics.STEP
        val ball = session.ball
        sound?.setRolling(if (session.phase == Phase.PLAYING && !ball.airborne) ball.speed else 0.0)
        publishHud()
    }

    /** Forgets frame timing, so the first frame after a gap (pause, surface loss) doesn't jump ahead. */
    fun resetClock() {
        lastFrameNanos = 0L
        accumulator = 0.0
    }

    private fun applyCommands() {
        while (true) {
            when (val command = commands.poll() ?: break) {
                Command.Pause -> sound?.setRolling(0.0)
                Command.Resume -> {
                    resetClock()
                    jumps.clear()
                    session.resumeAfterPause()
                }
                Command.Restart -> {
                    resetClock()
                    jumps.clear()
                    starting = true
                    session.restart()
                }
                is Command.Configure -> session.config = command.config
            }
        }
    }

    private fun react(event: GameEvent) {
        when (event) {
            is GameEvent.Countdown -> sound?.play(Cue.TICK)
            GameEvent.Go -> {
                starting = false
                sound?.play(Cue.GO)
            }
            is GameEvent.Impact -> {
                val strength = (event.speed / HARD_IMPACT).coerceAtMost(1.0)
                sound?.play(Cue.IMPACT, strength)
                if (event.speed > FELT_IMPACT) haptics.knock(strength)
            }
            GameEvent.Jump -> sound?.play(Cue.JUMP)
            is GameEvent.Landing -> {
                sound?.play(Cue.LANDING, (event.speed / HARD_IMPACT).coerceAtMost(1.0))
                haptics.knock(0.4)
            }
            GameEvent.Fell -> {
                sound?.play(Cue.FALL)
                haptics.fall()
            }
            GameEvent.Respawned -> Unit
            GameEvent.Checkpoint -> {
                sound?.play(Cue.CHECKPOINT)
                haptics.checkpoint()
            }
            is GameEvent.Won -> {
                sound?.play(Cue.WIN)
                haptics.win()
            }
        }
    }

    private fun publishHud() {
        val next = hudNow()
        if (next != hudState.value) hudState.value = next
    }

    private fun hudNow() = Hud(
        phase = session.phase,
        countdown = session.countdownRemaining,
        tenths = (session.elapsed * 10).toLong(),
        falls = session.falls,
        paused = paused,
        finishedSeconds = session.elapsed.takeIf { session.phase == Phase.WON },
        starting = starting,
        waitingForSteady = session.waitingForSteady,
    )

    private companion object {
        /** After a long stall, simulate at most this much: the game slows down rather than lurching. */
        const val MAX_FRAME_SECONDS = 0.1
        const val HARD_IMPACT = 10.0
        const val FELT_IMPACT = 1.5
    }
}

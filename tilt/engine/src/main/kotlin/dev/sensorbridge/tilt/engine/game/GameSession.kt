package dev.sensorbridge.tilt.engine.game

import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.geometry.Vec3
import dev.sensorbridge.tilt.engine.input.Neutral
import dev.sensorbridge.tilt.engine.input.Tilt
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.physics.Ball
import dev.sensorbridge.tilt.engine.physics.Physics
import dev.sensorbridge.tilt.engine.physics.StepResult
import dev.sensorbridge.tilt.engine.physics.World
import kotlin.math.hypot

/** How "level" is decided for tilt steering. */
enum class NeutralMode {
    /** However the phone is held during the countdown counts as level. */
    AUTO,

    /** Level means lying flat. */
    FLAT,
}

data class SessionConfig(
    /** Multiplies the board's response to tilt; 1 is the tuned default. */
    val sensitivity: Double = 1.0,
    val neutral: NeutralMode = NeutralMode.AUTO,
) {
    init {
        require(sensitivity in MIN_SENSITIVITY..MAX_SENSITIVITY) { "sensitivity $sensitivity out of range" }
    }

    companion object {
        const val MIN_SENSITIVITY = 0.5
        const val MAX_SENSITIVITY = 1.8
    }
}

/** What the player is doing this step. Mutable and reused; the caller fills it before each [GameSession.step]. */
class Input {
    /** The phone's "up" direction in screen axes (see [Tilt.toScreen]). */
    var up: Vec3 = Vec3.FLAT_UP

    /** True for the single step in which the player asked to jump. */
    var jump = false
}

enum class Phase {
    /** Ready, set: the marble waits at its start while the neutral pose is measured. */
    COUNTDOWN,
    PLAYING,

    /** The marble is dropping into a hole. */
    FALLING,

    /** The marble reappears at the last checkpoint. */
    RESPAWNING,

    /** The marble dropped into the goal. Final. */
    WON,
}

sealed interface GameEvent {
    /** A countdown beat: 3, 2, 1. */
    data class Countdown(val remaining: Int) : GameEvent
    data object Go : GameEvent
    data class Impact(val speed: Double) : GameEvent
    data object Jump : GameEvent
    data class Landing(val speed: Double) : GameEvent
    data object Fell : GameEvent
    data object Respawned : GameEvent
    data object Checkpoint : GameEvent
    data class Won(val seconds: Double) : GameEvent
}

/**
 * One attempt at a level: the rules around the physics.
 *
 * Advanced in fixed steps of [Physics.STEP] by [step]; time spent paused is not simulated at all. The run clock
 * starts at "go" and keeps running through falls, so falling costs time rather than lives.
 * Not thread-safe: drive it from one thread.
 */
class GameSession(val level: Level, config: SessionConfig = SessionConfig()) {

    var config: SessionConfig = config
        set(value) {
            field = value
            if (value.neutral == NeutralMode.FLAT) neutral = Neutral.FLAT
        }

    private val world = World(level)
    private val stepResult = StepResult()
    private val acceleration = DoubleArray(2)

    /** The marble now, and one step ago, so drawing can interpolate between steps. */
    val ball = Ball(level.start.x, level.start.y)
    val previousBall = Ball(level.start.x, level.start.y)

    var phase = Phase.COUNTDOWN
        private set

    /** Seconds since the current phase began. */
    var phaseTime = 0.0
        private set

    /** Run time in seconds, from "go". */
    var elapsed = 0.0
        private set

    var falls = 0
        private set

    /** The hole the marble is falling into, while [phase] is [Phase.FALLING]. */
    var fallingInto: Hole? = null
        private set

    /** Where the marble comes back after a fall. */
    var respawnPoint: Point = level.start
        private set

    private val checkpointsReached = BooleanArray(level.checkpoints.size)
    fun checkpointReached(index: Int) = checkpointsReached[index]

    /** Board tilt relative to neutral, as the sine of the tilt toward screen right and down; for drawing. */
    val tilt = DoubleArray(2)

    private var neutral = Neutral.FLAT
    private var countdownBeats = START_BEATS
    private var neutralSum = Vec3(0.0, 0.0, 0.0)
    private var steadyAnchor: Vec3? = null
    private var steadySeconds = 0.0

    /** True while the countdown has finished its beats but waits for the phone to be held still. */
    val waitingForSteady: Boolean
        get() = phase == Phase.COUNTDOWN && phaseTime >= countdownBeats * BEAT_SECONDS

    private val pending = ArrayList<GameEvent>()

    init {
        enterCountdown(START_BEATS)
    }

    val countdownRemaining: Int
        get() = if (phase == Phase.COUNTDOWN) maxOf(1, countdownBeats - (phaseTime / BEAT_SECONDS).toInt()) else 0

    /** How far through the current fall, sink or reappearance animation, from 0 to 1. */
    val animationProgress: Double
        get() = when (phase) {
            Phase.FALLING -> (phaseTime / FALL_SECONDS).coerceIn(0.0, 1.0)
            Phase.RESPAWNING -> (phaseTime / RESPAWN_SECONDS).coerceIn(0.0, 1.0)
            Phase.WON -> (phaseTime / FALL_SECONDS).coerceIn(0.0, 1.0)
            else -> 0.0
        }

    /** Hands over the events since the last call, oldest first. */
    fun drainEvents(sink: (GameEvent) -> Unit) {
        pending.forEach(sink)
        pending.clear()
    }

    /** Starts over from the beginning, with a fresh countdown. */
    fun restart() {
        ball.placeAt(level.start.x, level.start.y)
        previousBall.copyFrom(ball)
        elapsed = 0.0
        falls = 0
        fallingInto = null
        respawnPoint = level.start
        checkpointsReached.fill(false)
        enterCountdown(START_BEATS)
    }

    /**
     * Continues after a pause. A short countdown re-measures the neutral pose, since the player has likely
     * shifted their grip; the marble keeps its velocity through it.
     */
    fun resumeAfterPause() {
        if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN) enterCountdown(RESUME_BEATS)
    }

    fun step(input: Input) {
        previousBall.copyFrom(ball)
        phaseTime += Physics.STEP
        updateTilt(input.up)
        when (phase) {
            Phase.COUNTDOWN -> countdown(input)
            Phase.PLAYING -> play(input)
            Phase.FALLING -> fall()
            Phase.RESPAWNING -> {
                elapsed += Physics.STEP
                if (phaseTime >= RESPAWN_SECONDS) enter(Phase.PLAYING)
            }
            Phase.WON -> sink()
        }
    }

    /**
     * Counts down, measuring the neutral pose as it goes. The pose is the average over the latest stretch in which
     * the phone has stayed still; any movement starts that stretch again. If the beats run out before the phone
     * has been still for [STEADY_SECONDS], the countdown waits on its last beat (see [waitingForSteady]) for up
     * to [MAX_STEADY_WAIT_SECONDS], so picking the phone up as the countdown ends can't bake a tilt into "level".
     */
    private fun countdown(input: Input) {
        val total = countdownBeats * BEAT_SECONDS
        val beat = (phaseTime / BEAT_SECONDS).toInt()
        val previousBeat = ((phaseTime - Physics.STEP) / BEAT_SECONDS).toInt()
        if (beat != previousBeat && beat < countdownBeats) pending += GameEvent.Countdown(countdownBeats - beat)
        val auto = config.neutral == NeutralMode.AUTO
        if (auto) trackSteadiness(input.up)
        val settled = !auto || steadySeconds >= STEADY_SECONDS || phaseTime >= total + MAX_STEADY_WAIT_SECONDS
        if (phaseTime >= total && settled) {
            neutral = if (auto && neutralSum.length > 0.0) Neutral.of(neutralSum) else Neutral.FLAT
            updateTilt(input.up)
            pending += GameEvent.Go
            enter(Phase.PLAYING)
        }
    }

    private fun trackSteadiness(up: Vec3) {
        val anchor = steadyAnchor
        if (anchor == null || (anchor dot up) / (anchor.length * up.length) < STEADY_COS) {
            steadyAnchor = up
            steadySeconds = 0.0
            neutralSum = Vec3(0.0, 0.0, 0.0)
        }
        steadySeconds += Physics.STEP
        neutralSum += up
    }

    private fun play(input: Input) {
        elapsed += Physics.STEP
        if (input.jump && world.jump(ball)) pending += GameEvent.Jump
        Tilt.acceleration(neutral.relative(input.up), config.sensitivity, acceleration)
        world.step(ball, acceleration[0], acceleration[1], stepResult)
        if (stepResult.impact > 0.0) pending += GameEvent.Impact(stepResult.impact)
        if (stepResult.landing > 0.0) pending += GameEvent.Landing(stepResult.landing)
        val captured = stepResult.captured
        when {
            captured === level.goal -> {
                fallingInto = captured
                pending += GameEvent.Won(elapsed)
                enter(Phase.WON)
            }
            captured != null -> {
                fallingInto = captured
                falls++
                pending += GameEvent.Fell
                enter(Phase.FALLING)
            }
            else -> reachCheckpoints()
        }
    }

    private fun reachCheckpoints() {
        if (ball.airborne) return
        level.checkpoints.forEachIndexed { i, point ->
            if (!checkpointsReached[i] && hypot(point.x - ball.x, point.y - ball.y) < CHECKPOINT_RADIUS) {
                checkpointsReached[i] = true
                respawnPoint = point
                pending += GameEvent.Checkpoint
            }
        }
    }

    private fun fall() {
        elapsed += Physics.STEP
        drawIntoHole()
        if (phaseTime >= FALL_SECONDS) {
            fallingInto = null
            ball.placeAt(respawnPoint.x, respawnPoint.y)
            previousBall.copyFrom(ball)
            pending += GameEvent.Respawned
            enter(Phase.RESPAWNING)
        }
    }

    private fun sink() = drawIntoHole()

    /** Eases the marble toward the centre of the hole it is dropping into. */
    private fun drawIntoHole() {
        val hole = fallingInto ?: return
        val k = (Physics.STEP / SETTLE_SECONDS).coerceAtMost(1.0)
        ball.x += (hole.center.x - ball.x) * k
        ball.y += (hole.center.y - ball.y) * k
        ball.vx = 0.0
        ball.vy = 0.0
    }

    private fun updateTilt(up: Vec3) {
        val relative = neutral.relative(up)
        tilt[0] = -relative.x
        tilt[1] = relative.y
    }

    private fun enterCountdown(beats: Int) {
        countdownBeats = beats
        neutralSum = Vec3(0.0, 0.0, 0.0)
        steadyAnchor = null
        steadySeconds = 0.0
        enter(Phase.COUNTDOWN)
        pending += GameEvent.Countdown(beats)
    }

    private fun enter(next: Phase) {
        phase = next
        phaseTime = 0.0
    }

    companion object {
        const val START_BEATS = 3
        const val RESUME_BEATS = 2
        const val BEAT_SECONDS = 0.6

        /** The phone must stay within [STEADY_DEGREES] of one pose for this long before "go". */
        const val STEADY_SECONDS = 0.5
        const val STEADY_DEGREES = 3.0
        private val STEADY_COS = kotlin.math.cos(Math.toRadians(STEADY_DEGREES))

        /** A shaky hand still gets to play: after this long the latest pose is used anyway. */
        const val MAX_STEADY_WAIT_SECONDS = 3.0

        const val FALL_SECONDS = 0.55
        const val RESPAWN_SECONDS = 0.5
        private const val SETTLE_SECONDS = 0.08

        /** How close the marble must roll to a checkpoint to claim it. */
        const val CHECKPOINT_RADIUS = 0.8
    }
}

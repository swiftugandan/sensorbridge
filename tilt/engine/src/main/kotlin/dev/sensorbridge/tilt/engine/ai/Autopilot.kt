package dev.sensorbridge.tilt.engine.ai

import dev.sensorbridge.tilt.engine.game.GameSession
import dev.sensorbridge.tilt.engine.game.Input
import dev.sensorbridge.tilt.engine.game.NeutralMode
import dev.sensorbridge.tilt.engine.game.Phase
import dev.sensorbridge.tilt.engine.game.SessionConfig
import dev.sensorbridge.tilt.engine.input.Tilt
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.nav.NavGrid
import dev.sensorbridge.tilt.engine.nav.Route
import dev.sensorbridge.tilt.engine.physics.Ball
import dev.sensorbridge.tilt.engine.physics.Physics
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A steady, unhurried player: follows a [Route] by tilting the board, the same way a person does.
 *
 * It sees only what a player sees (where the marble is and how fast it moves) and acts only through the same
 * input, limited to a comfortable tilt. It plans a speed for every point of the route, braking before corners and
 * building speed before jumps, and steers toward a point a little way ahead. Used to prove levels can be finished
 * and to set their par times.
 */
class Autopilot(
    private val route: Route,
    private val sensitivity: Double = 1.0,
    /**
     * An unsteady hand: a slowly wandering push of up to this fraction of the tilt limit, added to every
     * command. 0 steers cleanly; levels must also be finished at [SHAKY], so they never demand precision.
     */
    private val wobble: Double = 0.0,
) {
    private var steps = 0
    private val points = route.points
    private val arc = DoubleArray(points.size).also { s ->
        for (i in 1 until points.size) s[i] = s[i - 1] + points[i - 1].distanceTo(points[i])
    }

    /** Fastest planned speed at each corner, after braking for every corner and jump beyond it. */
    private val cornerSpeed = DoubleArray(points.size)
    private val jumped = BooleanArray(points.size)
    private var leg = 0

    init {
        for (i in points.indices) cornerSpeed[i] = cornerLimit(i)
        for (i in points.size - 2 downTo 0) {
            if (route.jumps[i]) continue // no braking in mid-air: the take-off speed is what it is
            val reach = sqrt(cornerSpeed[i + 1] * cornerSpeed[i + 1] + 2 * BRAKING * (arc[i + 1] - arc[i]))
            cornerSpeed[i] = min(cornerSpeed[i], reach)
        }
    }

    fun steer(ball: Ball, input: Input) {
        input.jump = false
        val t = steps++ * Physics.STEP
        if (ball.airborne) return
        val s = progress(ball)
        val take = (leg..leg + 1).firstOrNull { route.jumps.getOrElse(it) { false } && !jumped[it] && s >= arc[it] - JUMP_LEAD }
        if (take != null) {
            jumped[take] = true
            input.jump = true
        }

        val desired = min(MAX_SPEED, speedLimitAt(s))
        val (tx, ty) = pointAt(s + LOOKAHEAD + LOOKAHEAD_PER_SPEED * ball.speed)
        val dx = tx - ball.x
        val dy = ty - ball.y
        val d = hypot(dx, dy).coerceAtLeast(1e-6)
        var ax = GAIN * (dx / d * desired - ball.vx)
        var ay = GAIN * (dy / d * desired - ball.vy)
        val limit = MAX_TILT * Physics.TILT_ACCELERATION * sensitivity
        if (wobble > 0) {
            // Incommensurate frequencies, so the wandering never settles into a pattern the route could fit.
            ax += wobble * limit * (sin(t * 2.1) + 0.6 * sin(t * 5.3 + 1.0)) / 1.6
            ay += wobble * limit * (sin(t * 1.7 + 2.0) + 0.6 * sin(t * 4.1)) / 1.6
        }
        val a = hypot(ax, ay)
        if (a > limit) {
            ax *= limit / a
            ay *= limit / a
        }
        input.up = Tilt.upFor(ax, ay, sensitivity)
    }

    /** Distance along the route of the point nearest the marble, searching from the current leg onward. */
    private fun progress(ball: Ball): Double {
        var bestLeg = leg
        var bestS = arc[leg]
        var bestD = Double.MAX_VALUE
        for (k in leg until min(leg + SEARCH_LEGS, points.size - 1)) {
            val a = points[k]
            val b = points[k + 1]
            val lx = b.x - a.x
            val ly = b.y - a.y
            val len2 = lx * lx + ly * ly
            val t = if (len2 == 0.0) 0.0 else (((ball.x - a.x) * lx + (ball.y - a.y) * ly) / len2).coerceIn(0.0, 1.0)
            val d = hypot(a.x + lx * t - ball.x, a.y + ly * t - ball.y)
            if (d < bestD - 1e-9) {
                bestD = d
                bestLeg = k
                bestS = arc[k] + t * sqrt(len2)
            }
        }
        leg = bestLeg
        return bestS
    }

    private fun pointAt(s: Double): Pair<Double, Double> {
        if (s >= arc.last()) return points.last().x to points.last().y
        var k = leg
        while (k < points.size - 2 && arc[k + 1] < s) k++
        val a = points[k]
        val b = points[k + 1]
        val t = ((s - arc[k]) / (arc[k + 1] - arc[k])).coerceIn(0.0, 1.0)
        return a.x + (b.x - a.x) * t to a.y + (b.y - a.y) * t
    }

    private fun speedLimitAt(s: Double): Double {
        var limit = Double.MAX_VALUE
        for (i in leg + 1 until points.size) {
            val ahead = arc[i] - s
            if (ahead > BRAKING_HORIZON) break
            limit = min(limit, sqrt(cornerSpeed[i] * cornerSpeed[i] + 2 * BRAKING * ahead.coerceAtLeast(0.0)))
        }
        // While running up to a jump, aim for take-off speed rather than coasting below it.
        val take = leg.takeIf { route.jumps.getOrElse(it) { false } }
        return if (take != null) jumpSpeed(take) else limit
    }

    private fun cornerLimit(i: Int): Double {
        if (i == points.lastIndex) return ARRIVAL_SPEED
        if (route.jumps[i]) return jumpSpeed(i) // take-off
        if (i > 0 && route.jumps[i - 1]) return jumpSpeed(i - 1) // landing
        if (i == 0) return MAX_SPEED
        val inX = points[i].x - points[i - 1].x
        val inY = points[i].y - points[i - 1].y
        val outX = points[i + 1].x - points[i].x
        val outY = points[i + 1].y - points[i].y
        val cos = (inX * outX + inY * outY) / (hypot(inX, inY) * hypot(outX, outY))
        val straightness = (1 + cos) / 2
        return CORNER_SPEED + (MAX_SPEED - CORNER_SPEED) * straightness * straightness * straightness
    }

    /** Fast enough to clear the hazard with time to spare, never slower than a brisk roll. */
    private fun jumpSpeed(leg: Int): Double {
        val length = points[leg].distanceTo(points[leg + 1])
        return maxOf(MIN_JUMP_SPEED, length / (Physics.JUMP_AIR_TIME * JUMP_MARGIN))
    }

    companion object {
        /** Gentle tilt (about 17°) and a modest top speed: a careful human, not a perfect one. */
        const val MAX_TILT = 0.3
        const val MAX_SPEED = 6.0
        const val CORNER_SPEED = 1.8
        const val ARRIVAL_SPEED = 2.2
        const val BRAKING = 10.0
        const val BRAKING_HORIZON = 4.0
        const val GAIN = 7.0
        const val LOOKAHEAD = 0.55
        const val LOOKAHEAD_PER_SPEED = 0.12
        const val SEARCH_LEGS = 3

        const val MIN_JUMP_SPEED = 3.0
        const val JUMP_MARGIN = 0.75

        /** How unsteady the stress-test player is (see [wobble]). */
        const val SHAKY = 0.35

        /** Jump this far before the take-off corner, covering a human's (and a microphone's) reaction delay. */
        const val JUMP_LEAD = 0.15
    }
}

/** How a rehearsal went. */
sealed interface Rehearsal {
    data class Finished(val seconds: Double) : Rehearsal
    data class Failed(val reason: String) : Rehearsal
}

/** Plays a level with the [Autopilot], in simulated time. Deterministic. */
object Rehearsals {
    private const val TIME_LIMIT_SECONDS = 240.0

    /**
     * Plays [level] cleanly for its par time, then again with an unsteady hand: a level counts as finished only
     * if both runs reach the goal without a single fall, so no level hinges on precise control.
     */
    fun rehearse(level: Level): Rehearsal {
        val route = NavGrid(level).routeToGoal() ?: return Rehearsal.Failed("no route to the goal")
        val clean = play(level, Autopilot(route))
        if (clean !is Rehearsal.Finished) return clean
        return when (val shaky = play(level, Autopilot(route, wobble = Autopilot.SHAKY))) {
            is Rehearsal.Finished -> clean
            is Rehearsal.Failed -> Rehearsal.Failed("an unsteady player ${shaky.reason}")
        }
    }

    private fun play(level: Level, pilot: Autopilot): Rehearsal {
        val session = GameSession(level, SessionConfig(neutral = NeutralMode.FLAT))
        val input = Input()
        var steps = 0
        val maxSteps = (TIME_LIMIT_SECONDS / Physics.STEP).toInt()
        while (steps++ < maxSteps) {
            when (session.phase) {
                Phase.WON -> return Rehearsal.Finished(session.elapsed)
                Phase.FALLING -> return Rehearsal.Failed("fell at (%.2f, %.2f)".format(session.ball.x, session.ball.y))
                else -> Unit
            }
            pilot.steer(session.ball, input)
            session.step(input)
        }
        return Rehearsal.Failed("ran out of time at (%.2f, %.2f)".format(session.ball.x, session.ball.y))
    }
}

package dev.sensorbridge.tilt.engine.physics

import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.level.Level
import kotlin.math.min
import kotlin.math.sqrt

/** Tuning for the marble. Distances are in cells, times in seconds. */
object Physics {
    /** The simulation always advances in steps of this size, so play is identical on every display rate. */
    const val STEP = 1.0 / 240

    const val BALL_RADIUS = 0.42

    /**
     * Acceleration at full tilt (cells/s²) before the player's sensitivity. A solid ball rolling down a slope
     * accelerates at 5/7·g·sin θ; this is that for a board of roughly 3 cm cells, so small tilts already move the
     * marble briskly and reversing a tilt reverses it quickly.
     */
    const val TILT_ACCELERATION = 160.0

    /**
     * Rolling resistance: a constant deceleration plus drag proportional to speed. The drag is far stronger than a
     * real marble's: it makes the marble's speed follow the tilt (terminal speed = acceleration / drag, reached in
     * about half a second) instead of storing up momentum, which on a phone-sized board feels like sluggish
     * inertia. It also lets the marble settle quickly when the board is held level.
     */
    const val ROLLING_FRICTION = 1.2
    const val DRAG = 2.0

    /** Fast enough to feel lively, slow enough that a step (0.1 cell) never skips through a one-cell wall. */
    const val MAX_SPEED = 24.0

    /** Fraction of the approach speed a hard wall hit gives back. Slower touches just slide. */
    const val RESTITUTION = 0.42
    const val BOUNCE_THRESHOLD = 0.8

    /** Each hit scrubs some of the speed along the wall too, like a real marble skidding against wood. */
    const val WALL_SCRUB = 0.06

    /** Hits slower than this are silent. */
    const val AUDIBLE_IMPACT = 0.6

    /**
     * Over a hole the marble rides the rim and is pulled in, harder the further it hangs over; it drops once its
     * centre is this far (in ball radii) inside the edge. Tuned to forgive: a marble that only grazes a rim while
     * moving rolls on, while one that stops over a hole, or crosses it slowly, still drops.
     */
    const val RIM_PULL = 45.0
    const val CAPTURE_DEPTH = 0.85

    /** A jump keeps the marble off the board for this long, peaking at [JUMP_HEIGHT] cells. */
    const val JUMP_AIR_TIME = 0.7
    const val JUMP_HEIGHT = 0.8
    const val JUMP_GRAVITY = 8 * JUMP_HEIGHT / (JUMP_AIR_TIME * JUMP_AIR_TIME)
    const val JUMP_VELOCITY = JUMP_GRAVITY * JUMP_AIR_TIME / 2

    fun captureRadius(hole: Hole) = hole.radius - BALL_RADIUS * CAPTURE_DEPTH
}

/** The marble's state. Mutable so the 240 Hz loop allocates nothing. */
class Ball(var x: Double, var y: Double) {
    var vx = 0.0
    var vy = 0.0

    /** Height above the board while jumping. */
    var z = 0.0
    var vz = 0.0

    val airborne: Boolean get() = z > 0.0 || vz > 0.0
    val speed: Double get() = sqrt(vx * vx + vy * vy)

    fun placeAt(x: Double, y: Double) {
        this.x = x
        this.y = y
        vx = 0.0
        vy = 0.0
        z = 0.0
        vz = 0.0
    }

    fun copyFrom(other: Ball) {
        x = other.x
        y = other.y
        vx = other.vx
        vy = other.vy
        z = other.z
        vz = other.vz
    }
}

/** What happened to the marble during one step. Reused between steps. */
class StepResult {
    /** Fastest speed into a wall this step, or 0 if it touched none. */
    var impact = 0.0

    /** Speed when the marble came down from a jump this step, or 0. */
    var landing = 0.0

    /** The hole (or goal) that swallowed the marble this step, if any. */
    var captured: Hole? = null

    internal fun clear() {
        impact = 0.0
        landing = 0.0
        captured = null
    }
}

/** Moves a marble around a level. Pure and deterministic: the same inputs always give the same path. */
class World(val level: Level) {
    private val walls = level.walls
    private val openings: List<Hole> = level.holes + level.goal

    /**
     * Advances [ball] by [Physics.STEP] under board acceleration ([ax], [ay]) in cells/s², already scaled for tilt
     * and sensitivity. While airborne the marble flies straight: tilt only acts through contact with the board.
     */
    fun step(ball: Ball, ax: Double, ay: Double, result: StepResult) {
        result.clear()
        val dt = Physics.STEP
        if (ball.airborne) {
            ball.vz -= Physics.JUMP_GRAVITY * dt
            ball.z += ball.vz * dt
            if (ball.z <= 0.0) {
                ball.z = 0.0
                ball.vz = 0.0
                result.landing = ball.speed
            }
        } else {
            ball.vx += ax * dt
            ball.vy += ay * dt
            rimPull(ball, dt)
            roll(ball, dt)
        }
        limitSpeed(ball)
        ball.x += ball.vx * dt
        ball.y += ball.vy * dt
        result.impact = collide(ball)
        if (!ball.airborne) result.captured = openings.firstOrNull { swallows(it, ball) }
    }

    /** Starts a jump if the marble is on the board. */
    fun jump(ball: Ball): Boolean {
        if (ball.airborne) return false
        ball.vz = Physics.JUMP_VELOCITY
        return true
    }

    private fun swallows(hole: Hole, ball: Ball): Boolean {
        val dx = ball.x - hole.center.x
        val dy = ball.y - hole.center.y
        val r = Physics.captureRadius(hole)
        return dx * dx + dy * dy < r * r
    }

    private fun rimPull(ball: Ball, dt: Double) {
        for (hole in openings) {
            val dx = hole.center.x - ball.x
            val dy = hole.center.y - ball.y
            val d2 = dx * dx + dy * dy
            if (d2 >= hole.radius * hole.radius || d2 == 0.0) continue
            val d = sqrt(d2)
            val overhang = min(1.0, (hole.radius - d) / Physics.BALL_RADIUS)
            val pull = Physics.RIM_PULL * overhang * dt / d
            ball.vx += dx * pull
            ball.vy += dy * pull
        }
    }

    private fun roll(ball: Ball, dt: Double) {
        val speed = ball.speed
        if (speed == 0.0) return
        val slowed = (speed - (Physics.ROLLING_FRICTION + Physics.DRAG * speed) * dt).coerceAtLeast(0.0)
        val scale = slowed / speed
        ball.vx *= scale
        ball.vy *= scale
    }

    private fun limitSpeed(ball: Ball) {
        val speed = ball.speed
        if (speed > Physics.MAX_SPEED) {
            val scale = Physics.MAX_SPEED / speed
            ball.vx *= scale
            ball.vy *= scale
        }
    }

    /**
     * Pushes the marble out of every wall it overlaps and reflects the inward part of its velocity. Two passes
     * settle corners, where pushing out of one wall can push into its neighbour. Returns the hardest hit.
     */
    private fun collide(ball: Ball): Double {
        val r = Physics.BALL_RADIUS
        var hardest = 0.0
        repeat(2) {
            for (wall in walls) {
                val cx = ball.x.coerceIn(wall.left, wall.right)
                val cy = ball.y.coerceIn(wall.top, wall.bottom)
                var nx = ball.x - cx
                var ny = ball.y - cy
                val d2 = nx * nx + ny * ny
                if (d2 >= r * r) continue
                val depth: Double
                if (d2 > 1e-12) {
                    val d = sqrt(d2)
                    nx /= d
                    ny /= d
                    depth = r - d
                } else {
                    // Centre inside the wall (only after a teleport): leave by the nearest face.
                    val exits = doubleArrayOf(ball.x - wall.left, wall.right - ball.x, ball.y - wall.top, wall.bottom - ball.y)
                    val face = exits.indices.minBy { exits[it] }
                    nx = if (face == 0) -1.0 else if (face == 1) 1.0 else 0.0
                    ny = if (face == 2) -1.0 else if (face == 3) 1.0 else 0.0
                    depth = exits[face] + r
                }
                ball.x += nx * depth
                ball.y += ny * depth
                val vn = ball.vx * nx + ball.vy * ny
                if (vn < 0.0) {
                    val impact = -vn
                    val restitution = if (impact > Physics.BOUNCE_THRESHOLD) Physics.RESTITUTION else 0.0
                    // Split into normal and tangential parts: bounce the first, scrub the second.
                    val tx = ball.vx - vn * nx
                    val ty = ball.vy - vn * ny
                    val scrub = 1.0 - Physics.WALL_SCRUB * min(1.0, impact / 4.0)
                    ball.vx = tx * scrub - restitution * vn * nx
                    ball.vy = ty * scrub - restitution * vn * ny
                    if (impact > hardest) hardest = impact
                }
            }
        }
        return if (hardest >= Physics.AUDIBLE_IMPACT) hardest else 0.0
    }

    /** True if a marble centred at ([x], [y]) would overlap no wall. */
    fun fits(x: Double, y: Double, clearance: Double = 0.0): Boolean =
        walls.none { it.distanceTo(x, y) < Physics.BALL_RADIUS + clearance }
}

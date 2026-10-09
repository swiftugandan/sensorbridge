package dev.sensorbridge.tilt.engine.input

import dev.sensorbridge.tilt.engine.geometry.Vec3
import dev.sensorbridge.tilt.engine.physics.Physics
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns the phone's orientation into acceleration on the board.
 *
 * Orientation arrives as the "up" direction (the opposite of gravity) in the phone's natural axes. It is rotated
 * into screen axes, then measured relative to a neutral pose: the way the player naturally holds the phone counts
 * as level, so they need not lie it flat.
 */
object Tilt {

    /**
     * Expresses [up] (natural device axes) in the axes of a screen rotated [quarterTurns] × 90° counter-clockwise
     * from natural, as `Display.getRotation()` reports it.
     */
    fun toScreen(up: Vec3, quarterTurns: Int): Vec3 = when (Math.floorMod(quarterTurns, 4)) {
        1 -> Vec3(-up.y, up.x, up.z)
        2 -> Vec3(-up.x, -up.y, up.z)
        3 -> Vec3(up.y, -up.x, up.z)
        else -> up
    }

    /**
     * Below this tilt (sine of ~0.7°) the board counts as level, so a hand's tremor never sets a resting marble
     * creeping. Beyond it the response is linear, as a real board's is.
     */
    const val DEAD_ZONE = 0.012

    /**
     * Board acceleration in cells/s² for an [up] vector already relative to neutral, written into [out] as
     * (x right, y down). A marble rolls toward whichever screen edge is lowered.
     */
    fun acceleration(up: Vec3, sensitivity: Double, out: DoubleArray) {
        val tx = -up.x
        val ty = up.y
        val magnitude = hypot(tx, ty)
        if (magnitude <= DEAD_ZONE) {
            out[0] = 0.0
            out[1] = 0.0
            return
        }
        val gain = Physics.TILT_ACCELERATION * sensitivity * (magnitude - DEAD_ZONE) / (1 - DEAD_ZONE) / magnitude
        out[0] = tx * gain
        out[1] = ty * gain
    }

    /** The relative "up" that makes [acceleration] produce ([ax], [ay]) at [sensitivity]: the inverse mapping. */
    fun upFor(ax: Double, ay: Double, sensitivity: Double): Vec3 {
        val target = hypot(ax, ay) / (Physics.TILT_ACCELERATION * sensitivity)
        if (target == 0.0) return Vec3.FLAT_UP
        val magnitude = (DEAD_ZONE + target * (1 - DEAD_ZONE)).coerceAtMost(1.0)
        val scale = magnitude / hypot(ax, ay)
        val tx = ax * scale
        val ty = ay * scale
        return Vec3(-tx, ty, kotlin.math.sqrt((1 - magnitude * magnitude).coerceAtLeast(0.0)))
    }
}

/**
 * A neutral pose: the rotation that carries the reference "up" onto the screen's normal, so holding the phone at
 * the reference reads as perfectly level and tilting away from it tilts the board.
 */
class Neutral private constructor(private val m: DoubleArray) {

    fun relative(up: Vec3): Vec3 = Vec3(
        m[0] * up.x + m[1] * up.y + m[2] * up.z,
        m[3] * up.x + m[4] * up.y + m[5] * up.z,
        m[6] * up.x + m[7] * up.y + m[8] * up.z,
    )

    companion object {
        val FLAT = Neutral(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** The shortest rotation taking [reference] to straight up (Rodrigues' formula). */
        fun of(reference: Vec3): Neutral {
            val r = reference.normalized()
            val axis = r cross Vec3.FLAT_UP
            val sinA = axis.length
            val cosA = r dot Vec3.FLAT_UP
            if (sinA < 1e-9) {
                // Already up, or exactly face down, where any horizontal axis will do.
                return if (cosA > 0) FLAT else Neutral(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0))
            }
            val k = axis * (1 / sinA)
            val c = cosA
            val s = sinA
            val t = 1 - c
            return Neutral(
                doubleArrayOf(
                    c + k.x * k.x * t, k.x * k.y * t - k.z * s, k.x * k.z * t + k.y * s,
                    k.y * k.x * t + k.z * s, c + k.y * k.y * t, k.y * k.z * t - k.x * s,
                    k.z * k.x * t - k.y * s, k.z * k.y * t + k.x * s, c + k.z * k.z * t,
                ),
            )
        }

        /** Convenience for tests and previews: a pose tilted [pitch] radians toward the player about the x axis. */
        fun upTiltedTowardPlayer(pitch: Double) = Vec3(0.0, sin(pitch), cos(pitch))
    }
}

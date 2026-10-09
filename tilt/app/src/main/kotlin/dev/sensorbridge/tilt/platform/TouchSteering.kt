package dev.sensorbridge.tilt.platform

import dev.sensorbridge.tilt.engine.geometry.Vec3
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Steering by dragging: the drag from where the finger went down acts like tilting the board that way. A drag
 * of [FULL_DRAG] of the board's width is a full [MAX_TILT] tilt. Written on the UI thread, read by the game loop.
 */
class TouchSteering {
    @Volatile
    var up: Vec3 = Vec3.FLAT_UP
        private set

    /** Sets the drag as fractions of the board width (x right, y down); (0, 0) when the finger lifts. */
    fun drag(dx: Double, dy: Double) {
        val reach = hypot(dx, dy)
        if (reach == 0.0) {
            up = Vec3.FLAT_UP
            return
        }
        val tilt = (reach / FULL_DRAG).coerceAtMost(1.0) * MAX_TILT
        val tx = dx / reach * tilt
        val ty = dy / reach * tilt
        // Inverse of Tilt.acceleration's mapping: the marble rolls toward (tx, ty).
        up = Vec3(-tx, ty, sqrt(1 - tilt * tilt))
    }

    companion object {
        const val FULL_DRAG = 0.25

        /** Sine of the steepest tilt a drag can give (about 24°). */
        const val MAX_TILT = 0.4
    }
}

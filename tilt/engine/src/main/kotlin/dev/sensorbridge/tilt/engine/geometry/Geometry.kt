package dev.sensorbridge.tilt.engine.geometry

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Board coordinates are measured in cells: cell (cx, cy) covers [cx, cx + 1] × [cy, cy + 1], with x to the right
 * and y down, matching the screen.
 */
data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point) = hypot(other.x - x, other.y - y)
}

/** An axis-aligned rectangle in board coordinates. */
data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left < right && top < bottom) { "empty rect $this" }
    }

    /** Distance from ([x], [y]) to the nearest point of the rectangle; zero inside it. */
    fun distanceTo(x: Double, y: Double): Double {
        val dx = maxOf(left - x, 0.0, x - right)
        val dy = maxOf(top - y, 0.0, y - bottom)
        return hypot(dx, dy)
    }
}

/** A round opening in the board. Holes swallow the marble; the goal is a hole that ends the level. */
data class Hole(val center: Point, val radius: Double)

/** A 3D vector, used for orientation: x to the right of the screen, y to its top, z out of the screen. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length
        require(l > 0.0) { "cannot normalise a zero vector" }
        return Vec3(x / l, y / l, z / l)
    }

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    companion object {
        /** "Up" for a phone lying flat on its back. */
        val FLAT_UP = Vec3(0.0, 0.0, 1.0)
    }
}

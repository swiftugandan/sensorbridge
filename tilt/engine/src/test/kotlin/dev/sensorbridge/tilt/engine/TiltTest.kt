package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.geometry.Vec3
import dev.sensorbridge.tilt.engine.input.Neutral
import dev.sensorbridge.tilt.engine.input.Tilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TiltTest {
    private val out = DoubleArray(2)

    @Test
    fun `lowering the right edge rolls the marble right, lowering the top rolls it up`() {
        // Right edge down: "up" leans toward the phone's left.
        Tilt.acceleration(Vec3(-sin(0.2), 0.0, cos(0.2)), 1.0, out)
        assertTrue(out[0] > 0 && out[1] == 0.0)
        // Top edge down: "up" leans toward the bottom of the screen.
        Tilt.acceleration(Vec3(0.0, -sin(0.2), cos(0.2)), 1.0, out)
        assertTrue(out[1] < 0 && out[0] == 0.0)
    }

    @Test
    fun `a hand tremor below the dead zone does nothing`() {
        Tilt.acceleration(Vec3(0.005, 0.005, 1.0).normalized(), 1.0, out)
        assertEquals(0.0, out[0], 0.0)
        assertEquals(0.0, out[1], 0.0)
    }

    @Test
    fun `upFor inverts acceleration`() {
        for ((ax, ay) in listOf(10.0 to 0.0, -3.0 to 7.0, 20.0 to -20.0)) {
            Tilt.acceleration(Tilt.upFor(ax, ay, 1.3), 1.3, out)
            assertEquals(ax, out[0], 1e-9)
            assertEquals(ay, out[1], 1e-9)
        }
    }

    @Test
    fun `screen remapping follows the display rotation`() {
        val up = Vec3(1.0, 2.0, 3.0)
        assertEquals(up, Tilt.toScreen(up, 0))
        assertEquals(Vec3(-2.0, 1.0, 3.0), Tilt.toScreen(up, 1))
        assertEquals(Vec3(-1.0, -2.0, 3.0), Tilt.toScreen(up, 2))
        assertEquals(Vec3(2.0, -1.0, 3.0), Tilt.toScreen(up, 3))
        assertEquals(up, Tilt.toScreen(Tilt.toScreen(Tilt.toScreen(Tilt.toScreen(up, 1), 1), 1), 1))
    }

    @Test
    fun `holding the neutral pose reads as level, tilting from it reads as tilt`() {
        val held = Neutral.upTiltedTowardPlayer(PI / 6) // phone propped at 30°
        val neutral = Neutral.of(held)
        val level = neutral.relative(held)
        assertEquals(0.0, level.x, 1e-12)
        assertEquals(0.0, level.y, 1e-12)
        assertEquals(1.0, level.z, 1e-12)

        // From the held pose, roll the right edge down by 0.1 rad about the phone's long axis.
        val rolled = Vec3(-sin(0.1) * cos(PI / 6), sin(PI / 6), cos(0.1) * cos(PI / 6)).normalized()
        Tilt.acceleration(neutral.relative(rolled), 1.0, out)
        assertTrue("right: ${out[0]}", out[0] > 5.0)
        assertTrue("barely vertical: ${out[1]}", kotlin.math.abs(out[1]) < out[0] * 0.1)
    }

    @Test
    fun `face-down neutral is handled`() {
        val n = Neutral.of(Vec3(0.0, 0.0, -1.0))
        assertEquals(Vec3(0.0, 0.0, 1.0), n.relative(Vec3(0.0, 0.0, -1.0)))
    }
}

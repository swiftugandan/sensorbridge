package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.physics.Ball
import dev.sensorbridge.tilt.engine.physics.Physics
import dev.sensorbridge.tilt.engine.physics.StepResult
import dev.sensorbridge.tilt.engine.physics.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsTest {
    private val result = StepResult()

    private fun World.run(ball: Ball, ax: Double, ay: Double, seconds: Double, onStep: (StepResult) -> Unit = {}) {
        repeat((seconds / Physics.STEP).toInt()) {
            step(ball, ax, ay, result)
            onStep(result)
        }
    }

    @Test
    fun `the marble rolls downhill and speeds up`() {
        val world = World(ARENA)
        val ball = Ball(3.0, 4.0)
        world.run(ball, 12.0, 0.0, 0.3)
        assertTrue("moved right: ${ball.x}", ball.x > 3.3)
        assertEquals(4.0, ball.y, 1e-9)
        val speed = ball.speed
        world.run(ball, 12.0, 0.0, 0.1)
        assertTrue(ball.speed > speed)
    }

    @Test
    fun `on a level board the marble rolls to a stop`() {
        val world = World(ARENA)
        val ball = Ball(3.0, 4.0).apply { vx = 2.0 }
        world.run(ball, 0.0, 0.0, 3.0)
        assertEquals(0.0, ball.speed, 1e-9)
    }

    @Test
    fun `a hard hit bounces back and is reported, a gentle one just stops`() {
        val world = World(ARENA)
        val hard = Ball(10.2, 5.0).apply { vx = 10.0 }
        var impact = 0.0
        world.run(hard, 0.0, 0.0, 0.2) { if (it.impact > 0) impact = it.impact }
        assertTrue("bounced: vx=${hard.vx}", hard.vx < -2.0)
        assertTrue("impact $impact", impact > 9.0)
        assertTrue(hard.x <= 11.0 - Physics.BALL_RADIUS + 1e-9) // never inside the wall

        val gentle = Ball(10.4, 5.0).apply { vx = 0.5 }
        world.run(gentle, 0.0, 0.0, 0.3)
        assertTrue(gentle.vx >= 0.0)
    }

    @Test
    fun `a slow marble passing near a hole's centre is pulled in, a fast one skims over the rim`() {
        val level = level(
            "#########",
            "#.......#",
            "#S..O..G#",
            "#.......#",
            "#########",
        )
        val world = World(level)
        val slow = Ball(3.5, 2.9).apply { vx = 2.5 }
        var captured = false
        world.run(slow, 0.0, 0.0, 1.0) { if (it.captured != null) captured = true }
        assertTrue(captured)

        val fast = Ball(2.5, 2.9).apply { vx = 20.0 }
        var swallowed: Any? = null
        world.run(fast, 0.0, 0.0, 0.25) { if (it.captured != null && swallowed == null) swallowed = it.captured }
        assertTrue("passed the hole: x=${fast.x}", fast.x > 5.5)
        assertNull(swallowed)
    }

    @Test
    fun `a jumping marble clears a hole and lands`() {
        val level = level(
            "##########",
            "#........#",
            "#S..OO..G#",
            "#...OO...#",
            "#........#",
            "##########",
        )
        val world = World(level)
        val ball = Ball(2.5, 3.0).apply { vx = 5.0 }
        assertTrue(world.jump(ball))
        assertFalse("no double jump", world.jump(ball))
        var landed = false
        var captured: Any? = null
        world.run(ball, 0.0, 0.0, 0.75) {
            if (it.landing > 0) landed = true
            if (it.captured != null) captured = it.captured
        }
        assertTrue(landed)
        assertNull(captured)
        assertTrue(ball.x > 6.0)
    }

    @Test
    fun `the goal swallows the marble like a hole`() {
        val world = World(ARENA)
        val ball = Ball(8.0, 3.5)
        var captured: Any? = null
        world.run(ball, 6.0, 0.0, 1.5) { if (it.captured != null && captured == null) captured = it.captured }
        assertNotNull(captured)
        assertSame(ARENA.goal, captured)
    }
}

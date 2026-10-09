package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.game.GameEvent
import dev.sensorbridge.tilt.engine.game.GameSession
import dev.sensorbridge.tilt.engine.game.Input
import dev.sensorbridge.tilt.engine.game.NeutralMode
import dev.sensorbridge.tilt.engine.game.Phase
import dev.sensorbridge.tilt.engine.game.Scoring
import dev.sensorbridge.tilt.engine.game.SessionConfig
import dev.sensorbridge.tilt.engine.input.Neutral
import dev.sensorbridge.tilt.engine.input.Tilt
import dev.sensorbridge.tilt.engine.level.LevelValidator
import dev.sensorbridge.tilt.engine.nav.NavGrid
import dev.sensorbridge.tilt.engine.physics.Physics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAndSessionTest {

    @Test
    fun `routes go around holes and jump only when they must`() {
        val around = level(
            "#########",
            "#S......#",
            "#..O....#",
            "#......G#",
            "#########",
        )
        val route = NavGrid(around).routeToGoal()!!
        assertEquals(0, route.jumpCount)
        assertEquals(around.start, route.points.first())
        assertEquals(around.goal.center, route.points.last())

        val gap = level(
            "##########",
            "#S..OO..G#",
            "#...OO...#",
            "##########",
        )
        assertEquals(1, NavGrid(gap).routeToGoal()!!.jumpCount)

        val walled = level(
            "#######",
            "#S.#.G#",
            "#######",
        )
        assertNull(NavGrid(walled).routeToGoal())
        assertTrue(LevelValidator.problems(walled).any { "no way" in it })
    }

    private fun countdownSteps(beats: Int) = (beats * GameSession.BEAT_SECONDS / Physics.STEP).toInt() + 2

    @Test
    fun `the countdown beats three times, then go, and the clock starts at go`() {
        val session = GameSession(ARENA)
        val input = Input()
        val events = mutableListOf<GameEvent>()
        repeat(countdownSteps(3)) {
            session.step(input)
            session.drainEvents { events += it }
        }
        assertEquals(
            listOf(GameEvent.Countdown(3), GameEvent.Countdown(2), GameEvent.Countdown(1), GameEvent.Go),
            events,
        )
        assertEquals(Phase.PLAYING, session.phase)
        assertTrue(session.elapsed < 0.02)
    }

    @Test
    fun `with automatic neutral, holding still at any angle keeps the marble still`() {
        val session = GameSession(ARENA, SessionConfig(neutral = NeutralMode.AUTO))
        val input = Input().apply { up = Neutral.upTiltedTowardPlayer(Math.toRadians(35.0)) }
        repeat(countdownSteps(3) + 240) { session.step(input) }
        assertEquals(Phase.PLAYING, session.phase)
        assertEquals(0.0, session.ball.speed, 1e-9)

        // Same pose with a flat neutral: the marble rolls toward the player.
        val flat = GameSession(ARENA, SessionConfig(neutral = NeutralMode.FLAT))
        repeat(countdownSteps(3) + 60) { flat.step(input) }
        assertTrue(flat.ball.vy > 1.0)
    }

    @Test
    fun `go waits until the phone is held still, and that still pose becomes level`() {
        val session = GameSession(ARENA, SessionConfig(neutral = NeutralMode.AUTO))
        val input = Input()
        // Swing the phone through the whole countdown, then hold it at 40°.
        var step = 0
        while (step * Physics.STEP < GameSession.START_BEATS * GameSession.BEAT_SECONDS + 0.3) {
            input.up = Neutral.upTiltedTowardPlayer(Math.toRadians(10.0 + 30 * kotlin.math.sin(step++ * 0.01)))
            session.step(input)
        }
        assertEquals(Phase.COUNTDOWN, session.phase)
        assertTrue(session.waitingForSteady)
        assertEquals(1, session.countdownRemaining)
        input.up = Neutral.upTiltedTowardPlayer(Math.toRadians(40.0))
        repeat((GameSession.STEADY_SECONDS / Physics.STEP).toInt() + 2) { session.step(input) }
        assertEquals(Phase.PLAYING, session.phase)
        repeat(240) { session.step(input) }
        assertEquals("held pose is level", 0.0, session.ball.speed, 1e-9)
    }

    @Test
    fun `falling costs time and returns the marble to the last checkpoint`() {
        val level = level(
            "##########",
            "#S..C..O.#",
            "#......G.#",
            "##########",
        )
        val session = GameSession(level, SessionConfig(neutral = NeutralMode.FLAT))
        val input = Input().apply { up = Tilt.upFor(15.0, 0.0, 1.0) }
        val events = mutableListOf<GameEvent>()
        var steps = 0
        while (session.phase != Phase.FALLING && steps++ < 2000) {
            session.step(input)
            session.drainEvents { events += it }
        }
        assertTrue(GameEvent.Checkpoint in events)
        assertTrue(GameEvent.Fell in events)
        input.up = Neutral.upTiltedTowardPlayer(0.0)
        repeat(((GameSession.FALL_SECONDS + GameSession.RESPAWN_SECONDS) / Physics.STEP).toInt() + 2) { session.step(input) }
        assertEquals(Phase.PLAYING, session.phase)
        assertEquals(level.checkpoints.single().x, session.ball.x, 1e-9)
        assertEquals(1, session.falls)
    }

    @Test
    fun `stars compare a run to par`() {
        assertEquals(3, Scoring.stars(10.0, 10.0))
        assertEquals(2, Scoring.stars(14.9, 10.0))
        assertEquals(1, Scoring.stars(15.1, 10.0))
        assertEquals(11.5, Scoring.parFor(10.0), 1e-9)
    }
}

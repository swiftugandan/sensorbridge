package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.ai.Rehearsal
import dev.sensorbridge.tilt.engine.ai.Rehearsals
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.engine.gen.GeneratorSpec
import dev.sensorbridge.tilt.engine.gen.LevelGenerator
import dev.sensorbridge.tilt.engine.level.LevelValidator
import dev.sensorbridge.tilt.engine.nav.NavGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CatalogTest {

    @Test
    fun `every training level is valid, titled as in its file, and finished by the autopilot`() {
        val jumps = LevelCatalog.training.map { entry ->
            val playable = entry.load()
            assertEquals(entry.title, playable.level.name)
            assertTrue(LevelValidator.problems(playable.level).isEmpty())
            assertTrue("${entry.title} par ${playable.parSeconds}", playable.parSeconds in 3.0..90.0)
            NavGrid(playable.level).routeToGoal()!!.jumpCount
        }
        assertEquals("only Leap and later teach jumping", listOf(0, 0), jumps.take(2))
        assertTrue(jumps[2] > 0)
    }

    @Test
    fun `every labyrinth generates, is finished by the autopilot, and gets harder`() {
        val pars = LevelCatalog.labyrinths.map { entry ->
            val playable = entry.load()
            val route = NavGrid(playable.level).routeToGoal()!!
            assertEquals("${entry.title} jumps", LevelCatalog.campaignSpec(entry.number).gaps, route.jumpCount)
            playable.parSeconds
        }
        assertTrue("later levels take longer: $pars", pars.takeLast(5).average() > pars.take(5).average())
    }

    @Test
    fun `generation is deterministic`() {
        val spec = GeneratorSpec(4, 6, loops = 0.2, traps = 0.4, gaps = 1, decoys = 0.3, checkpoint = true)
        val a = LevelGenerator.generate("A", 42, spec)
        val b = LevelGenerator.generate("A", 42, spec)
        assertEquals(a.rehearsalSeconds, b.rehearsalSeconds, 0.0)
        assertEquals(a.level.holes, b.level.holes)
        assertEquals(a.level.walls, b.level.walls)
        val c = LevelGenerator.generate("A", 43, spec)
        assertTrue(c.level.walls != a.level.walls || c.level.holes != a.level.holes)
    }

    @Test
    fun `daily challenges differ by day and resolve by id`() {
        val today = LevelCatalog.daily(LocalDate.of(2026, 10, 9))
        val tomorrow = LevelCatalog.daily(LocalDate.of(2026, 10, 10))
        val a = today.load().level
        assertTrue(a.walls != tomorrow.load().level.walls)
        assertEquals(today.id, LevelCatalog.byId("daily/2026-10-09")!!.id)
        assertTrue(Rehearsals.rehearse(a) is Rehearsal.Finished)
    }
}

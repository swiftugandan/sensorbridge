package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.geometry.Rect
import dev.sensorbridge.tilt.engine.level.LevelFormatException
import dev.sensorbridge.tilt.engine.level.LevelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelParserTest {

    @Test
    fun `markers are centred on their square`() {
        val level = level(
            "########",
            "#SS....#",
            "#SS.O..#",
            "#...GG.#",
            "#...GG.#",
            "########",
        )
        assertEquals(Point(2.0, 2.0), level.start)
        assertEquals(Point(5.0, 4.0), level.goal.center)
        assertEquals(1.0, level.goal.radius, 0.0)
        assertEquals(listOf(Point(4.5, 2.5)), level.holes.map { it.center })
        assertEquals(0.5, level.holes.single().radius, 0.0)
        assertTrue(level.isWall(0, 0))
        assertFalse(level.isWall(4, 2)) // a hole is on the floor
        assertTrue(level.isWall(-1, 3)) // outside counts as wall
    }

    @Test
    fun `header is read and the hint is optional`() {
        val level = LevelParser.parse("name: Two\nhint: Roll it.\n---\n#####\n#S.G#\n#####")
        assertEquals("Two", level.name)
        assertEquals("Roll it.", level.hint)
        assertNull(LevelParser.parse("name: x\n---\n#####\n#S.G#\n#####").hint)
    }

    @Test
    fun `walls merge into few rectangles that cover exactly the wall cells`() {
        val level = level(
            "#######",
            "#S....#",
            "#.###.#",
            "#.###G#",
            "#######",
        )
        assertTrue(Rect(2.0, 2.0, 5.0, 4.0) in level.walls)
        assertEquals(5, level.walls.size) // top, bottom, left, right, the block
        for (y in 0 until level.height) for (x in 0 until level.width) {
            val covered = level.walls.count { x + 0.5 in it.left..it.right && y + 0.5 in it.top..it.bottom }
            assertEquals("cell ($x, $y)", if (level.isWall(x, y)) 1 else 0, covered)
        }
    }

    @Test
    fun `malformed levels are rejected with the reason`() {
        fun reason(vararg rows: String) = assertThrows(LevelFormatException::class.java) { level(*rows) }.message!!
        assertTrue(reason("#####", "#S.G#", "####").contains("row 3"))
        assertTrue(reason("#####", "#SXG#", "#####").contains("unknown tile 'X'"))
        assertTrue(reason("#####", "#..G#", "#####").contains("exactly one start"))
        assertTrue(reason("######", "#SSSG#", "######").contains("1×1 or 2×2"))
        assertThrows(LevelFormatException::class.java) { LevelParser.parse("#####\n#S.G#\n#####") }
    }
}

package dev.sensorbridge.tilt.engine.level

import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.geometry.Point

class LevelFormatException(message: String) : Exception(message)

/**
 * Reads the text level format:
 *
 * ```
 * name: First roll
 * hint: Tilt your phone to roll the marble.
 * ---
 * #########
 * #SS.....#
 * #SS..O..#
 * #.....GG#
 * #.....GG#
 * #########
 * ```
 *
 * Header lines are `key: value` (name is required, hint optional). Below `---`, one character per cell:
 * `#` wall, `.` floor, `S` start, `G` goal, `O` hole, `C` checkpoint. Every marker is a 1×1 or 2×2 square of its
 * letter, centred where the square's centre is, so a marker can sit in the middle of a two-cell corridor.
 * Markers lie on the floor.
 */
object LevelParser {

    fun parse(text: String): Level {
        val lines = text.lines()
        val separator = lines.indexOfFirst { it.trim() == "---" }
        if (separator < 0) throw LevelFormatException("missing '---' between the header and the grid")
        val header = parseHeader(lines.subList(0, separator))
        val rows = lines.drop(separator + 1).map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        if (rows.isEmpty()) throw LevelFormatException("the grid is empty")
        val width = rows[0].length
        rows.forEachIndexed { i, row ->
            if (row.length != width) {
                throw LevelFormatException("grid row ${i + 1} has ${row.length} cells; the first row has $width")
            }
            row.forEachIndexed { x, c ->
                if (c !in TILES) throw LevelFormatException("unknown tile '$c' at column ${x + 1}, row ${i + 1}")
            }
        }
        val height = rows.size
        val markers = markers(rows)

        fun single(c: Char, what: String): Marker {
            val found = markers[c].orEmpty()
            if (found.size != 1) throw LevelFormatException("expected exactly one $what ('$c'), found ${found.size}")
            return found.single()
        }

        val start = single(START, "start")
        val goal = single(GOAL, "goal")
        return Level.fromCells(
            name = header["name"] ?: throw LevelFormatException("missing 'name:' header"),
            hint = header["hint"],
            width = width,
            height = height,
            isWall = { x, y -> rows[y][x] == WALL },
            start = start.center,
            goal = Hole(goal.center, goal.size * GOAL_RADIUS_PER_CELL),
            holes = markers[HOLE].orEmpty().map { Hole(it.center, it.size * HOLE_RADIUS_PER_CELL) },
            checkpoints = markers[CHECKPOINT].orEmpty().map { it.center },
        )
    }

    private fun parseHeader(lines: List<String>): Map<String, String> = lines
        .filter { it.isNotBlank() }
        .associate { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw LevelFormatException("header line '$line' is not 'key: value'")
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }

    private class Marker(val center: Point, val size: Int)

    /** Groups each marker letter into 4-connected squares and checks every group is 1×1 or 2×2. */
    private fun markers(rows: List<String>): Map<Char, List<Marker>> {
        val width = rows[0].length
        val seen = Array(rows.size) { BooleanArray(width) }
        val found = mutableMapOf<Char, MutableList<Marker>>()
        for (y in rows.indices) for (x in 0 until width) {
            val c = rows[y][x]
            if (c == WALL || c == FLOOR || seen[y][x]) continue
            val cells = mutableListOf<Pair<Int, Int>>()
            val stack = ArrayDeque(listOf(x to y))
            seen[y][x] = true
            while (stack.isNotEmpty()) {
                val (cx, cy) = stack.removeLast()
                cells += cx to cy
                for ((nx, ny) in listOf(cx + 1 to cy, cx - 1 to cy, cx to cy + 1, cx to cy - 1)) {
                    if (ny in rows.indices && nx in 0 until width && !seen[ny][nx] && rows[ny][nx] == c) {
                        seen[ny][nx] = true
                        stack += nx to ny
                    }
                }
            }
            val minX = cells.minOf { it.first }
            val minY = cells.minOf { it.second }
            val size = cells.maxOf { it.first } - minX + 1
            val square = size == cells.maxOf { it.second } - minY + 1 && cells.size == size * size
            if (!square || size > 2) {
                throw LevelFormatException("'$c' at column ${minX + 1}, row ${minY + 1} must be a 1×1 or 2×2 square")
            }
            found.getOrPut(c) { mutableListOf() } += Marker(Point(minX + size / 2.0, minY + size / 2.0), size)
        }
        return found
    }

    private const val WALL = '#'
    private const val FLOOR = '.'
    private const val START = 'S'
    private const val GOAL = 'G'
    private const val HOLE = 'O'
    private const val CHECKPOINT = 'C'
    private val TILES = setOf(WALL, FLOOR, START, GOAL, HOLE, CHECKPOINT)

    /** A 1×1 hole fills its cell; a 2×2 hole fills a two-cell corridor and has to be jumped. */
    const val HOLE_RADIUS_PER_CELL = 0.5
    const val GOAL_RADIUS_PER_CELL = 0.5
}

package dev.sensorbridge.tilt.engine.level

import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.geometry.Rect

/**
 * A labyrinth: a grid of wall and floor cells, plus the round features that sit on the floor.
 *
 * Immutable. Walls are stored per cell (for validation and drawing) and as merged rectangles (for collision),
 * so a marble sliding along a straight wall never catches on the seam between two cells.
 */
class Level(
    val name: String,
    /** Shown while the level starts; teaches the mechanic a training level introduces. */
    val hint: String?,
    val width: Int,
    val height: Int,
    private val wallCells: BooleanArray,
    val start: Point,
    val goal: Hole,
    val holes: List<Hole>,
    val checkpoints: List<Point>,
) {
    init {
        require(width > 0 && height > 0) { "level must have cells" }
        require(wallCells.size == width * height) { "wall grid is ${wallCells.size} cells, expected ${width * height}" }
    }

    /** True for wall cells and for anything outside the grid. */
    fun isWall(cx: Int, cy: Int): Boolean =
        cx !in 0 until width || cy !in 0 until height || wallCells[cy * width + cx]

    val walls: List<Rect> by lazy { WallMesher.mesh(width, height, ::isWall) }

    companion object {
        /** Builds a level from a predicate over cells; used by the parser and the generator. */
        fun fromCells(
            name: String,
            hint: String?,
            width: Int,
            height: Int,
            isWall: (Int, Int) -> Boolean,
            start: Point,
            goal: Hole,
            holes: List<Hole>,
            checkpoints: List<Point>,
        ) = Level(
            name, hint, width, height,
            BooleanArray(width * height) { isWall(it % width, it / width) },
            start, goal, holes, checkpoints,
        )
    }
}

/** Collapses wall cells into few rectangles: horizontal runs per row, then identical runs stacked across rows. */
internal object WallMesher {
    fun mesh(width: Int, height: Int, isWall: (Int, Int) -> Boolean): List<Rect> {
        data class Run(val x0: Int, val x1: Int, val y0: Int, var y1: Int)

        val done = mutableListOf<Run>()
        var open = mutableMapOf<Pair<Int, Int>, Run>() // runs from the previous row, by span
        for (y in 0 until height) {
            val next = mutableMapOf<Pair<Int, Int>, Run>()
            var x = 0
            while (x < width) {
                if (!isWall(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < width && isWall(x, y)) x++
                val span = start to x
                val run = open.remove(span)?.also { it.y1 = y + 1 } ?: Run(start, x, y, y + 1)
                next[span] = run
            }
            done += open.values
            open = next
        }
        done += open.values
        return done
            .sortedWith(compareBy({ it.y0 }, { it.x0 }))
            .map { Rect(it.x0.toDouble(), it.y0.toDouble(), it.x1.toDouble(), it.y1.toDouble()) }
    }
}

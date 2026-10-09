package dev.sensorbridge.tilt.engine.level

import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.nav.NavGrid
import dev.sensorbridge.tilt.engine.physics.Physics

/** Checks a level is well formed and can be finished. Returns human-readable problems; empty means valid. */
object LevelValidator {

    fun problems(level: Level): List<String> = buildList {
        for (x in 0 until level.width) {
            if (!level.isWall(x, 0) || !level.isWall(x, level.height - 1)) add("the border is open at column ${x + 1}")
        }
        for (y in 0 until level.height) {
            if (!level.isWall(0, y) || !level.isWall(level.width - 1, y)) add("the border is open at row ${y + 1}")
        }
        val grid = NavGrid(level)
        fun checkSpot(what: String, p: Point) {
            if (!grid.isFree(p.x, p.y)) add("the $what at (${p.x}, ${p.y}) touches a wall or hangs over a hole")
        }
        checkSpot("start", level.start)
        level.checkpoints.forEach { checkSpot("checkpoint", it) }
        if (level.walls.any { it.distanceTo(level.goal.center.x, level.goal.center.y) < Physics.BALL_RADIUS }) {
            add("the goal is too close to a wall for the marble to drop in")
        }
        val openings = level.holes + level.goal
        for ((i, a) in openings.withIndex()) for (b in openings.drop(i + 1)) {
            if (a.center.distanceTo(b.center) < a.radius + b.radius) add("holes at ${a.center} and ${b.center} overlap")
        }
        if (isEmpty() && grid.routeToGoal() == null) add("there is no way from the start to the goal")
    }

    fun requireValid(level: Level): Level {
        val problems = problems(level)
        require(problems.isEmpty()) { "'${level.name}' is invalid: ${problems.joinToString("; ")}" }
        return level
    }
}

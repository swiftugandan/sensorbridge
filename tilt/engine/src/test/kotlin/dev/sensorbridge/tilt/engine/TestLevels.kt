package dev.sensorbridge.tilt.engine

import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.level.LevelParser

/** Builds a level from grid rows, for tests. */
fun level(vararg rows: String, name: String = "Test"): Level =
    LevelParser.parse("name: $name\n---\n" + rows.joinToString("\n"))

/** An open 12×8 arena: start on the left, goal on the right, nothing in between. */
val ARENA = level(
    "############",
    "#..........#",
    "#..........#",
    "#.S......G.#",
    "#..........#",
    "#..........#",
    "#..........#",
    "############",
)

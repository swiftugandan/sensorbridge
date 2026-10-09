package dev.sensorbridge.tilt.engine.gen

import dev.sensorbridge.tilt.engine.ai.Rehearsal
import dev.sensorbridge.tilt.engine.ai.Rehearsals
import dev.sensorbridge.tilt.engine.geometry.Hole
import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.level.LevelValidator
import dev.sensorbridge.tilt.engine.nav.NavGrid

/** Shape and difficulty of a generated labyrinth. */
data class GeneratorSpec(
    val roomsWide: Int,
    val roomsHigh: Int,
    /** Chance that a dead end is opened into a loop, giving alternative (often riskier) routes. */
    val loops: Double,
    /** Chance that each room or doorway on the way to the goal holds a hole to steer around. */
    val traps: Double,
    /** How many rooms on the way are entirely a hole, to be jumped. */
    val gaps: Int,
    /** Chance that a room off the main route holds holes too, so detours are not free. */
    val decoys: Double,
    val checkpoint: Boolean,
) {
    init {
        require(roomsWide >= 2 && roomsHigh >= 2) { "need at least 2×2 rooms" }
        require(gaps >= 0) { "gaps must be non-negative" }
    }
}

/** A generated level, with the rehearsal time that proved it can be finished. */
class GeneratedLevel(val level: Level, val rehearsalSeconds: Double)

/**
 * Builds labyrinths from a seed: the same seed and spec give the same level on every device, forever.
 *
 * Rooms of 2×2 floor cells sit on a grid, separated by one-cell walls. A randomised depth-first search carves a
 * spanning tree through them (long winding corridors), some dead ends are opened into loops, then the start and the
 * goal are put as far apart as the maze allows. Holes go on and around the route to the goal, each only where a
 * comfortable lane still passes it. Every candidate must pass [LevelValidator] and be finished, without a fall, by
 * both a steady and an unsteady autopilot; a candidate that fails is discarded for the next seed in the sequence,
 * so every level handed out is known to be fair.
 */
object LevelGenerator {

    fun generate(name: String, seed: Long, spec: GeneratorSpec): GeneratedLevel {
        val random = SplitMix64(seed)
        repeat(MAX_ATTEMPTS) {
            val level = candidate(name, SplitMix64(random.nextLong()), spec) ?: return@repeat
            if (LevelValidator.problems(level).isNotEmpty()) return@repeat
            val rehearsal = Rehearsals.rehearse(level)
            if (rehearsal is Rehearsal.Finished) return GeneratedLevel(level, rehearsal.seconds)
        }
        error("no playable level for seed $seed after $MAX_ATTEMPTS attempts")
    }

    private class Maze(val wide: Int, val high: Int) {
        val count = wide * high
        private val open = HashSet<Long>()

        fun connect(a: Int, b: Int) {
            open += key(a, b)
        }

        fun connected(a: Int, b: Int) = key(a, b) in open
        fun neighbours(r: Int): List<Int> = buildList {
            val x = r % wide
            val y = r / wide
            if (x > 0) add(r - 1)
            if (x < wide - 1) add(r + 1)
            if (y > 0) add(r - wide)
            if (y < high - 1) add(r + wide)
        }

        fun exits(r: Int) = neighbours(r).filter { connected(r, it) }

        /** Breadth-first distances and parents from [from]. */
        fun search(from: Int): Pair<IntArray, IntArray> {
            val distance = IntArray(count) { -1 }
            val parent = IntArray(count) { -1 }
            val queue = ArrayDeque(listOf(from))
            distance[from] = 0
            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                for (n in exits(r)) if (distance[n] < 0) {
                    distance[n] = distance[r] + 1
                    parent[n] = r
                    queue += n
                }
            }
            return distance to parent
        }

        private fun key(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
    }

    private fun candidate(name: String, random: SplitMix64, spec: GeneratorSpec): Level? {
        val maze = carve(spec, random)
        val corners = listOf(0, spec.roomsWide - 1, maze.count - spec.roomsWide, maze.count - 1)
        val startRoom = corners[random.nextInt(corners.size)]
        val (distance, parent) = maze.search(startRoom)
        val farthest = distance.max()
        val goalRoom = distance.indices.filter { distance[it] == farthest }.let { it[random.nextInt(it.size)] }
        val path = generateSequence(goalRoom) { parent[it].takeIf { p -> p >= 0 } }.toList().reversed()

        val width = spec.roomsWide * ROOM_PITCH + 1
        val height = spec.roomsHigh * ROOM_PITCH + 1
        val floor = BooleanArray(width * height)
        fun setFloor(x: Int, y: Int) {
            floor[y * width + x] = true
        }
        for (r in 0 until maze.count) {
            val (x0, y0) = roomOrigin(r, spec)
            for (dy in 0..1) for (dx in 0..1) setFloor(x0 + dx, y0 + dy)
            for (n in maze.exits(r)) if (n > r) doorway(r, n, spec).forEach { (x, y) -> setFloor(x, y) }
        }

        val holes = mutableListOf<Hole>()
        val onPath = path.toSet()
        val gapRooms = chooseGaps(path, maze, spec, random)
        if (gapRooms.size < spec.gaps) return null // this maze has too few straight runs; try another
        for (r in gapRooms) holes += Hole(roomCentre(r, spec), GAP_RADIUS)

        // Checkpoints every few rooms along the way, so a fall never costs more than a short stretch.
        val checkpointRooms = if (spec.checkpoint) {
            (CHECKPOINT_SPACING until path.size - 2 step CHECKPOINT_SPACING).mapNotNull { k ->
                path.subList(k, minOf(k + 2, path.size - 1)).firstOrNull { it !in gapRooms && it != goalRoom }
            }.distinct()
        } else {
            emptyList()
        }
        val reserved = setOf(startRoom, goalRoom) + gapRooms + checkpointRooms

        fun build(withHoles: List<Hole>) = Level.fromCells(
            name, null, width, height, { x, y -> !floor[y * width + x] },
            start = roomCentre(startRoom, spec),
            goal = Hole(roomCentre(goalRoom, spec), GOAL_RADIUS),
            holes = withHoles,
            checkpoints = checkpointRooms.map { roomCentre(it, spec) },
        )

        // Each trap is kept only if a comfortably wide lane still leads to the goal, with no extra jump.
        fun tryAdd(hole: Hole) {
            val trial = build(holes + hole)
            val route = NavGrid(trial, NavGrid.COMFORTABLE_RIM_MARGIN).routeToGoal()
            if (route != null && route.jumpCount <= gapRooms.size) holes += hole
        }
        for (k in path.indices) {
            val r = path[k]
            if (r !in reserved && random.nextDouble() < spec.traps) tryAdd(cellHole(r, random.nextInt(4), spec))
            if (k + 1 < path.size && random.nextDouble() < spec.traps / 2) {
                val door = doorway(r, path[k + 1], spec)[random.nextInt(2)]
                tryAdd(Hole(Point(door.first + 0.5, door.second + 0.5), TRAP_RADIUS))
            }
        }
        for (r in 0 until maze.count) {
            if (r in onPath || r in reserved || random.nextDouble() >= spec.decoys) continue
            tryAdd(cellHole(r, random.nextInt(4), spec))
        }
        val level = build(holes)
        val route = NavGrid(level).routeToGoal() ?: return null
        return level.takeIf { route.jumpCount == gapRooms.size }
    }

    private fun carve(spec: GeneratorSpec, random: SplitMix64): Maze {
        val maze = Maze(spec.roomsWide, spec.roomsHigh)
        val visited = BooleanArray(maze.count)
        val stack = ArrayDeque<Int>()
        val first = random.nextInt(maze.count)
        visited[first] = true
        stack += first
        while (stack.isNotEmpty()) {
            val r = stack.last()
            val fresh = maze.neighbours(r).filter { !visited[it] }
            if (fresh.isEmpty()) {
                stack.removeLast()
                continue
            }
            val next = fresh[random.nextInt(fresh.size)]
            maze.connect(r, next)
            visited[next] = true
            stack += next
        }
        for (r in 0 until maze.count) {
            if (maze.exits(r).size == 1 && random.nextDouble() < spec.loops) {
                val closed = maze.neighbours(r).filter { !maze.connected(r, it) }
                if (closed.isNotEmpty()) maze.connect(r, closed[random.nextInt(closed.size)])
            }
        }
        return maze
    }

    /** Rooms on the route that it crosses straight through, spaced apart, never next to the start or goal. */
    private fun chooseGaps(path: List<Int>, maze: Maze, spec: GeneratorSpec, random: SplitMix64): Set<Int> {
        val straight = (2 until path.size - 2).filter { k ->
            val before = path[k] - path[k - 1]
            val after = path[k + 1] - path[k]
            before == after && maze.exits(path[k]).size == 2
        }.shuffled(random)
        val chosen = mutableListOf<Int>()
        for (k in straight) {
            if (chosen.size == spec.gaps) break
            if (chosen.none { kotlin.math.abs(it - k) < 3 }) chosen += k
        }
        return chosen.map { path[it] }.toSet()
    }

    private fun roomOrigin(r: Int, spec: GeneratorSpec) =
        (r % spec.roomsWide) * ROOM_PITCH + 1 to (r / spec.roomsWide) * ROOM_PITCH + 1

    private fun roomCentre(r: Int, spec: GeneratorSpec): Point {
        val (x, y) = roomOrigin(r, spec)
        return Point(x + 1.0, y + 1.0)
    }

    private fun cellHole(r: Int, cell: Int, spec: GeneratorSpec): Hole {
        val (x, y) = roomOrigin(r, spec)
        return Hole(Point(x + cell % 2 + 0.5, y + cell / 2 + 0.5), TRAP_RADIUS)
    }

    /** The two cells of wall opened between neighbouring rooms [a] and [b]. */
    private fun doorway(a: Int, b: Int, spec: GeneratorSpec): List<Pair<Int, Int>> {
        val (ax, ay) = roomOrigin(minOf(a, b), spec)
        return if (kotlin.math.abs(a - b) == 1) listOf(ax + 2 to ay, ax + 2 to ay + 1) else listOf(ax to ay + 2, ax + 1 to ay + 2)
    }

    private fun <T> List<T>.shuffled(random: SplitMix64): List<T> = toMutableList().also { list ->
        for (i in list.indices.reversed()) {
            val j = random.nextInt(i + 1)
            list[i] = list[j].also { list[j] = list[i] }
        }
    }

    private const val ROOM_PITCH = 3
    private const val MAX_ATTEMPTS = 40
    private const val CHECKPOINT_SPACING = 6
    private const val TRAP_RADIUS = 0.5
    private const val GAP_RADIUS = 1.0
    private const val GOAL_RADIUS = 0.5
}

/** A small, fast generator with a fixed algorithm, so seeds mean the same thing in every version. */
class SplitMix64(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
}

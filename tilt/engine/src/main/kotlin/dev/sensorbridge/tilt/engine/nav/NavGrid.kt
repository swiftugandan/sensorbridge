package dev.sensorbridge.tilt.engine.nav

import dev.sensorbridge.tilt.engine.geometry.Point
import dev.sensorbridge.tilt.engine.level.Level
import dev.sensorbridge.tilt.engine.physics.Physics
import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A route through a level: corners to roll between, and which legs must be jumped.
 *
 * @property points corners in order, from the start to the goal.
 * @property jumps for each leg (points[i] → points[i + 1]), whether the marble has to be in the air.
 */
class Route(val points: List<Point>, val jumps: List<Boolean>) {
    init {
        require(points.size >= 2 && jumps.size == points.size - 1) { "a route needs at least one leg" }
    }

    val length: Double get() = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    val jumpCount: Int get() = jumps.count { it }
}

/**
 * Where a marble can safely be, sampled every half cell, and the best way between two places.
 *
 * A sample is *blocked* if the marble would overlap a wall there, a *hazard* if it would hang over a hole's rim
 * (from which a marble can only escape by jumping), and *free* otherwise. Routes roll between free samples and
 * jump straight across runs of hazards, and prefer to stay away from walls and rims, the way a careful player
 * keeps to the middle of a corridor.
 */
class NavGrid(
    private val level: Level,
    /** How far beyond a hole's rim a safe route must stay, in cells. */
    private val rimMargin: Double = RIM_MARGIN,
) {
    private val columns = level.width * SAMPLES_PER_CELL - 1
    private val rows = level.height * SAMPLES_PER_CELL - 1
    private val kind = ByteArray(columns * rows)
    private val clearance = DoubleArray(columns * rows)

    init {
        val walls = level.walls
        val holes = level.holes
        for (j in 0 until rows) for (i in 0 until columns) {
            val x = xOf(i)
            val y = yOf(j)
            val wallGap = walls.minOfOrNull { it.distanceTo(x, y) }?.minus(Physics.BALL_RADIUS) ?: Double.MAX_VALUE
            val rimGap = holes.minOfOrNull { hypot(x - it.center.x, y - it.center.y) - it.radius } ?: Double.MAX_VALUE
            val n = j * columns + i
            kind[n] = when {
                wallGap < WALL_MARGIN -> BLOCKED
                rimGap < rimMargin -> HAZARD
                else -> FREE
            }
            clearance[n] = minOf(wallGap, rimGap)
        }
    }

    /** True if a marble may rest at the sample nearest ([x], [y]). */
    fun isFree(x: Double, y: Double): Boolean = nodeAt(x, y)?.let { kind[it] == FREE } ?: false

    /** The cheapest route from the level's start to its goal, or null if there is none. */
    fun routeToGoal(): Route? = route(level.start, level.goal.center)

    fun route(from: Point, to: Point): Route? {
        val source = nodeAt(from.x, from.y)?.takeIf { kind[it] == FREE } ?: return null
        val target = nodeAt(to.x, to.y)?.takeIf { kind[it] == FREE } ?: return null
        val cost = DoubleArray(kind.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(kind.size) { -1 }
        val jumpedInto = BooleanArray(kind.size)
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[source] = 0.0
        queue += 0.0 to source
        while (queue.isNotEmpty()) {
            val (c, n) = queue.poll()
            if (c > cost[n]) continue
            if (n == target) break
            val i = n % columns
            val j = n / columns
            fun relax(m: Int, step: Double, jump: Boolean) {
                val next = c + step
                if (next < cost[m]) {
                    cost[m] = next
                    previous[m] = n
                    jumpedInto[m] = jump
                    queue += next to m
                }
            }
            for ((di, dj) in NEIGHBOURS) {
                val m = indexOf(i + di, j + dj) ?: continue
                if (kind[m] != FREE) continue
                // No cutting corners: a diagonal needs both orthogonal neighbours free.
                if (di != 0 && dj != 0 && (kind[indexOf(i + di, j)!!] != FREE || kind[indexOf(i, j + dj)!!] != FREE)) continue
                relax(m, hypot(di.toDouble(), dj.toDouble()) * SPACING * penalty(n, m), jump = false)
            }
            for ((di, dj) in STRAIGHT) {
                jumpTarget(i, j, di, dj)?.let { (m, steps) -> relax(m, steps * SPACING + JUMP_COST, jump = true) }
            }
        }
        if (cost[target].isInfinite()) return null
        val nodes = generateSequence(target) { previous[it].takeIf { p -> p >= 0 } }.toList().reversed()
        return simplify(nodes, nodes.map { jumpedInto[it] })
    }

    /** Across a straight run of hazard samples to the first free one beyond, if it is within a jump. */
    private fun jumpTarget(i: Int, j: Int, di: Int, dj: Int): Pair<Int, Int>? {
        for (steps in 1..MAX_JUMP_SAMPLES) {
            val m = indexOf(i + di * steps, j + dj * steps) ?: return null
            when (kind[m]) {
                BLOCKED -> return null
                FREE -> return if (steps >= 2) m to steps else null
            }
        }
        return null
    }

    /** Moving close to a wall or rim costs extra, up to [CLEARANCE_WEIGHT] times more right against it. */
    private fun penalty(a: Int, b: Int): Double {
        val tight = (PREFERRED_CLEARANCE - minOf(clearance[a], clearance[b])).coerceAtLeast(0.0) / PREFERRED_CLEARANCE
        return 1 + CLEARANCE_WEIGHT * tight
    }

    /** Keeps only the corners: samples where the direction changes, and both ends of every jump. */
    private fun simplify(nodes: List<Int>, jumpedInto: List<Boolean>): Route {
        val points = mutableListOf(pointOf(nodes[0]))
        val jumps = mutableListOf<Boolean>()
        for (k in 1 until nodes.size) {
            val last = k == nodes.lastIndex
            val jumpLeg = jumpedInto[k]
            val keep = last || jumpLeg || jumpedInto[k + 1] || direction(nodes[k - 1], nodes[k]) != direction(nodes[k], nodes[k + 1])
            if (keep) {
                points += pointOf(nodes[k])
                jumps += jumpLeg
            }
        }
        return Route(points, jumps)
    }

    private fun direction(a: Int, b: Int): Pair<Int, Int> =
        (b % columns - a % columns).sign to (b / columns - a / columns).sign

    private fun xOf(i: Int) = (i + 1) * SPACING
    private fun yOf(j: Int) = (j + 1) * SPACING
    private fun pointOf(n: Int) = Point(xOf(n % columns), yOf(n / columns))
    private fun indexOf(i: Int, j: Int): Int? = if (i in 0 until columns && j in 0 until rows) j * columns + i else null

    private fun nodeAt(x: Double, y: Double): Int? = indexOf((x / SPACING).roundToInt() - 1, (y / SPACING).roundToInt() - 1)

    companion object {
        /** The least clearance from a rim that is still safe: just outside the reach of its pull. */
        const val RIM_MARGIN = 0.08

        /** Clearance that leaves room for a wobbling hand: a lane a player can hold without precision. */
        const val COMFORTABLE_RIM_MARGIN = 0.3

        private const val SAMPLES_PER_CELL = 2
        private const val SPACING = 1.0 / SAMPLES_PER_CELL

        private const val FREE: Byte = 0
        private const val HAZARD: Byte = 1
        private const val BLOCKED: Byte = 2

        /** Margin beyond touching: a route never grazes a wall. */
        private const val WALL_MARGIN = 0.03

        private const val PREFERRED_CLEARANCE = 0.5
        private const val CLEARANCE_WEIGHT = 1.5

        /**
         * Jumps cover at most three cells and cost as much as a long detour: a route only jumps where rolling
         * around is impossible or very much longer, as a cautious player would.
         */
        private const val MAX_JUMP_SAMPLES = 6
        private const val JUMP_COST = 12.0

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}

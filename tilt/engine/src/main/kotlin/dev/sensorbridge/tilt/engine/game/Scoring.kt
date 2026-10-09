package dev.sensorbridge.tilt.engine.game

import kotlin.math.ceil

/** Stars for a finished run, measured against the level's par time. */
object Scoring {
    const val MAX_STARS = 3

    /** Within this multiple of par still earns two stars. */
    const val TWO_STAR_FACTOR = 1.5

    fun stars(seconds: Double, parSeconds: Double): Int = when {
        seconds <= parSeconds -> 3
        seconds <= parSeconds * TWO_STAR_FACTOR -> 2
        else -> 1
    }

    /** Par is the rehearsal time plus some slack for being human, rounded up to a tenth of a second. */
    fun parFor(rehearsalSeconds: Double): Double = ceil(rehearsalSeconds * PAR_SLACK * 10) / 10

    private const val PAR_SLACK = 1.15
}

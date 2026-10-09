package dev.sensorbridge.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Thins readings to at most [rateHz] on average, judged by event timestamps.
 *
 * Android treats a requested sampling period only as a hint and often delivers faster, so callers that asked
 * for a rate need it enforced. Emissions are scheduled on a fixed grid of slots rather than relative to the
 * previous emission, so an input that is not an exact multiple of the target (say 15 Hz thinned to 10 Hz)
 * still averages the requested rate. [tolerance] admits samples slightly before their slot to absorb jitter;
 * after a gap longer than one period the grid restarts instead of bursting to catch up.
 */
fun Flow<SensorReading>.limitRate(rateHz: Int, tolerance: Double = 0.1): Flow<SensorReading> {
    require(rateHz > 0) { "rateHz must be positive, was $rateHz" }
    require(tolerance in 0.0..<1.0) { "tolerance must be in [0, 1), was $tolerance" }
    val periodNanos = (NANOS_PER_SECOND / rateHz).toLong()
    val earlyNanos = (periodNanos * tolerance).toLong()
    return flow {
        var nextDue: Long? = null
        collect { reading ->
            val t = reading.timestampNanos
            val due = nextDue
            if (due == null || t >= due - earlyNanos) {
                nextDue = if (due == null || t - due >= periodNanos) t + periodNanos else due + periodNanos
                emit(reading)
            }
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000.0

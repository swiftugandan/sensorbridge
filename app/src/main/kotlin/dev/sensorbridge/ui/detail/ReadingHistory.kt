package dev.sensorbridge.ui.detail

import dev.sensorbridge.domain.SensorReading

/**
 * Rolling window of recent readings, sized by time. Mutated by the sensor stream and read by the UI at frame
 * rate, so access is synchronized and reads return immutable snapshots.
 */
class ReadingHistory(
    private val windowNanos: Long,
    private val channels: Int,
    private val recentCapacity: Int = 20,
) {
    private val samples = ArrayDeque<SensorReading>()
    private val recent = ArrayDeque<SensorReading>()

    @Synchronized
    fun add(reading: SensorReading) {
        samples.addLast(reading)
        recent.addFirst(reading)
        if (recent.size > recentCapacity) recent.removeLast()
        trim(reading.timestampNanos)
    }

    /** Drops samples older than the window, keeping one just before it so lines start at the left edge. */
    private fun trim(newestNanos: Long) {
        val cutoff = newestNanos - windowNanos
        while (samples.size >= 2 && samples[1].timestampNanos <= cutoff) samples.removeFirst()
    }

    /**
     * @param nowNanos current time on the sensor clock; the window ends here so sparse sensors scroll too.
     */
    @Synchronized
    fun snapshot(nowNanos: Long): HistorySnapshot {
        val latest = samples.lastOrNull()
        val end = when {
            latest == null -> nowNanos
            // Some HALs stamp events on a different clock; never let a skewed clock hide the data.
            nowNanos - latest.timestampNanos in 0..CLOCK_SKEW_LIMIT_NANOS -> nowNanos
            else -> latest.timestampNanos
        }
        val timestamps = LongArray(samples.size) { samples[it].timestampNanos }
        val series = List(channels) { channel ->
            FloatArray(samples.size) { samples[it].values.getOrElse(channel) { Float.NaN } }
        }
        return HistorySnapshot(
            latest = latest,
            startNanos = end - windowNanos,
            endNanos = end,
            timestamps = timestamps,
            series = series,
            measuredRateHz = measuredRate(end),
            recent = recent.toList(),
        )
    }

    private fun measuredRate(end: Long): Float? {
        val inWindow = samples.filter { it.timestampNanos >= end - windowNanos }
        if (inWindow.size < 3) return null
        val span = inWindow.last().timestampNanos - inWindow.first().timestampNanos
        return if (span > 0) (inWindow.size - 1) * NANOS_PER_SECOND / span else null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9f
        const val CLOCK_SKEW_LIMIT_NANOS = 10_000_000_000L
    }
}

/** Immutable view of [ReadingHistory] for one frame. Identity, not structural, equality: every frame is new. */
class HistorySnapshot(
    val latest: SensorReading?,
    val startNanos: Long,
    val endNanos: Long,
    val timestamps: LongArray,
    /** One array per channel, aligned with [timestamps]; missing values are NaN. */
    val series: List<FloatArray>,
    val measuredRateHz: Float?,
    /** Newest first. */
    val recent: List<SensorReading>,
) {
    val isEmpty: Boolean get() = timestamps.isEmpty()

    /** Range of finite values across all channels within the visible window, or null if there are none. */
    fun valueRange(): ClosedFloatingPointRange<Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in timestamps.indices) {
            // The sample just before the window still sets the left edge of the line, so it counts.
            if (i + 1 < timestamps.size && timestamps[i + 1] < startNanos) continue
            for (channel in series) {
                val v = channel[i]
                if (v.isFinite()) {
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
        }
        return if (lo <= hi) lo..hi else null
    }
}

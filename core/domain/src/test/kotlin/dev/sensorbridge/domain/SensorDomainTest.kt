package dev.sensorbridge.domain

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDomainTest {

    @Test
    fun `known types resolve to curated kinds`() {
        val kind = SensorCatalog.kindOf(SensorTypes.ACCELEROMETER, "android.sensor.accelerometer")
        assertEquals("accelerometer", kind.key)
        assertEquals("m/s²", kind.unit)
        assertEquals(ValueShape.VECTOR, kind.shape)
    }

    @Test
    fun `vendor types derive a slug from their string type`() {
        val kind = SensorCatalog.kindOf(65_537, "com.qualcomm.qti.Sensor_Hall Effect")
        assertEquals("sensor-hall-effect", kind.key)
        assertEquals("Sensor hall effect", kind.displayName)
        assertEquals(SensorCategory.OTHER, kind.category)
    }

    @Test
    fun `vendor types without a usable string type fall back to the type number`() {
        assertEquals("sensor-70000", SensorCatalog.kindOf(70_000, "").key)
    }

    @Test
    fun `rate limiting keeps one reading per period`() = runTest {
        // 200 readings 5 ms apart = 200 Hz for one second.
        val readings = (0 until 200).map { reading(it * 5_000_000L) }
        val limited = readings.asFlow().limitRate(100).toList()
        assertEquals(100, limited.size)
        assertEquals(10_000_000L, limited[1].timestampNanos - limited[0].timestampNanos)
    }

    @Test
    fun `rate limiting tolerates early jitter`() = runTest {
        // A 100 Hz request fed at ~105 Hz must stay near 100 Hz, not collapse towards 50 Hz.
        val readings = (0 until 100).map { reading(it * 9_500_000L) }
        assertTrue(readings.asFlow().limitRate(100).toList().size in 93..100)
    }

    @Test
    fun `rate limiting averages the target when the input is not a multiple of it`() = runTest {
        // 15 Hz in for two seconds, 10 Hz requested: relative spacing would give 7.5 Hz, slots give 10 Hz.
        val readings = (0 until 30).map { reading(it * 66_666_667L) }
        assertEquals(20, readings.asFlow().limitRate(10).toList().size)
    }

    @Test
    fun `rate limiting restarts its grid after a gap instead of bursting`() = runTest {
        val readings = listOf(0L, 10L, 500L, 501L, 502L, 510L).map { reading(it * 1_000_000L) }
        val kept = readings.asFlow().limitRate(100).toList().map { it.timestampNanos / 1_000_000L }
        assertEquals(listOf(0L, 10L, 500L, 510L), kept)
    }

    @Test
    fun `magnitude uses the leading components only`() {
        assertEquals(5f, reading(0, listOf(3f, 4f, 0f, 99f)).magnitude(), 1e-6f)
    }

    @Test
    fun `axis labels fall back for vendor extras`() {
        val kind = SensorCatalog.kindOf(SensorTypes.LIGHT, "android.sensor.light")
        val descriptor = SensorDescriptor(
            key = kind.key, type = SensorTypes.LIGHT, stringType = "android.sensor.light", displayName = kind.displayName,
            name = "tcs3701", vendor = "ams", version = 1, category = kind.category, shape = kind.shape, unit = kind.unit,
            axes = kind.axes, reportingMode = ReportingMode.ON_CHANGE, wakeUp = false, maximumRange = 1f,
            resolution = 1f, powerMilliAmps = 0f, minDelayMicros = 0, maxDelayMicros = 0,
        )
        assertEquals("illuminance", descriptor.axisLabel(0))
        assertEquals("v2", descriptor.axisLabel(2))
    }

    private fun reading(timestampNanos: Long, values: List<Float> = listOf(0f)) =
        SensorReading("test", timestampNanos, 0L, 3, values)
}

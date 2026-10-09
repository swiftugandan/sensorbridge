package dev.sensorbridge.ui.detail

import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.ui.components.AxisScale
import dev.sensorbridge.ui.format.ReadingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartModelTest {

    @Test
    fun `nice scale lands on round ticks`() {
        val scale = AxisScale.nice(-0.37f, 9.81f)
        assertEquals(-2f, scale.min, 1e-6f)
        assertEquals(10f, scale.max, 1e-6f)
        assertEquals(2f, scale.step, 1e-6f)
        assertEquals(listOf(-2f, 0f, 2f, 4f, 6f, 8f, 10f), scale.ticks)
    }

    @Test
    fun `flat signal still gets a visible band`() {
        val scale = AxisScale.nice(57f, 57f)
        assertTrue(scale.min < 57f && scale.max > 57f)
    }

    @Test
    fun `stable scale holds while data fits comfortably`() {
        val first = AxisScale.nice(0f, 9.8f)
        assertSame(first, AxisScale.stable(first, 0.5f, 9.6f))
    }

    @Test
    fun `stable scale rescales when data escapes`() {
        val first = AxisScale.nice(0f, 9.8f)
        val next = AxisScale.stable(first, 0f, 25f)
        assertTrue(next.max >= 25f)
    }

    @Test
    fun `history keeps one sample before the window and trims the rest`() {
        val history = ReadingHistory(windowNanos = 1_000, channels = 1)
        (0L..30L step 10).forEach { history.add(reading(it * 100)) } // t = 0, 1000, 2000, 3000
        val snapshot = history.snapshot(nowNanos = 3_000)
        assertEquals(listOf(2_000L, 3_000L), snapshot.timestamps.toList())
        assertEquals(2_000L, snapshot.startNanos)
    }

    @Test
    fun `history window follows the clock so sparse sensors scroll`() {
        val history = ReadingHistory(windowNanos = 1_000_000_000, channels = 1)
        history.add(reading(1_000))
        assertEquals(5_000_000_000L, history.snapshot(nowNanos = 5_000_000_000).endNanos)
    }

    @Test
    fun `measured rate needs a few samples`() {
        val history = ReadingHistory(windowNanos = 1_000_000_000, channels = 1)
        history.add(reading(0))
        assertNull(history.snapshot(0).measuredRateHz)
        (1..10).forEach { history.add(reading(it * 20_000_000L)) } // 50 Hz
        assertEquals(50f, history.snapshot(200_000_000).measuredRateHz!!, 0.01f)
    }

    @Test
    fun `missing channel values become gaps`() {
        val history = ReadingHistory(windowNanos = 1_000, channels = 3)
        history.add(SensorReading("s", 0, 0, 3, listOf(1f)))
        assertTrue(history.snapshot(0).series[2][0].isNaN())
    }

    @Test
    fun `identity quaternion is zero rotation and w is reconstructed when absent`() {
        assertEquals(0f, ReadingFormat.rotationAngleDegrees(listOf(0f, 0f, 0f, 1f)), 1e-3f)
        assertEquals(180f, ReadingFormat.rotationAngleDegrees(listOf(0f, 0f, 1f)), 1e-3f)
    }

    private fun reading(t: Long) = SensorReading("s", t, 0, 3, listOf(t.toFloat()))
}

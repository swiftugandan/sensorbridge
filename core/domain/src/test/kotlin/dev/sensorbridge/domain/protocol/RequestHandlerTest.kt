package dev.sensorbridge.domain.protocol

import dev.sensorbridge.domain.ReportingMode
import dev.sensorbridge.domain.SensorAccessException
import dev.sensorbridge.domain.SensorCategory
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.SensorRepository
import dev.sensorbridge.domain.ValueShape
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestHandlerTest {

    private val repository = FakeRepository()
    private val hello = Response.Hello(ProtocolCodec.VERSION, "test", DeviceInfo("Acme", "Phone", "11", 30))
    private val handler = RequestHandler(repository, hello)

    @Test
    fun `hello answers with server identity`() = runTest {
        assertEquals(listOf(hello), handler.handle(Request.Hello).toList())
    }

    @Test
    fun `list returns every sensor`() = runTest {
        val response = handler.handle(Request.ListSensors).toList().single() as Response.Sensors
        assertEquals(listOf("ticker", "silent", "guarded"), response.sensors.map { it.key })
    }

    @Test
    fun `read returns the first reading`() = runTest {
        val response = handler.handle(Request.Read("ticker")).toList().single() as Response.Reading
        assertEquals(0L, response.reading.timestampNanos)
    }

    @Test
    fun `read of a silent sensor times out`() = runTest {
        val response = handler.handle(Request.Read("silent", timeoutMs = 500)).toList().single() as Response.Error
        assertEquals(ErrorCode.TIMEOUT, response.code)
    }

    @Test
    fun `read of an unknown sensor reports it`() = runTest {
        val response = handler.handle(Request.Read("nope")).toList().single() as Response.Error
        assertEquals(ErrorCode.UNKNOWN_SENSOR, response.code)
    }

    @Test
    fun `missing permission is reported with its code`() = runTest {
        val response = handler.handle(Request.Read("guarded")).toList().single() as Response.Error
        assertEquals(ErrorCode.PERMISSION_REQUIRED, response.code)
    }

    @Test
    fun `stream stops at the sample limit`() = runTest {
        val responses = handler.handle(Request.Stream(listOf("ticker"), maxSamples = 5)).toList()
        assertEquals(5, responses.count { it is Response.Reading })
        assertEquals(Response.Done(5, DoneReason.MAX_SAMPLES), responses.last())
    }

    @Test
    fun `stream stops when the duration elapses`() = runTest {
        // The ticker emits every 10 ms of virtual time, starting immediately.
        val responses = handler.handle(Request.Stream(listOf("ticker"), durationMs = 95)).toList()
        val done = responses.last() as Response.Done
        assertEquals(DoneReason.DURATION, done.reason)
        assertEquals(10L, done.samples)
    }

    @Test
    fun `stream rejects unknown sensors before registering any`() = runTest {
        val responses = handler.handle(Request.Stream(listOf("ticker", "nope"), maxSamples = 1)).toList()
        assertEquals(ErrorCode.UNKNOWN_SENSOR, (responses.single() as Response.Error).code)
        assertTrue(repository.registrations.isEmpty())
    }

    private class FakeRepository : SensorRepository {
        val registrations = mutableListOf<String>()

        override val sensors = listOf(fakeDescriptor("ticker"), fakeDescriptor("silent"), fakeDescriptor("guarded"))

        override fun readings(key: String, rateHz: Int): Flow<SensorReading> = flow {
            registrations += key
            when (key) {
                "ticker" -> {
                    var t = 0L
                    while (true) {
                        emit(SensorReading(key, t * 1_000_000, t, 3, listOf(t.toFloat())))
                        delay(10)
                        t += 10
                    }
                }
                "silent" -> awaitCancellation()
                "guarded" -> throw SensorAccessException.PermissionRequired(key, "android.permission.ACTIVITY_RECOGNITION")
                else -> throw SensorAccessException.UnknownSensor(key)
            }
        }

        private fun fakeDescriptor(key: String) = SensorDescriptor(
            key = key, type = 1, stringType = "test.$key", displayName = key, name = key, vendor = "test",
            version = 1, category = SensorCategory.OTHER, shape = ValueShape.SCALAR, axes = listOf("v"),
            reportingMode = ReportingMode.CONTINUOUS, wakeUp = false, maximumRange = 1f, resolution = 1f,
            powerMilliAmps = 0f, minDelayMicros = 0, maxDelayMicros = 0,
        )
    }
}

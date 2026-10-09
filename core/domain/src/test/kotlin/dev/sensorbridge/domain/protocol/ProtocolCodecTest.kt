package dev.sensorbridge.domain.protocol

import dev.sensorbridge.domain.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun `read request applies the default timeout`() {
        val request = ProtocolCodec.decodeRequest("""{"type":"read","sensor":"light"}""")
        assertEquals(Request.Read("light", Request.DEFAULT_READ_TIMEOUT_MS), request)
    }

    @Test
    fun `object requests decode from their type alone`() {
        assertEquals(Request.ListSensors, ProtocolCodec.decodeRequest("""{"type":"list"}"""))
        assertEquals(Request.Hello, ProtocolCodec.decodeRequest("""{"type":"hello"}"""))
    }

    @Test
    fun `stream request round-trips and omits absent limits`() {
        val request = Request.Stream(listOf("accelerometer", "gyroscope"), rateHz = 100, durationMs = 2_000)
        val line = ProtocolCodec.encode(request)
        assertFalse("null fields are omitted", line.contains("maxSamples"))
        assertEquals(request, ProtocolCodec.decodeRequest(line))
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val request = ProtocolCodec.decodeRequest("""{"type":"read","sensor":"light","future":true}""")
        assertEquals("light", (request as Request.Read).sensor)
    }

    @Test
    fun `unknown type is malformed`() {
        assertCode(ErrorCode.MALFORMED) { ProtocolCodec.decodeRequest("""{"type":"explode"}""") }
    }

    @Test
    fun `invalid json is malformed`() {
        assertCode(ErrorCode.MALFORMED) { ProtocolCodec.decodeRequest("not json") }
    }

    @Test
    fun `out of range rate is an invalid request`() {
        assertCode(ErrorCode.INVALID_REQUEST) {
            ProtocolCodec.decodeRequest("""{"type":"stream","sensors":["light"],"rateHz":0}""")
        }
    }

    @Test
    fun `empty sensor list is an invalid request`() {
        assertCode(ErrorCode.INVALID_REQUEST) {
            ProtocolCodec.decodeRequest("""{"type":"stream","sensors":[]}""")
        }
    }

    @Test
    fun `responses are single lines tagged by type`() {
        val reading = SensorReading("accelerometer", 1L, 2L, 3, listOf(0.5f, -9.81f, 0f))
        val line = ProtocolCodec.encode(Response.Reading(reading))
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("""{"type":"reading""""))
        assertEquals(Response.Reading(reading), ProtocolCodec.decodeResponse(line))
    }

    @Test
    fun `enums use their wire names`() {
        val line = ProtocolCodec.encode(Response.Error(ErrorCode.PERMISSION_REQUIRED, "nope"))
        assertTrue(line, line.contains(""""code":"permission-required""""))
    }

    private fun assertCode(expected: ErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(expected, e.code)
            return
        }
        throw AssertionError("Expected ProtocolException($expected)")
    }
}

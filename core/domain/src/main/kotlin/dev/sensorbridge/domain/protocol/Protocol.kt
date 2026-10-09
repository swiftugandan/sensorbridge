package dev.sensorbridge.domain.protocol

import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.SensorRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The bridge speaks newline-delimited JSON over a local socket. Each line is one object whose "type" field
 * selects the message. A client sends a request and reads responses until the request is finished
 * (one response for hello/list/read; for stream, readings followed by "done", or until the client hangs up).
 * Requests on one connection are handled in order.
 */

@Serializable
sealed interface Request {
    @Serializable
    @SerialName("hello")
    data object Hello : Request

    @Serializable
    @SerialName("list")
    data object ListSensors : Request

    @Serializable
    @SerialName("read")
    data class Read(
        val sensor: String,
        val timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    ) : Request {
        init {
            require(sensor.isNotBlank()) { "sensor must not be blank" }
            require(timeoutMs in 1..MAX_TIMEOUT_MS) { "timeoutMs must be in 1..$MAX_TIMEOUT_MS" }
        }
    }

    @Serializable
    @SerialName("stream")
    data class Stream(
        val sensors: List<String>,
        val rateHz: Int = SensorRepository.DEFAULT_RATE_HZ,
        /** Stop after this long; `null` streams until the client disconnects or [maxSamples] is reached. */
        val durationMs: Long? = null,
        /** Stop after this many readings across all sensors. */
        val maxSamples: Int? = null,
    ) : Request {
        init {
            require(sensors.isNotEmpty()) { "sensors must not be empty" }
            require(sensors.none { it.isBlank() }) { "sensor keys must not be blank" }
            require(rateHz in SensorRepository.SUPPORTED_RATES_HZ) {
                "rateHz must be in ${SensorRepository.SUPPORTED_RATES_HZ}"
            }
            require(durationMs == null || durationMs > 0) { "durationMs must be positive" }
            require(maxSamples == null || maxSamples > 0) { "maxSamples must be positive" }
        }
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 3_000L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}

@Serializable
sealed interface Response {
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val app: String, val device: DeviceInfo) : Response

    @Serializable
    @SerialName("sensors")
    data class Sensors(val sensors: List<SensorDescriptor>) : Response

    @Serializable
    @SerialName("reading")
    data class Reading(val reading: SensorReading) : Response

    @Serializable
    @SerialName("done")
    data class Done(val samples: Long, val reason: DoneReason) : Response

    @Serializable
    @SerialName("error")
    data class Error(val code: ErrorCode, val message: String) : Response
}

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: Int,
)

@Serializable
enum class DoneReason {
    @SerialName("duration") DURATION,
    @SerialName("max-samples") MAX_SAMPLES,
}

@Serializable
enum class ErrorCode {
    @SerialName("malformed") MALFORMED,
    @SerialName("invalid-request") INVALID_REQUEST,
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("unknown-sensor") UNKNOWN_SENSOR,
    @SerialName("permission-required") PERMISSION_REQUIRED,
    @SerialName("sensor-unavailable") SENSOR_UNAVAILABLE,
    @SerialName("timeout") TIMEOUT,
    @SerialName("internal") INTERNAL,
}

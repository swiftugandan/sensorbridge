package dev.sensorbridge.domain.protocol

import dev.sensorbridge.domain.SensorAccessException
import dev.sensorbridge.domain.SensorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

/** Turns protocol requests into response streams. Transport-agnostic, so it is tested without sockets. */
class RequestHandler(
    private val repository: SensorRepository,
    private val hello: Response.Hello,
) {
    fun handle(request: Request): Flow<Response> =
        when (request) {
            Request.Hello -> flowOf(hello)
            Request.ListSensors -> flow { emit(Response.Sensors(repository.sensors)) }
            is Request.Read -> read(request)
            is Request.Stream -> stream(request)
        }.catch { emit(it.toErrorResponse()) }

    private fun read(request: Request.Read): Flow<Response> = flow {
        val reading = withTimeoutOrNull(request.timeoutMs) {
            repository.readings(request.sensor, READ_RATE_HZ).first()
        }
        emit(
            if (reading != null) {
                Response.Reading(reading)
            } else {
                Response.Error(
                    ErrorCode.TIMEOUT,
                    "No reading from '${request.sensor}' within ${request.timeoutMs} ms. " +
                        "On-change and event sensors only report when their value changes.",
                )
            },
        )
    }

    private fun stream(request: Request.Stream): Flow<Response> = flow {
        val keys = request.sensors.distinct()
        keys.firstOrNull { repository.descriptor(it) == null }?.let { throw SensorAccessException.UnknownSensor(it) }

        val merged = keys.map { repository.readings(it, request.rateHz) }.merge()
        val bounded = request.maxSamples?.let(merged::take) ?: merged

        var samples = 0L
        val finishedBeforeDeadline = withTimeoutOrNull(request.durationMs ?: Long.MAX_VALUE) {
            bounded.collect {
                emit(Response.Reading(it))
                samples++
            }
        } != null

        if (request.durationMs != null || request.maxSamples != null) {
            emit(Response.Done(samples, if (finishedBeforeDeadline) DoneReason.MAX_SAMPLES else DoneReason.DURATION))
        }
    }

    private fun Throwable.toErrorResponse(): Response.Error = when (this) {
        is SensorAccessException.UnknownSensor -> Response.Error(ErrorCode.UNKNOWN_SENSOR, message.orEmpty())
        is SensorAccessException.PermissionRequired -> Response.Error(ErrorCode.PERMISSION_REQUIRED, message.orEmpty())
        is SensorAccessException.RegistrationFailed -> Response.Error(ErrorCode.SENSOR_UNAVAILABLE, message.orEmpty())
        is IllegalArgumentException -> Response.Error(ErrorCode.INVALID_REQUEST, message.orEmpty())
        else -> Response.Error(ErrorCode.INTERNAL, "${this::class.simpleName}: $message")
    }

    private companion object {
        /** A one-off read wants the first sample quickly, not a particular cadence. */
        const val READ_RATE_HZ = 50
    }
}

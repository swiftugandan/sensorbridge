package dev.sensorbridge.domain.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ProtocolException(val code: ErrorCode, message: String) : Exception(message)

/** Encodes and decodes single protocol lines. Encoded lines never contain a newline. */
object ProtocolCodec {
    const val VERSION = 1
    const val DEFAULT_SOCKET_NAME = "sensorbridge"

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(request: Request): String = json.encodeToString(Request.serializer(), request)

    fun encode(response: Response): String = json.encodeToString(Response.serializer(), response)

    fun decodeRequest(line: String): Request = decode(line) { json.decodeFromString(Request.serializer(), it) }

    fun decodeResponse(line: String): Response = decode(line) { json.decodeFromString(Response.serializer(), it) }

    private inline fun <T> decode(line: String, parse: (String) -> T): T =
        try {
            parse(line)
        } catch (e: SerializationException) {
            // Validation failures inside constructors surface wrapped by the generated serializer.
            val validation = generateSequence(e.cause) { it.cause }.firstOrNull { it is IllegalArgumentException }
            if (validation != null) {
                throw ProtocolException(ErrorCode.INVALID_REQUEST, validation.message ?: "Invalid request")
            }
            throw ProtocolException(ErrorCode.MALFORMED, e.message ?: "Malformed message")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(ErrorCode.INVALID_REQUEST, e.message ?: "Invalid request")
        }
}

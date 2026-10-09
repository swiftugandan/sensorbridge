package dev.sensorbridge.domain

import kotlinx.coroutines.flow.Flow

interface SensorRepository {
    /** Every sensor on the device, ordered by type, with stable unique keys. */
    val sensors: List<SensorDescriptor>

    fun descriptor(key: String): SensorDescriptor? = sensors.firstOrNull { it.key == key }

    /**
     * A cold stream of readings. The sensor is registered when collection starts and released when it stops.
     * Continuous sensors are delivered at no more than [rateHz]; event and on-change sensors ignore it.
     *
     * Fails with [SensorAccessException] when the sensor cannot be read.
     */
    fun readings(key: String, rateHz: Int = DEFAULT_RATE_HZ): Flow<SensorReading>

    companion object {
        const val DEFAULT_RATE_HZ = 50
        val SUPPORTED_RATES_HZ = 1..1_000
    }
}

sealed class SensorAccessException(message: String) : Exception(message) {
    abstract val sensor: String

    class UnknownSensor(override val sensor: String) :
        SensorAccessException("No sensor with key '$sensor'")

    class PermissionRequired(override val sensor: String, val permission: String) :
        SensorAccessException("Sensor '$sensor' requires the $permission permission")

    class RegistrationFailed(override val sensor: String) :
        SensorAccessException("The system refused to register a listener for '$sensor'")
}

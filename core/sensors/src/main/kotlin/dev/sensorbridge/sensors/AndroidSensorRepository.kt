package dev.sensorbridge.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.sensorbridge.domain.ReportingMode
import dev.sensorbridge.domain.SensorAccessException
import dev.sensorbridge.domain.SensorCatalog
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorReading
import dev.sensorbridge.domain.SensorRepository
import dev.sensorbridge.domain.SensorTypes
import dev.sensorbridge.domain.limitRate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** [SensorRepository] backed by the platform [SensorManager]. One instance should live for the whole process. */
class AndroidSensorRepository(context: Context) : SensorRepository {

    private val appContext = context.applicationContext
    private val sensorManager = checkNotNull(appContext.getSystemService(SensorManager::class.java)) {
        "SensorManager is unavailable"
    }

    /** Sensor callbacks are delivered here so collectors never depend on the main looper being free. */
    private val eventHandler: Handler by lazy {
        val thread = HandlerThread("SensorBridge-events", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        Handler(thread.looper)
    }

    private class Entry(val sensor: Sensor, val descriptor: SensorDescriptor)

    private val entries: Map<String, Entry> by lazy(::discoverSensors)

    override val sensors: List<SensorDescriptor> by lazy { entries.values.map(Entry::descriptor) }

    override fun descriptor(key: String): SensorDescriptor? = entries[key]?.descriptor

    override fun readings(key: String, rateHz: Int): Flow<SensorReading> {
        require(rateHz in SensorRepository.SUPPORTED_RATES_HZ) { "rateHz must be in ${SensorRepository.SUPPORTED_RATES_HZ}" }
        return flow {
            val entry = entries[key] ?: throw SensorAccessException.UnknownSensor(key)
            entry.descriptor.requiredPermission?.let { permission ->
                if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
                    throw SensorAccessException.PermissionRequired(key, permission)
                }
            }
            emitAll(
                when (entry.descriptor.reportingMode) {
                    ReportingMode.CONTINUOUS -> listen(entry, rateHz).limitRate(rateHz)
                    // Some HALs re-report unchanged values; an on-change stream should carry changes only.
                    ReportingMode.ON_CHANGE -> listen(entry, rateHz).distinctUntilChangedBy(SensorReading::values)
                    ReportingMode.SPECIAL_TRIGGER -> listen(entry, rateHz)
                    ReportingMode.ONE_SHOT -> trigger(entry)
                },
            )
        }
    }

    private fun listen(entry: Entry, rateHz: Int): Flow<SensorReading> = callbackFlow {
        val key = entry.descriptor.key
        val listener = object : SensorEventListener {
            // The framework recycles SensorEvent instances, so values are copied before leaving the callback.
            override fun onSensorChanged(event: SensorEvent) {
                trySend(SensorReading(key, event.timestamp, wallTimeOf(event.timestamp), event.accuracy, event.values.toList()))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // Ask the HAL for roughly twice the target so rate limiting has samples to choose from; HALs round
        // requested periods to a few supported rates, often below the one asked for.
        val samplingPeriodUs = maxOf(MICROS_PER_SECOND / (rateHz * OVERSAMPLING), entry.sensor.minDelay)
        if (!sensorManager.registerListener(listener, entry.sensor, samplingPeriodUs, eventHandler)) {
            throw SensorAccessException.RegistrationFailed(key)
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }.buffer(EVENT_BUFFER_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** One-shot sensors disarm after firing; re-arming after each event turns them into a stream. */
    private fun trigger(entry: Entry): Flow<SensorReading> = callbackFlow {
        val key = entry.descriptor.key
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                trySend(SensorReading(key, event.timestamp, wallTimeOf(event.timestamp), SensorManager.SENSOR_STATUS_ACCURACY_HIGH, event.values.toList()))
                sensorManager.requestTriggerSensor(this, entry.sensor)
            }
        }
        if (!sensorManager.requestTriggerSensor(listener, entry.sensor)) {
            throw SensorAccessException.RegistrationFailed(key)
        }
        awaitClose { sensorManager.cancelTriggerSensor(listener, entry.sensor) }
    }.buffer(EVENT_BUFFER_CAPACITY, BufferOverflow.DROP_OLDEST)

    private fun discoverSensors(): Map<String, Entry> {
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        // The platform's default sensor for a type gets the plain key; secondary instances get a numeric suffix.
        val defaults = all.mapNotNullTo(HashSet()) { sensorManager.getDefaultSensor(it.type, it.isWakeUpSensor) }
        val ordered = all.sortedWith(
            compareBy<Sensor>({ it.type }, { it.isWakeUpSensor }, { it !in defaults }, { it.name }),
        )
        val result = LinkedHashMap<String, Entry>(ordered.size)
        for (sensor in ordered) {
            val kind = SensorCatalog.kindOf(sensor.type, sensor.stringType)
            val base = if (sensor.isWakeUpSensor) "${kind.key}-wakeup" else kind.key
            val key = generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) base else "$base-$n" }
                .first { it !in result }
            result[key] = Entry(
                sensor,
                SensorDescriptor(
                    key = key,
                    type = sensor.type,
                    stringType = sensor.stringType,
                    displayName = kind.displayName,
                    name = sensor.name,
                    vendor = sensor.vendor,
                    version = sensor.version,
                    category = kind.category,
                    shape = kind.shape,
                    unit = kind.unit,
                    axes = kind.axes,
                    reportingMode = sensor.reportingMode.toReportingMode(),
                    wakeUp = sensor.isWakeUpSensor,
                    maximumRange = sensor.maximumRange,
                    resolution = sensor.resolution,
                    powerMilliAmps = sensor.power,
                    minDelayMicros = sensor.minDelay,
                    maxDelayMicros = sensor.maxDelay,
                    requiredPermission = requiredPermission(sensor.type),
                ),
            )
        }
        return result
    }

    private fun Int.toReportingMode(): ReportingMode = when (this) {
        Sensor.REPORTING_MODE_ON_CHANGE -> ReportingMode.ON_CHANGE
        Sensor.REPORTING_MODE_ONE_SHOT -> ReportingMode.ONE_SHOT
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> ReportingMode.SPECIAL_TRIGGER
        else -> ReportingMode.CONTINUOUS
    }

    private fun requiredPermission(type: Int): String? = when (type) {
        SensorTypes.STEP_COUNTER, SensorTypes.STEP_DETECTOR ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null
        SensorTypes.HEART_RATE, SensorTypes.HEART_BEAT -> Manifest.permission.BODY_SENSORS
        else -> null
    }

    /** Sensor timestamps share the `elapsedRealtimeNanos` clock; project them onto wall-clock time. */
    private fun wallTimeOf(eventNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - eventNanos) / NANOS_PER_MILLI

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000
        const val OVERSAMPLING = 2
        const val NANOS_PER_MILLI = 1_000_000L

        /** About two seconds at the fastest supported rate before the oldest readings are dropped. */
        const val EVENT_BUFFER_CAPACITY = 2_048
    }
}

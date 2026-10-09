package dev.sensorbridge.domain

/** Platform-independent knowledge about a sensor type: what it measures and how to present it. */
data class SensorKind(
    val key: String,
    val displayName: String,
    val category: SensorCategory,
    val shape: ValueShape,
    val unit: String?,
    val axes: List<String>,
)

/** Integer sensor types as defined by `android.hardware.Sensor.TYPE_*`. */
object SensorTypes {
    const val ACCELEROMETER = 1
    const val MAGNETIC_FIELD = 2
    const val ORIENTATION = 3
    const val GYROSCOPE = 4
    const val LIGHT = 5
    const val PRESSURE = 6
    const val PROXIMITY = 8
    const val GRAVITY = 9
    const val LINEAR_ACCELERATION = 10
    const val ROTATION_VECTOR = 11
    const val RELATIVE_HUMIDITY = 12
    const val AMBIENT_TEMPERATURE = 13
    const val MAGNETIC_FIELD_UNCALIBRATED = 14
    const val GAME_ROTATION_VECTOR = 15
    const val GYROSCOPE_UNCALIBRATED = 16
    const val SIGNIFICANT_MOTION = 17
    const val STEP_DETECTOR = 18
    const val STEP_COUNTER = 19
    const val GEOMAGNETIC_ROTATION_VECTOR = 20
    const val HEART_RATE = 21
    const val TILT_DETECTOR = 22
    const val WAKE_GESTURE = 23
    const val GLANCE_GESTURE = 24
    const val PICK_UP_GESTURE = 25
    const val DEVICE_ORIENTATION = 27
    const val POSE_6DOF = 28
    const val STATIONARY_DETECT = 29
    const val MOTION_DETECT = 30
    const val HEART_BEAT = 31
    const val LOW_LATENCY_OFFBODY_DETECT = 34
    const val ACCELEROMETER_UNCALIBRATED = 35
    const val HINGE_ANGLE = 36
}

object SensorCatalog {
    private val XYZ = listOf("x", "y", "z")
    private val XYZ_BIAS = XYZ + listOf("bias x", "bias y", "bias z")
    private val XYZ_DRIFT = XYZ + listOf("drift x", "drift y", "drift z")
    private val QUATERNION = listOf("x", "y", "z", "w")

    private const val MS2 = "m/s²"
    private const val MICROTESLA = "µT"
    private const val RAD_PER_S = "rad/s"

    private val known: Map<Int, SensorKind> = with(SensorTypes) {
        listOf(
            ACCELEROMETER to kind("accelerometer", "Accelerometer", SensorCategory.MOTION, ValueShape.VECTOR, MS2, XYZ),
            ACCELEROMETER_UNCALIBRATED to kind("accelerometer-uncalibrated", "Accelerometer (uncalibrated)", SensorCategory.MOTION, ValueShape.VECTOR, MS2, XYZ_BIAS),
            GYROSCOPE to kind("gyroscope", "Gyroscope", SensorCategory.MOTION, ValueShape.VECTOR, RAD_PER_S, XYZ),
            GYROSCOPE_UNCALIBRATED to kind("gyroscope-uncalibrated", "Gyroscope (uncalibrated)", SensorCategory.MOTION, ValueShape.VECTOR, RAD_PER_S, XYZ_DRIFT),
            GRAVITY to kind("gravity", "Gravity", SensorCategory.MOTION, ValueShape.VECTOR, MS2, XYZ),
            LINEAR_ACCELERATION to kind("linear-acceleration", "Linear acceleration", SensorCategory.MOTION, ValueShape.VECTOR, MS2, XYZ),
            ROTATION_VECTOR to kind("rotation-vector", "Rotation vector", SensorCategory.MOTION, ValueShape.ROTATION, null, QUATERNION + "heading accuracy"),
            SIGNIFICANT_MOTION to kind("significant-motion", "Significant motion", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            STEP_DETECTOR to kind("step-detector", "Step detector", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("step")),
            STEP_COUNTER to kind("step-counter", "Step counter", SensorCategory.MOTION, ValueShape.COUNTER, "steps", listOf("steps")),
            TILT_DETECTOR to kind("tilt-detector", "Tilt detector", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            WAKE_GESTURE to kind("wake-gesture", "Wake gesture", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            GLANCE_GESTURE to kind("glance-gesture", "Glance gesture", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            PICK_UP_GESTURE to kind("pick-up-gesture", "Pick-up gesture", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            STATIONARY_DETECT to kind("stationary-detect", "Stationary detect", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            MOTION_DETECT to kind("motion-detect", "Motion detect", SensorCategory.MOTION, ValueShape.EVENT, null, listOf("event")),
            POSE_6DOF to kind("pose-6dof", "Pose (6DoF)", SensorCategory.MOTION, ValueShape.RAW, null, QUATERNION + XYZ.map { "t$it" }),

            MAGNETIC_FIELD to kind("magnetic-field", "Magnetometer", SensorCategory.POSITION, ValueShape.VECTOR, MICROTESLA, XYZ),
            MAGNETIC_FIELD_UNCALIBRATED to kind("magnetic-field-uncalibrated", "Magnetometer (uncalibrated)", SensorCategory.POSITION, ValueShape.VECTOR, MICROTESLA, XYZ_BIAS),
            ORIENTATION to kind("orientation", "Orientation", SensorCategory.POSITION, ValueShape.ANGLES, "°", listOf("azimuth", "pitch", "roll")),
            GAME_ROTATION_VECTOR to kind("game-rotation-vector", "Game rotation vector", SensorCategory.POSITION, ValueShape.ROTATION, null, QUATERNION),
            GEOMAGNETIC_ROTATION_VECTOR to kind("geomagnetic-rotation-vector", "Geomagnetic rotation vector", SensorCategory.POSITION, ValueShape.ROTATION, null, QUATERNION + "heading accuracy"),
            PROXIMITY to kind("proximity", "Proximity", SensorCategory.POSITION, ValueShape.SCALAR, "cm", listOf("distance")),
            DEVICE_ORIENTATION to kind("device-orientation", "Device orientation", SensorCategory.POSITION, ValueShape.SCALAR, null, listOf("rotation")),
            HINGE_ANGLE to kind("hinge-angle", "Hinge angle", SensorCategory.POSITION, ValueShape.SCALAR, "°", listOf("angle")),

            LIGHT to kind("light", "Ambient light", SensorCategory.ENVIRONMENT, ValueShape.SCALAR, "lx", listOf("illuminance")),
            PRESSURE to kind("pressure", "Barometer", SensorCategory.ENVIRONMENT, ValueShape.SCALAR, "hPa", listOf("pressure")),
            AMBIENT_TEMPERATURE to kind("ambient-temperature", "Ambient temperature", SensorCategory.ENVIRONMENT, ValueShape.SCALAR, "°C", listOf("temperature")),
            RELATIVE_HUMIDITY to kind("relative-humidity", "Relative humidity", SensorCategory.ENVIRONMENT, ValueShape.SCALAR, "%", listOf("humidity")),

            HEART_RATE to kind("heart-rate", "Heart rate", SensorCategory.OTHER, ValueShape.SCALAR, "bpm", listOf("rate")),
            HEART_BEAT to kind("heart-beat", "Heart beat", SensorCategory.OTHER, ValueShape.EVENT, null, listOf("confidence")),
            LOW_LATENCY_OFFBODY_DETECT to kind("offbody-detect", "Off-body detect", SensorCategory.OTHER, ValueShape.SCALAR, null, listOf("on body")),
        ).toMap()
    }

    /** Resolves a sensor type to its kind; vendor types fall back to a kind derived from [stringType]. */
    fun kindOf(type: Int, stringType: String): SensorKind = known[type] ?: vendorKind(type, stringType)

    private fun vendorKind(type: Int, stringType: String): SensorKind {
        val slug = slugify(stringType.substringAfterLast('.')).ifEmpty { "sensor-$type" }
        return kind(slug, humanize(slug), SensorCategory.OTHER, ValueShape.RAW, null, emptyList())
    }

    internal fun slugify(raw: String): String =
        raw.lowercase().replace(NON_SLUG, "-").trim('-')

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun kind(
        key: String,
        displayName: String,
        category: SensorCategory,
        shape: ValueShape,
        unit: String?,
        axes: List<String>,
    ) = SensorKind(key, displayName, category, shape, unit, axes)

    private val NON_SLUG = Regex("[^a-z0-9]+")
}

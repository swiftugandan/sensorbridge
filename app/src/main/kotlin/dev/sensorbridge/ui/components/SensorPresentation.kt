package dev.sensorbridge.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined._3dRotation
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import dev.sensorbridge.R
import dev.sensorbridge.domain.ReportingMode
import dev.sensorbridge.domain.SensorCategory
import dev.sensorbridge.domain.SensorDescriptor
import dev.sensorbridge.domain.SensorTypes

val SensorDescriptor.icon: ImageVector
    get() = when (type) {
        SensorTypes.ACCELEROMETER, SensorTypes.ACCELEROMETER_UNCALIBRATED -> Icons.Outlined.Speed
        SensorTypes.LINEAR_ACCELERATION -> Icons.AutoMirrored.Outlined.TrendingUp
        SensorTypes.GRAVITY -> Icons.Outlined.Public
        SensorTypes.GYROSCOPE, SensorTypes.GYROSCOPE_UNCALIBRATED -> Icons.Outlined.ScreenRotation
        SensorTypes.ROTATION_VECTOR, SensorTypes.GAME_ROTATION_VECTOR,
        SensorTypes.GEOMAGNETIC_ROTATION_VECTOR, SensorTypes.POSE_6DOF -> Icons.Outlined._3dRotation
        SensorTypes.MAGNETIC_FIELD, SensorTypes.MAGNETIC_FIELD_UNCALIBRATED, SensorTypes.ORIENTATION -> Icons.Outlined.Explore
        SensorTypes.DEVICE_ORIENTATION -> Icons.Outlined.ScreenRotation
        SensorTypes.STEP_COUNTER, SensorTypes.STEP_DETECTOR -> Icons.AutoMirrored.Outlined.DirectionsWalk
        SensorTypes.SIGNIFICANT_MOTION, SensorTypes.MOTION_DETECT, SensorTypes.STATIONARY_DETECT -> Icons.AutoMirrored.Outlined.DirectionsRun
        SensorTypes.PICK_UP_GESTURE, SensorTypes.WAKE_GESTURE, SensorTypes.GLANCE_GESTURE, SensorTypes.TILT_DETECTOR -> Icons.Outlined.TouchApp
        SensorTypes.LIGHT -> Icons.Outlined.LightMode
        SensorTypes.PRESSURE -> Icons.Outlined.Compress
        SensorTypes.AMBIENT_TEMPERATURE -> Icons.Outlined.Thermostat
        SensorTypes.RELATIVE_HUMIDITY -> Icons.Outlined.WaterDrop
        SensorTypes.HEART_RATE, SensorTypes.HEART_BEAT -> Icons.Outlined.FavoriteBorder
        else -> Icons.Outlined.Sensors
    }

@get:StringRes
val SensorCategory.label: Int
    get() = when (this) {
        SensorCategory.MOTION -> R.string.category_motion
        SensorCategory.POSITION -> R.string.category_position
        SensorCategory.ENVIRONMENT -> R.string.category_environment
        SensorCategory.OTHER -> R.string.category_other
    }

@get:StringRes
val ReportingMode.label: Int
    get() = when (this) {
        ReportingMode.CONTINUOUS -> R.string.reporting_continuous
        ReportingMode.ON_CHANGE -> R.string.reporting_on_change
        ReportingMode.ONE_SHOT -> R.string.reporting_one_shot
        ReportingMode.SPECIAL_TRIGGER -> R.string.reporting_special
    }

/** Event sensors have no value to chart; they're presented as a log of occurrences. */
val SensorDescriptor.isEventDriven: Boolean
    get() = reportingMode == ReportingMode.ONE_SHOT || reportingMode == ReportingMode.SPECIAL_TRIGGER

/** Human-readable permission name, e.g. "Physical activity" for ACTIVITY_RECOGNITION. */
fun permissionLabel(permission: String): String = when (permission) {
    android.Manifest.permission.ACTIVITY_RECOGNITION -> "Physical activity"
    android.Manifest.permission.BODY_SENSORS -> "Body sensors"
    android.Manifest.permission.RECORD_AUDIO -> "Microphone"
    android.Manifest.permission.CAMERA -> "Camera"
    else -> permission.substringAfterLast('.')
}

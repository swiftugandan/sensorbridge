package dev.sensorbridge.domain.vision

import kotlinx.coroutines.flow.Flow

/**
 * One camera frame's brightness (the Y plane), row-major and tightly packed: `luma[y * width + x]`, 0–255.
 * [rotationDegrees] is the clockwise rotation that makes the frame upright on screen.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val rotationDegrees: Int,
    /** True for a front camera, whose preview is shown mirrored like a mirror. */
    val mirrored: Boolean,
    val timestampNanos: Long,
) {
    init {
        require(luma.size >= width * height) { "luma holds ${luma.size} bytes, need ${width * height}" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotation must be a multiple of 90, was $rotationDegrees" }
    }
}

enum class Lens { BACK, FRONT }

interface CameraRepository {
    fun hasPermission(): Boolean

    fun hasLens(lens: Lens): Boolean

    /**
     * A cold stream of frames from [lens]. The camera opens when collection starts and is released as soon as it
     * stops. Frames that arrive while the collector is busy are dropped, never queued. Fails with [CameraAccessException].
     */
    fun frames(lens: Lens): Flow<LumaFrame>
}

sealed class CameraAccessException(message: String) : Exception(message) {
    class PermissionRequired(val permission: String) : CameraAccessException("The camera requires the $permission permission")

    /** No such lens, the camera is disabled, or another app holds it. */
    class Unavailable(reason: String) : CameraAccessException(reason)
}

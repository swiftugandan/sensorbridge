package dev.sensorbridge.ui.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** Where finished sketches go. */
interface SketchGallery {
    /** Human-readable location shown after saving, e.g. "Pictures/SensorBridge". */
    val location: String

    /**
     * Saves [tone] (black strokes on white, as rendered) recoloured with [ink] and [paper], and returns a
     * content URI that a viewer app can open. Throws [IOException] when the image cannot be written.
     */
    suspend fun save(tone: Bitmap, ink: Color, paper: Color): Uri
}

/** Saves sketches as PNGs in the shared Pictures collection, visible to gallery apps. */
class MediaStoreSketchGallery(context: Context) : SketchGallery {
    private val appContext = context.applicationContext

    override val location = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

    override suspend fun save(tone: Bitmap, ink: Color, paper: Color): Uri = withContext(Dispatchers.IO) {
        val image = recolor(tone, ink, paper)
        val name = "Sketch_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(image, name) else saveLegacy(image, name)
        } finally {
            image.recycle()
        }
    }

    /** Android 10+: no permission needed; the file stays hidden from other apps until it is complete. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(image: Bitmap, name: String): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, location)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("The photo library refused the new image")
        try {
            resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                ?: throw IOException("Could not open the new image for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    /** Android 8–9: writes the file directly (needs WRITE_EXTERNAL_STORAGE) and asks the media scanner to index it. */
    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(image: Bitmap, name: String): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val file = File(directory, name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(MIME_TYPE)) { _, uri ->
                if (uri != null) continuation.resume(uri) else continuation.resume(Uri.fromFile(file))
            }
        }
    }

    /** Applies the same ink-and-paper mapping the screen uses, so the saved image matches what was shown. */
    private fun recolor(tone: Bitmap, ink: Color, paper: Color): Bitmap {
        val result = Bitmap.createBitmap(tone.width, tone.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(inkAndPaperMatrix(ink, paper)) }
        Canvas(result).drawBitmap(tone, 0f, 0f, paint)
        return result
    }

    private companion object {
        const val ALBUM = "SensorBridge"
        const val MIME_TYPE = "image/png"
        const val PNG_QUALITY = 100 // ignored for PNG, which is lossless
    }
}

/**
 * Colour matrix mapping black-and-white sketch output onto ink and paper: out = ink + (paper − ink) · in,
 * per channel, in 0–255 units. Shared by the live view and the saved image so the two always match.
 */
fun inkAndPaperMatrix(ink: Color, paper: Color): FloatArray {
    fun row(i: Float, p: Float, channel: Int) = FloatArray(5).also {
        it[channel] = p - i
        it[4] = i * 255f
    }
    return row(ink.red, paper.red, 0) + row(ink.green, paper.green, 1) + row(ink.blue, paper.blue, 2) +
        floatArrayOf(0f, 0f, 0f, 1f, 0f)
}

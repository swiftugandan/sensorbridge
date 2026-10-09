package dev.sensorbridge.tilt.render

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.sin

/**
 * A seamless tile of pale maple, generated rather than shipped: long horizontal grain streaks from stretched,
 * periodic value noise, with a finer figure on top. Built once per process.
 */
internal object WoodGrain {
    private const val SIZE = 256

    private val cached: Bitmap by lazy(::build)

    fun tile(): Bitmap = cached

    private fun build(): Bitmap {
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            // Coarse streaks: 4 lattice cells across, 32 down, so features run long and thin.
            val coarse = noise(x * 4.0 / SIZE, y * 32.0 / SIZE, 4, 32)
            val fine = noise(x * 16.0 / SIZE, y * 96.0 / SIZE, 16, 96)
            val rings = 0.5 + 0.5 * sin((y * 24.0 / SIZE + coarse * 3.0) * Math.PI * 2)
            val g = (0.5 * coarse + 0.25 * fine + 0.25 * rings).coerceIn(0.0, 1.0)
            pixels[y * SIZE + x] = mix(LIGHT, DARK, g * g)
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Smooth value noise in [0, 1] that repeats every [periodX] by [periodY] lattice cells. */
    private fun noise(x: Double, y: Double, periodX: Int, periodY: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun at(i: Int, j: Int) = hash(Math.floorMod(i, periodX), Math.floorMod(j, periodY))
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx
        return top + (bottom - top) * fy
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun hash(i: Int, j: Int): Double {
        var h = i * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun channel(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private val LIGHT = 0xFFDDBB91.toInt()
    private val DARK = 0xFFB98D61.toInt()
}

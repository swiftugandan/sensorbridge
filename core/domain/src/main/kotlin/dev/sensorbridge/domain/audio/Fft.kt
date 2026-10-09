package dev.sensorbridge.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 FFT for a fixed power-of-two [size], with twiddle factors and the bit-reversal
 * permutation precomputed so repeated transforms allocate nothing.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two >= 2, was $size" }
    }

    private val bits = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (Int.SIZE_BITS - bits) }

    /** Forward transform, X[k] = Σ x[n]·e^(−2πikn/N), overwriting [re] and [im]. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) { "arrays must have length $size" }
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            for (start in 0 until size step length) {
                var k = 0
                for (a in start until start + half) {
                    val b = a + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    // (re[b] + i·im[b]) · (c − i·s)
                    val tRe = re[b] * c + im[b] * s
                    val tIm = im[b] * c - re[b] * s
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    k += stride
                }
            }
            length *= 2
        }
    }
}

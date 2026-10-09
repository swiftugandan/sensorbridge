package dev.sensorbridge.domain.vision

import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/**
 * User-facing controls for the sketch.
 *
 * @property detail 0–1. How faint an edge still gets a line: low keeps only strong outlines, high adds texture.
 * @property lineWeight 0–1. Stroke thickness, from fine pen to broad marker.
 */
data class SketchParams(val detail: Float = 0.65f, val lineWeight: Float = 0.35f) {
    init {
        require(detail in 0f..1f) { "detail must be in 0..1, was $detail" }
        require(lineWeight in 0f..1f) { "lineWeight must be in 0..1, was $lineWeight" }
    }

    /** Gaussian scale in pixels. Kept at 0.8 or more so sensor noise is smoothed before edges are found. */
    internal val sigma: Float get() = 0.8f + lineWeight * 1.6f

    /**
     * Ink blobs smaller than this many pixels are erased as noise. Grows with stroke width, since noise
     * blobs grow with the blur too; connected strokes are far larger and always survive.
     */
    internal val minStrokePixels: Int get() = (4f + 3f * sigma * sigma).toInt()

    /**
     * Relative contrast an edge needs before it is inked: about 7% at minimum detail down to 1.2% at maximum,
     * eased so the middle of the slider still keeps interior lines.
     */
    internal val threshold: Float get() = 0.012f + 0.06f * (1f - detail).pow(1.5f)
}

/**
 * Turns camera brightness into pencil line art with a thresholded, contrast-normalised difference of Gaussians:
 *
 *   1. Levels: lift the frame's black point (1st percentile) to zero so dim scenes keep detail. Highlights are
 *      barely clipped (0.1%): the contrast measure below is relative, so stretching the white end gains nothing,
 *      while clipping it would flatten bright subjects (white keys, paper) and erase their edges.
 *   2. C = (G(σ) − G(1.6σ)) / (G(1.6σ) + ε): relative (Weber) contrast, so an edge between two dark surfaces
 *      counts as much as one between two bright ones. ε keeps sensor noise in near-black areas from exploding.
 *   3. Paper where C ≥ −θ, otherwise ink ramping in as 1 + tanh(φ·(C + θ)).
 *   4. Despeckle with hysteresis: keep an ink blob only if it is big enough to be a stroke and some part of it
 *      is clearly strong (contrast below −[STRONG_FACTOR]·θ). Noise yields small blobs that rarely contain a
 *      strong pixel; real edges are connected and strong somewhere along their length, so faint continuations
 *      of a real line survive where a higher threshold would lose them in textured scenes.
 *
 * The dark side of every edge goes negative, so outlines come out as single lines rather than the double edges
 * of a gradient filter.
 *
 * Buffers are reused between frames of the same size, so steady-state rendering allocates nothing.
 * Not safe for concurrent calls; use one instance per stream (each call parallelises internally).
 */
class SketchRenderer(
    /** ARGB colour of the strokes. */
    ink: Int,
    /** ARGB colour of the background. */
    paper: Int,
) {
    /** Output colour for each quantised negative D in [−[D_RANGE], 0): ink ramp 1 + tanh(φ·D), precomputed. */
    private val strokeLut = IntArray(STROKE_LEVELS) { i ->
        val d = -(i.toFloat() / (STROKE_LEVELS - 1)) * D_RANGE
        blend(ink, paper, 1f + tanh(SOFTNESS * d))
    }
    private val paperColor = blend(ink, paper, 1f)

    private var width = 0
    private var height = 0
    private var source = FloatArray(0)
    private var scratch = FloatArray(0)
    private var narrow = FloatArray(0)
    private var wide = FloatArray(0)

    /** Width of [render]'s output for [frame]: the frame's, or its height when rotated a quarter turn. */
    fun outputWidth(frame: LumaFrame) = if (frame.rotationDegrees % 180 == 0) frame.width else frame.height

    fun outputHeight(frame: LumaFrame) = if (frame.rotationDegrees % 180 == 0) frame.height else frame.width

    /**
     * Renders [frame] upright (and mirrored if the frame asks) into [out], which must hold width × height pixels.
     * Rows are processed in parallel; every pass writes disjoint rows, and each pass completes before the next.
     */
    fun render(frame: LumaFrame, params: SketchParams, out: IntArray) {
        require(out.size >= frame.width * frame.height) { "out holds ${out.size} pixels, need ${frame.width * frame.height}" }
        ensureBuffers(frame.width, frame.height)
        val luma = frame.luma
        val (black, white) = levels(luma, width * height)
        val gain = 1f / (white - black).toFloat().coerceAtLeast(MIN_LEVELS_SPAN)
        forEachRow { y ->
            val row = y * width
            for (i in row until row + width) source[i] = (((luma[i].toInt() and 0xFF) - black) * gain).coerceIn(0f, 1f)
        }

        val sigma = params.sigma
        gaussian(source, narrow, sigma)
        gaussian(source, wide, sigma * WIDE_TO_NARROW)

        // Relative contrast per pixel, reusing the blur scratch buffer.
        val contrast = scratch
        forEachRow { y ->
            val row = y * width
            for (i in row until row + width) contrast[i] = (narrow[i] - wide[i]) / (wide[i] + NOISE_FLOOR)
        }
        val threshold = params.threshold
        despeckle(contrast, threshold, params.minStrokePixels)

        val outWidth = outputWidth(frame)
        val scale = (STROKE_LEVELS - 1) / D_RANGE
        forEachRow { y ->
            for (x in 0 until width) {
                val d = contrast[y * width + x] + threshold
                out[destination(x, y, frame, outWidth)] =
                    if (d >= 0f) paperColor else strokeLut[(-d * scale).toInt().coerceAtMost(STROKE_LEVELS - 1)]
            }
        }
    }

    /**
     * Clears ink components (8-connected pixels with contrast below −[threshold]) that are smaller than
     * [minPixels] or never reach −[STRONG_FACTOR]·[threshold], by raising their contrast to zero so the drawing
     * pass renders them as paper.
     */
    private fun despeckle(contrast: FloatArray, threshold: Float, minPixels: Int) {
        val count = width * height
        if (labels.size != count) {
            labels = IntArray(count)
            queue = IntArray(count)
        }
        java.util.Arrays.fill(labels, 0, count, UNVISITED)
        for (start in 0 until count) {
            if (labels[start] != UNVISITED || contrast[start] >= -threshold) continue
            // Breadth-first flood fill; the queue doubles as the component's member list.
            val strong = -threshold * STRONG_FACTOR
            var hasStrong = false
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = VISITED
            while (head < tail) {
                val p = queue[head++]
                if (contrast[p] < strong) hasStrong = true
                val px = p % width
                val py = p / width
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= width) continue
                        val n = ny * width + nx
                        if (labels[n] == UNVISITED && contrast[n] < -threshold) {
                            labels[n] = VISITED
                            queue[tail++] = n
                        }
                    }
                }
            }
            if (tail < minPixels || !hasStrong) for (i in 0 until tail) contrast[queue[i]] = 0f
        }
    }

    private var labels = IntArray(0)
    private var queue = IntArray(0)

    /** The black and white points (0–255) for the levels stretch, at [BLACK_CLIP] and 1 − [WHITE_CLIP]. */
    private fun levels(luma: ByteArray, count: Int): Pair<Int, Int> {
        val histogram = IntArray(256)
        for (i in 0 until count) histogram[luma[i].toInt() and 0xFF]++
        val blackClip = (count * BLACK_CLIP).toInt()
        var black = 0
        var seen = 0
        while (black < 255 && seen + histogram[black] <= blackClip) seen += histogram[black++]
        val whiteClip = (count * WHITE_CLIP).toInt()
        var white = 255
        seen = 0
        while (white > black && seen + histogram[white] <= whiteClip) seen += histogram[white--]
        return black to white
    }

    /** Index in the upright output of source pixel ([x], [y]) after clockwise rotation and optional mirroring. */
    private fun destination(x: Int, y: Int, frame: LumaFrame, outWidth: Int): Int {
        var dx: Int
        val dy: Int
        when (frame.rotationDegrees) {
            90 -> { dx = frame.height - 1 - y; dy = x }
            180 -> { dx = frame.width - 1 - x; dy = frame.height - 1 - y }
            270 -> { dx = y; dy = frame.width - 1 - x }
            else -> { dx = x; dy = y }
        }
        if (frame.mirrored) dx = outWidth - 1 - dx
        return dy * outWidth + dx
    }

    /**
     * Separable Gaussian blur of [input] into [output], clamping at the borders. Border pixels take the clamped
     * path; the interior runs without bounds checks, and the vertical pass accumulates whole rows so memory is
     * read sequentially rather than down columns.
     */
    private fun gaussian(input: FloatArray, output: FloatArray, sigma: Float) {
        val kernel = kernel(sigma)
        val radius = kernel.size / 2
        val taps = kernel.size
        forEachRow { y ->
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                if (x < radius || x >= width - radius) {
                    for (k in 0 until taps) sum += kernel[k] * input[row + (x + k - radius).coerceIn(0, width - 1)]
                } else {
                    val start = row + x - radius
                    for (k in 0 until taps) sum += kernel[k] * input[start + k]
                }
                scratch[row + x] = sum
            }
        }
        forEachRow { y ->
            val row = y * width
            java.util.Arrays.fill(output, row, row + width, 0f)
            for (k in 0 until taps) {
                val weight = kernel[k]
                val sourceRow = (y + k - radius).coerceIn(0, height - 1) * width
                for (x in 0 until width) output[row + x] += weight * scratch[sourceRow + x]
            }
        }
    }

    private fun forEachRow(body: (Int) -> Unit) {
        IntStream.range(0, height).parallel().forEach(body)
    }

    private val kernels = java.util.concurrent.ConcurrentHashMap<Float, FloatArray>()

    private fun kernel(sigma: Float): FloatArray = kernels.getOrPut(sigma) {
        val radius = ceil(3 * sigma).toInt()
        val weights = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val total = weights.sum()
        FloatArray(weights.size) { weights[it] / total }
    }

    private fun ensureBuffers(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        source = FloatArray(w * h)
        scratch = FloatArray(w * h)
        narrow = FloatArray(w * h)
        wide = FloatArray(w * h)
    }

    private companion object {
        const val STROKE_LEVELS = 1024
        const val WIDE_TO_NARROW = 1.6f

        /** Steepness of the ink ramp: high values give crisp strokes, low values a soft pencil. */
        const val SOFTNESS = 30f

        /** ε in the contrast normalisation, in stretched brightness units (0–1). */
        const val NOISE_FLOOR = 0.06f

        /** How much stronger than the threshold some part of a stroke must be for the stroke to be kept. */
        const val STRONG_FACTOR = 2.5f

        const val UNVISITED = 0
        const val VISITED = 1

        /** Fraction of pixels allowed to clip to black, and to white, in the levels stretch. */
        const val BLACK_CLIP = 0.01f
        const val WHITE_CLIP = 0.001f

        /** Never stretch less than this range (0–255), so a flat grey frame isn't amplified into noise. */
        const val MIN_LEVELS_SPAN = 24f

        /** Beyond this depth the ramp is fully inked (tanh(−6) ≈ −1). */
        const val D_RANGE = 6f / SOFTNESS

        fun blend(ink: Int, paper: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val a = ink shr shift and 0xFF
                val b = paper shr shift and 0xFF
                return (a + (b - a) * t).toInt() and 0xFF
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

package dev.sensorbridge.ui.microphone

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sensorbridge.domain.audio.SpectrumFrame
import dev.sensorbridge.ui.theme.NumericStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Sequential colour ramp from silence (blends into the surface) to loud, as a 256-entry ARGB lookup table. */
class SpectrogramPalette(stops: List<Color>) {
    val lut: IntArray = IntArray(LUT_SIZE) { i ->
        val position = i / (LUT_SIZE - 1f) * (stops.size - 1)
        val index = position.toInt().coerceAtMost(stops.size - 2)
        lerp(stops[index], stops[index + 1], position - index).toArgb()
    }
    val stops: List<Color> = stops

    fun color(normalized: Float): Int = lut[(normalized * (LUT_SIZE - 1)).roundToInt().coerceIn(0, LUT_SIZE - 1)]

    companion object {
        private const val LUT_SIZE = 256

        // One hue, light to dark (validated sequential blue ramp). The quiet end recedes into the surface.
        private val Ramp = listOf(0xFFCDE2FB, 0xFF86B6EF, 0xFF3987E5, 0xFF1C5CAB, 0xFF0D366B).map(::Color)

        /** On a light surface loud is dark; on a dark surface the same ramp runs the other way. */
        fun forSurface(surface: Color, dark: Boolean) =
            SpectrogramPalette(listOf(surface) + if (dark) Ramp.reversed() else Ramp)
    }
}

/**
 * Paints spectrum frames into a pixel ring buffer, one column per frame, only for frames it has not painted
 * yet. Recreated when the palette, scale or history changes, after which it repaints what the history retains.
 */
class SpectrogramRenderer(
    private val history: SpectrogramHistory,
    private val scale: FrequencyScale,
    private val palette: SpectrogramPalette,
    private val floorDb: Float,
    private val ceilingDb: Float,
    val rows: Int = DEFAULT_ROWS,
) {
    val columns = history.capacity
    private val bitmap: Bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888).apply {
        eraseColor(palette.color(0f))
    }
    val image: ImageBitmap = bitmap.asImageBitmap()
    private val column = IntArray(rows)
    private var binRanges: List<IntRange>? = null
    private var painted = 0L

    /** Bitmap column that will receive the next frame; columns from here to the end hold the oldest data. */
    val head: Int get() = (painted % columns).toInt()

    var axis: FrequencyAxis? = null
        private set

    /** Paints frames added since the last call, up to [total]. */
    fun sync(total: Long) {
        for (index in maxOf(painted, total - columns) until total) {
            val frame = history.frame(index) ?: continue
            paint(index, frame)
        }
        painted = maxOf(painted, total)
    }

    private fun paint(index: Long, frame: SpectrumFrame) {
        val ranges = binRanges ?: FrequencyAxis.forFrame(scale, frame.nyquistHz).also { axis = it }
            .binRanges(rows, frame.binHz, frame.decibels.size)
            .also { binRanges = it }
        for (row in 0 until rows) {
            column[row] = palette.color(normalizeDb(frame.maxDecibels(ranges[row]), floorDb, ceilingDb))
        }
        bitmap.setPixels(column, 0, 1, (index % columns).toInt(), 0, 1, rows)
    }

    companion object {
        const val DEFAULT_ROWS = 256
    }
}

/** What the touch readout reports for the point under the finger. */
data class SpectrogramProbe(val frequencyHz: Float, val decibels: Float?, val secondsAgo: Float?)

/**
 * Scrolling spectrogram: time runs left to right (newest at the right edge), frequency bottom to top.
 * Touch and hold, then drag, to read the frequency and level under the finger.
 */
@Composable
fun Spectrogram(
    renderer: SpectrogramRenderer,
    total: Long,
    description: String,
    probeAt: (frameIndex: Long, frequencyHz: Float) -> SpectrogramProbe,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(NumericStyle).copy(color = colors.onSurface)
    var touch by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            // Touch and hold to inspect, so ordinary swipes still scroll the page this large view sits in.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touch = it },
                    onDragEnd = { touch = null },
                    onDragCancel = { touch = null },
                    onDrag = { change, _ -> touch = change.position },
                )
            },
    ) {
        renderer.sync(total)
        drawRing(renderer)

        val axis = renderer.axis ?: return@Canvas
        for (hz in axis.ticks()) {
            val y = axis.positionOf(hz) * size.height
            drawLine(colors.outline.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawLabel(textMeasurer, formatFrequency(hz), Offset(4.dp.toPx(), y), labelStyle, colors.surface)
        }

        touch?.let { point ->
            val hz = axis.frequencyAt(point.y / size.height)
            val columnsFromRight = ((size.width - point.x) / size.width * renderer.columns).toLong()
            val probe = probeAt(total - 1 - columnsFromRight, hz)
            drawLine(colors.onSurface, Offset(0f, point.y), Offset(size.width, point.y), strokeWidth = 1.dp.toPx())
            drawLine(colors.onSurface, Offset(point.x, 0f), Offset(point.x, size.height), strokeWidth = 1.dp.toPx())
            val text = buildString {
                append(formatFrequency(probe.frequencyHz, precise = true))
                probe.decibels?.let { append(String.format(Locale.getDefault(), " · %.0f dB", it)) }
                probe.secondsAgo?.let { append(String.format(Locale.getDefault(), " · −%.1f s", it)) }
            }
            val layout = textMeasurer.measure(text, labelStyle)
            val padding = 6.dp.toPx()
            val boxWidth = layout.size.width + padding * 2
            val boxHeight = layout.size.height + padding * 2
            val left = if (point.x + 12.dp.toPx() + boxWidth <= size.width) point.x + 12.dp.toPx() else point.x - 12.dp.toPx() - boxWidth
            val top = (point.y - boxHeight - 8.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(colors.surfaceContainerHigh, Offset(left, top), Size(boxWidth, boxHeight), CornerRadius(6.dp.toPx()))
            drawText(layout, topLeft = Offset(left + padding, top + padding))
        }
    }
}

/**
 * Draws the ring buffer oldest-first. The whole bitmap is drawn twice at one fixed scale, shifted so that column
 * [SpectrogramRenderer.head] lands at the left edge; drawing the two halves separately would give them slightly
 * different scales every frame and make the image wobble as it scrolls.
 */
private fun DrawScope.drawRing(renderer: SpectrogramRenderer) {
    val shift = renderer.head.toFloat() / renderer.columns * size.width
    val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
    clipRect {
        translate(left = -shift) {
            drawImage(renderer.image, dstSize = dstSize, filterQuality = FilterQuality.Low)
        }
        translate(left = size.width - shift) {
            drawImage(renderer.image, dstSize = dstSize, filterQuality = FilterQuality.Low)
        }
    }
}

private fun DrawScope.drawLabel(textMeasurer: TextMeasurer, text: String, anchor: Offset, style: TextStyle, background: Color) {
    val layout = textMeasurer.measure(text, style)
    val padding = 3.dp.toPx()
    val top = (anchor.y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
    drawRoundRect(
        background.copy(alpha = 0.75f),
        Offset(anchor.x - padding, top - 1.dp.toPx()),
        Size(layout.size.width + padding * 2, layout.size.height + 2.dp.toPx()),
        CornerRadius(4.dp.toPx()),
    )
    drawText(layout, topLeft = Offset(anchor.x, top))
}

/** "500", "1k", "2.5k", or with [precise] "1.02 kHz" / "440 Hz". */
fun formatFrequency(hz: Float, precise: Boolean = false): String = when {
    precise && hz >= 1_000f -> String.format(Locale.getDefault(), "%.2f kHz", hz / 1_000f)
    precise -> String.format(Locale.getDefault(), "%.0f Hz", hz)
    hz >= 1_000f && hz % 1_000f == 0f -> "${(hz / 1_000f).toInt()}k"
    hz >= 1_000f -> String.format(Locale.getDefault(), "%.1fk", hz / 1_000f)
    else -> hz.toInt().toString()
}

/** Horizontal colour key for the spectrogram, labelled at both ends. */
@Composable
fun SpectrogramLegend(palette: SpectrogramPalette, floorDb: Float, ceilingDb: Float, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.merge(NumericStyle).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val barHeight = 8.dp.toPx()
        drawRoundRect(Brush.horizontalGradient(palette.stops), size = Size(size.width, barHeight), cornerRadius = CornerRadius(4.dp.toPx()))
        drawRoundRect(outline, size = Size(size.width, barHeight), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
        val low = textMeasurer.measure(String.format(Locale.getDefault(), "%.0f dB", floorDb), style)
        val high = textMeasurer.measure(String.format(Locale.getDefault(), "%.0f dB", ceilingDb), style)
        drawText(low, topLeft = Offset(0f, barHeight + 4.dp.toPx()))
        drawText(high, topLeft = Offset(size.width - high.size.width, barHeight + 4.dp.toPx()))
    }
}

package dev.sensorbridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.sensorbridge.ui.detail.HistorySnapshot
import dev.sensorbridge.ui.theme.LocalSeriesColors
import dev.sensorbridge.ui.theme.NumericStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * Scrolling multi-series line chart over a fixed time window.
 *
 * Touch and drag horizontally to inspect: a crosshair snaps to the nearest sample and a tooltip lists each
 * series' value at that moment. Series colours follow channel order, so an axis keeps its colour everywhere.
 *
 * @param stepped draw step lines, for on-change sensors whose value holds until the next report.
 */
@Composable
fun LiveChart(
    snapshot: HistorySnapshot,
    scale: AxisScale,
    labels: List<String>,
    stepped: Boolean,
    formatValue: (Float) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val seriesColors = LocalSeriesColors.current
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.merge(NumericStyle).copy(color = colors.onSurfaceVariant)
    val tooltipStyle = MaterialTheme.typography.labelMedium.merge(NumericStyle).copy(color = colors.onSurface)
    var scrubX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { offset ->
                    scrubX = offset.x
                    tryAwaitRelease()
                    scrubX = null
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { scrubX = it.x },
                    onDragEnd = { scrubX = null },
                    onDragCancel = { scrubX = null },
                    onHorizontalDrag = { change, _ -> scrubX = change.position.x },
                )
            },
    ) {
        val plot = PlotArea(top = 6.dp.toPx(), bottom = size.height - 6.dp.toPx(), width = size.width, snapshot, scale)

        drawGrid(plot, scale, colors.outlineVariant, textMeasurer, axisStyle)

        clipRect(0f, 0f, size.width, size.height) {
            snapshot.series.forEachIndexed { channel, values ->
                drawSeries(plot, snapshot.timestamps, values, stepped, seriesColors[channel])
            }
        }

        val x = scrubX
        if (x != null && !snapshot.isEmpty) {
            drawScrubber(
                plot, snapshot, x, labels, seriesColors.slots, colors.onSurfaceVariant, colors.surfaceContainerHigh,
                colors.surface, textMeasurer, tooltipStyle, formatValue,
            )
        }
    }
}

private class PlotArea(
    val top: Float,
    val bottom: Float,
    val width: Float,
    snapshot: HistorySnapshot,
    private val scale: AxisScale,
) {
    private val start = snapshot.startNanos
    private val span = (snapshot.endNanos - snapshot.startNanos).coerceAtLeast(1)

    fun x(t: Long): Float = (t - start).toFloat() / span * width
    fun y(v: Float): Float = bottom - (v - scale.min) / scale.span * (bottom - top)
    fun timeAt(x: Float): Long = start + (x / width * span).toLong()
}

private fun DrawScope.drawGrid(
    plot: PlotArea,
    scale: AxisScale,
    color: Color,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    val decimals = decimalsForStep(scale.step)
    for (tick in scale.ticks) {
        val y = plot.y(tick)
        val isZero = tick == 0f
        drawLine(
            color = if (isZero) color else color.copy(alpha = 0.55f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isZero) 1.5.dp.toPx() else 1.dp.toPx(),
        )
        val label = textMeasurer.measure(String.format(Locale.getDefault(), "%.${decimals}f", tick), style)
        val labelY = (y - label.size.height - 2.dp.toPx()).coerceAtLeast(0f)
        drawText(label, topLeft = Offset(4.dp.toPx(), labelY))
    }
}

private fun DrawScope.drawSeries(plot: PlotArea, timestamps: LongArray, values: FloatArray, stepped: Boolean, color: Color) {
    val path = Path()
    var penDown = false
    var lastY = 0f
    for (i in timestamps.indices) {
        val v = values[i]
        if (!v.isFinite()) {
            penDown = false
            continue
        }
        val x = plot.x(timestamps[i])
        val y = plot.y(v)
        when {
            !penDown -> path.moveTo(x, y)
            stepped -> {
                path.lineTo(x, lastY)
                path.lineTo(x, y)
            }
            else -> path.lineTo(x, y)
        }
        penDown = true
        lastY = y
    }
    // A held value continues to the present.
    if (stepped && penDown) path.lineTo(size.width, lastY)
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawScrubber(
    plot: PlotArea,
    snapshot: HistorySnapshot,
    touchX: Float,
    labels: List<String>,
    seriesColors: List<Color>,
    lineColor: Color,
    tooltipBackground: Color,
    ringColor: Color,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    formatValue: (Float) -> String,
) {
    val index = nearestIndex(snapshot.timestamps, plot.timeAt(touchX))
    val t = snapshot.timestamps[index]
    val x = plot.x(t).coerceIn(0f, size.width)

    drawLine(lineColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1.dp.toPx())
    snapshot.series.forEachIndexed { channel, values ->
        val v = values[index]
        if (v.isFinite()) {
            val center = Offset(x, plot.y(v))
            drawCircle(ringColor, radius = 6.dp.toPx(), center = center)
            drawCircle(seriesColors[channel.coerceAtMost(seriesColors.lastIndex)], radius = 4.dp.toPx(), center = center)
        }
    }

    val secondsAgo = (snapshot.endNanos - t) / 1e9
    val lines = buildList {
        add(String.format(Locale.getDefault(), "−%.2f s", secondsAgo))
        snapshot.series.forEachIndexed { channel, values ->
            add("${labels.getOrElse(channel) { "v$channel" }}  ${formatValue(values[index])}")
        }
    }
    val measured = lines.map { textMeasurer.measure(it, style) }
    val swatch = 8.dp.toPx()
    val gap = 6.dp.toPx()
    val padding = 8.dp.toPx()
    val textWidth = measured.maxOf { it.size.width }
    val boxWidth = padding * 2 + swatch + gap + textWidth
    val boxHeight = padding * 2 + measured.sumOf { it.size.height }
    val left = if (x + 12.dp.toPx() + boxWidth <= size.width) x + 12.dp.toPx() else x - 12.dp.toPx() - boxWidth
    val top = plot.top

    drawRoundRect(tooltipBackground, Offset(left, top), Size(boxWidth, boxHeight), CornerRadius(8.dp.toPx()))
    var lineTop = top + padding
    measured.forEachIndexed { i, layout ->
        if (i > 0) {
            val color = seriesColors[(i - 1).coerceAtMost(seriesColors.lastIndex)]
            drawRoundRect(
                color,
                Offset(left + padding, lineTop + (layout.size.height - swatch) / 2),
                Size(swatch, swatch),
                CornerRadius(2.dp.toPx()),
            )
        }
        drawText(layout, topLeft = Offset(left + padding + swatch + gap, lineTop))
        lineTop += layout.size.height
    }
}

private fun nearestIndex(timestamps: LongArray, target: Long): Int {
    var lo = 0
    var hi = timestamps.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (timestamps[mid] < target) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && target - timestamps[lo - 1] < timestamps[lo] - target) lo - 1 else lo
}

/** Fewest decimals that print every tick exactly, e.g. 1 for a 2.5 step. */
private fun decimalsForStep(step: Float): Int =
    (0..MAX_TICK_DECIMALS).firstOrNull { d ->
        val scaled = step * 10f.pow(d)
        abs(scaled - round(scaled)) < 1e-3f
    } ?: MAX_TICK_DECIMALS

private const val MAX_TICK_DECIMALS = 4

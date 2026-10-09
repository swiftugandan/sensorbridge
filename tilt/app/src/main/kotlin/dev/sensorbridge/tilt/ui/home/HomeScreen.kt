package dev.sensorbridge.tilt.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sensorbridge.tilt.R
import dev.sensorbridge.tilt.data.Progress
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.ui.components.Stars
import dev.sensorbridge.tilt.ui.components.formatSeconds
import dev.sensorbridge.tilt.ui.theme.TiltColors
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    viewModel: ProgressViewModel,
    onPlay: (String) -> Unit,
    onLevels: () -> Unit,
    onSettings: () -> Unit,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val daily = remember { LevelCatalog.daily(LocalDate.now()) }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RollingMarble(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge)
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            progress?.let { StarsSummary(it) }
            Spacer(Modifier.height(32.dp))

            val p = progress
            val next = p?.nextUp ?: LevelCatalog.campaign.first()
            Button(
                onClick = { onPlay(next.id) },
                enabled = p != null,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    if (p == null || p.records.isEmpty()) stringResource(R.string.play) else stringResource(R.string.continue_level, next.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onLevels, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.GridView, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.levels))
                }
                FilledTonalButton(onClick = { onPlay(daily.id) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.daily), maxLines = 1)
                }
            }
            progress?.records?.get(daily.id)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.daily_best, formatSeconds(it.bestSeconds)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StarsSummary(progress: Progress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Stars(count = 1, max = 1, size = 18.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            pluralStringResource(R.plurals.stars_progress, progress.campaignMaxStars, progress.campaignStars, progress.campaignMaxStars),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The title art: a steel marble circling a gold-ringed goal on a maple board. */
@Composable
private fun RollingMarble(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "marble")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier) {
        val c = center
        val board = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(TiltColors.Maple, Color(0xFFB98D61)), c, board), board, c)
        drawCircle(Color(0x33000000), board, c, style = Stroke(board * 0.04f))
        val goal = board * 0.2f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF050302), Color(0xFF3A2819)), c, goal), goal, c)
        drawCircle(
            Brush.linearGradient(listOf(TiltColors.Gold, TiltColors.GoldDeep), c - Offset(goal, goal), c + Offset(goal, goal)),
            goal * 1.1f, c, style = Stroke(board * 0.05f),
        )
        val orbit = board * 0.62f
        // An ellipse with a slight wobble, as if the board were being tilted round and round.
        val p = c + Offset(cos(angle) * orbit, sin(angle) * orbit * 0.85f)
        val r = board * 0.16f
        drawCircle(Color(0x55000000), r * 1.05f, p + Offset(r * 0.3f, r * 0.4f))
        drawCircle(
            Brush.radialGradient(
                0f to Color.White, 0.18f to Color(0xFFDDE2E8), 0.55f to Color(0xFF8E97A3), 0.85f to Color(0xFF3A404A), 1f to Color(0xFF1E2228),
                center = p - Offset(r * 0.35f, r * 0.4f), radius = r * 1.45f,
            ),
            r, p,
        )
        drawCircle(Color.White.copy(alpha = 0.9f), r * 0.17f, p - Offset(r * 0.36f, r * 0.42f))
    }
}

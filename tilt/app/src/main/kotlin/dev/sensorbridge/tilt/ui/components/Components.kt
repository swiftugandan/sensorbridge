package dev.sensorbridge.tilt.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sensorbridge.tilt.engine.game.Scoring
import dev.sensorbridge.tilt.ui.theme.TiltColors
import java.util.Locale

/** Earned stars in gold, the rest dim. Decorative: callers describe the count in text for accessibility. */
@Composable
fun Stars(count: Int, modifier: Modifier = Modifier, size: Dp = 16.dp, max: Int = Scoring.MAX_STARS) {
    Row(modifier) {
        repeat(max) { i ->
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = if (i < count) TiltColors.Gold else TiltColors.StarEmpty,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** 83.4 s → "1:23.4". */
fun formatSeconds(seconds: Double): String {
    val tenths = (seconds * 10).toLong()
    return formatTenths(tenths)
}

fun formatTenths(tenths: Long): String =
    String.format(Locale.US, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)

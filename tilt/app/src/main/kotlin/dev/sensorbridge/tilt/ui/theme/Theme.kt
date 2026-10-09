package dev.sensorbridge.tilt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Warm workshop colours: walnut, maple, brass. One fixed palette, since the board is a physical object. */
object TiltColors {
    val Walnut = Color(0xFF17120E)
    val WalnutRaised = Color(0xFF231A14)
    val WalnutHigh = Color(0xFF2E231B)
    val Maple = Color(0xFFDDBB91)
    val Gold = Color(0xFFF6C35A)
    val GoldDeep = Color(0xFFA8701C)
    val Teal = Color(0xFF2FA08C)
    val Ink = Color(0xFFF3E9DC)
    val InkMuted = Color(0xFFB9A996)
    val InkFaint = Color(0xFF7D6E60)
    val StarEmpty = Color(0xFF4A3B2F)
    val Danger = Color(0xFFE57B62)
}

private val scheme = darkColorScheme(
    primary = TiltColors.Gold,
    onPrimary = Color(0xFF2B1A05),
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFFFDFA6),
    secondary = TiltColors.Teal,
    onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF16473F),
    onSecondaryContainer = Color(0xFFB4F0E2),
    background = TiltColors.Walnut,
    onBackground = TiltColors.Ink,
    surface = TiltColors.Walnut,
    onSurface = TiltColors.Ink,
    surfaceVariant = TiltColors.WalnutHigh,
    onSurfaceVariant = TiltColors.InkMuted,
    surfaceContainerLowest = Color(0xFF120E0B),
    surfaceContainerLow = Color(0xFF1D1611),
    surfaceContainer = TiltColors.WalnutRaised,
    surfaceContainerHigh = TiltColors.WalnutHigh,
    surfaceContainerHighest = Color(0xFF392C22),
    outline = Color(0xFF6B5A4B),
    outlineVariant = Color(0xFF43362B),
    error = TiltColors.Danger,
)

/** Timers and scores use tabular figures so digits don't shuffle sideways as they change. */
val NumericStyle = TextStyle(fontFeatureSettings = "tnum")

private val typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun TiltTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

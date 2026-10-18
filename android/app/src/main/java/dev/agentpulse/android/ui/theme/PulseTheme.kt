package dev.agentpulse.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PulseDarkColorScheme = darkColorScheme(
    primary = PulseColors.PrimaryCyan,
    onPrimary = Color(0xFF03121C),
    primaryContainer = Color(0xFF0B2B3E),
    onPrimaryContainer = PulseColors.PrimaryCyan,
    secondary = PulseColors.SuccessGreen,
    onSecondary = Color(0xFF031F16),
    tertiary = PulseColors.WarningAmber,
    onTertiary = Color(0xFF201600),
    error = PulseColors.ErrorRose,
    onError = Color(0xFF2B0308),
    background = PulseColors.Background,
    onBackground = PulseColors.TextPrimary,
    surface = PulseColors.Surface,
    onSurface = PulseColors.TextPrimary,
    surfaceVariant = PulseColors.SurfaceVariant,
    onSurfaceVariant = PulseColors.TextSecondary,
    outline = PulseColors.Outline,
    outlineVariant = Color(0xFF21262D),
    surfaceContainerHighest = PulseColors.SurfaceVariant,
    surfaceContainerHigh = PulseColors.Surface,
    surfaceContainer = PulseColors.Surface,
    surfaceContainerLow = PulseColors.Background,
    surfaceContainerLowest = PulseColors.CodeTerminalBg,
    scrim = Color(0xCC000000),
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseDarkColorScheme,
        typography = PulseTypography,
        content = content,
    )
}

package dev.agentpulse.android.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import dev.agentpulse.android.ui.theme.PulseWindowBreakpoints

/** Responsive form factor from design report 4.5. */
enum class PulseWindowClass {
    /** Phone portrait: bottom navigation, single pane. */
    Compact,

    /** Phone landscape / small tablet: navigation rail + two panes (40% / 60%). */
    TwoPane,

    /** Tablet / foldable: navigation rail + three-column board (25% / 45% / 30%). */
    ThreePane,
}

/**
 * Pure classification (unit-tested):
 * width >= 840dp -> three-column board; landscape or medium width -> two panes; otherwise compact.
 */
fun classifyWindow(widthDp: Int, isLandscape: Boolean): PulseWindowClass = when {
    widthDp >= PulseWindowBreakpoints.EXPANDED_WIDTH_DP -> PulseWindowClass.ThreePane
    isLandscape || widthDp >= PulseWindowBreakpoints.MEDIUM_WIDTH_DP -> PulseWindowClass.TwoPane
    else -> PulseWindowClass.Compact
}

@Composable
fun rememberPulseWindowClass(): PulseWindowClass {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return classifyWindow(configuration.screenWidthDp, isLandscape)
}

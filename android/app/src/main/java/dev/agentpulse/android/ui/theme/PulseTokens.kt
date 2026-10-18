package dev.agentpulse.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale of design report 4.2.C: 4 / 8 / 12 / 16 / 24 / 32 dp. */
object PulseSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii of design report 4.2.C. */
object PulseRadii {
    val badge: Dp = 6.dp
    val card: Dp = 12.dp
    val sheet: Dp = 16.dp
}

/** Motion spec of design report 4.2.C. */
object PulseMotion {
    /** Status breathing light: 1200ms infinite loop, FastOutSlowInEasing. */
    const val STATUS_PULSE_MS = 1200
    val statusPulseEasing: Easing = FastOutSlowInEasing

    /** List item enter animation: 250ms Decelerate. */
    const val LIST_ITEM_ENTER_MS = 250
    val listEnterEasing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}

/** Responsive breakpoints used by 4.5 (compact portrait / two-pane landscape / three-column tablet). */
object PulseWindowBreakpoints {
    const val MEDIUM_WIDTH_DP = 600
    const val EXPANDED_WIDTH_DP = 840
}

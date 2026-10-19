package dev.agentpulse.android.ui.common

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.core.PulseTime
import dev.agentpulse.android.data.AnsiParser
import dev.agentpulse.android.data.ConnectionState
import dev.agentpulse.android.data.model.AgentStatus
import dev.agentpulse.android.data.model.EventKind
import dev.agentpulse.android.data.model.EventSource
import dev.agentpulse.android.data.model.Severity
import dev.agentpulse.android.data.model.TaskKind
import dev.agentpulse.android.data.model.TaskState
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseMotion
import dev.agentpulse.android.ui.theme.PulseRadii
import dev.agentpulse.android.ui.theme.PulseSpacing

// ---------------------------------------------------------------- colours

fun connectionColor(state: ConnectionState): Color = when (state) {
    ConnectionState.Healthy -> PulseColors.SuccessGreen
    ConnectionState.Stale -> PulseColors.WarningAmber
    ConnectionState.Disconnected -> PulseColors.ErrorRose
}

fun taskStateColor(state: TaskState): Color = when (state) {
    TaskState.InFlight -> PulseColors.PrimaryCyan
    TaskState.Done -> PulseColors.SuccessGreen
    TaskState.Queued, TaskState.Held -> PulseColors.TextSecondary
    TaskState.Blocked -> PulseColors.WarningAmber
    TaskState.Failed -> PulseColors.ErrorRose
    TaskState.Unknown -> PulseColors.TextSecondary
}

fun agentStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.Working -> PulseColors.PrimaryCyan
    AgentStatus.Idle -> PulseColors.TextSecondary
    AgentStatus.Blocked -> PulseColors.WarningAmber
    AgentStatus.Done -> PulseColors.SuccessGreen
    AgentStatus.Unknown -> PulseColors.TextMuted
}

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.Critical, Severity.Error -> PulseColors.ErrorRose
    Severity.Warning -> PulseColors.WarningAmber
    Severity.Info -> PulseColors.PrimaryCyan
    Severity.Debug -> PulseColors.TextMuted
    Severity.Unknown -> PulseColors.TextSecondary
}

/** English kind tag used on cards ("Scout" / "Ship"), kept English per report 4.3. */
fun kindLabel(kind: TaskKind): String = when (kind) {
    TaskKind.Scout -> "Scout"
    TaskKind.Ship -> "Ship"
    TaskKind.Secondmate -> "Secondmate"
    TaskKind.Custom -> "Custom"
    TaskKind.Unknown -> "Task"
}

/** English source tag used on the live timeline ("FirstMate" / "Herdr" / "Pi"). */
fun sourceLabel(source: EventSource): String = when (source) {
    EventSource.Firstmate -> "FirstMate"
    EventSource.Herdr -> "Herdr"
    EventSource.Pi -> "Pi"
    EventSource.Service -> "Service"
    EventSource.Unknown -> "Unknown"
}

/** Compound timeline tag "[Pi/Tool]" (report 4.3 screen 3). */
fun eventTag(source: EventSource, kind: EventKind): String = "[${sourceLabel(source)}/${kind.code}]"

// ---------------------------------------------------------------- atoms

/** Breathing status light (report 4.2.C: 1200ms infinite FastOutSlowInEasing). */
@Composable
fun PulseStatusDot(
    color: Color,
    animated: Boolean,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val alpha = if (animated) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = PulseMotion.STATUS_PULSE_MS,
                    easing = PulseMotion.statusPulseEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusPulseAlpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

/** Non-clickable tag: kind badges, status pills (6dp radius, report 4.2.C). */
@Composable
fun PulseTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PulseRadii.badge),
        color = if (filled) color.copy(alpha = 0.9f) else color.copy(alpha = 0.14f),
        border = if (filled) null else BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) PulseColors.Background else color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Clickable filter chip; 48dp minimum touch target (report 4.5). */
@Composable
fun PulseFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (selected) PulseColors.PrimaryCyan else PulseColors.TextSecondary
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(PulseRadii.badge),
        color = if (selected) PulseColors.PrimaryCyan.copy(alpha = 0.16f) else PulseColors.SurfaceVariant,
        border = BorderStroke(1.dp, if (selected) PulseColors.PrimaryCyan.copy(alpha = 0.55f) else PulseColors.Outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                modifier = Modifier.padding(horizontal = PulseSpacing.md),
            )
        }
    }
}

/** Card container: 12dp radius, #161B22 surface, 1dp #30363D outline. */
@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = RoundedCornerShape(PulseRadii.card),
        color = PulseColors.Surface,
        border = BorderStroke(1.dp, PulseColors.Outline),
    ) {
        Column(
            modifier = Modifier.padding(PulseSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            content = content,
        )
    }
}

/** Section header, wireframe style "◆ 正在执行的任务". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "◆",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.PrimaryCyan,
        )
        Spacer(Modifier.width(PulseSpacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = PulseColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** "label: value" row used across detail surfaces; values stay English/monospace. */
@Composable
fun MetaRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    valueColor: Color = PulseColors.TextSecondary,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextMuted,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            style = if (mono) PulseMonoText.chip else MaterialTheme.typography.bodySmall,
            color = if (value.isNullOrBlank()) PulseColors.TextMuted else valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------- states

@Composable
fun LoadingState(message: String = "正在加载…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PulseSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        CircularProgressIndicator(
            color = PulseColors.PrimaryCyan,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextSecondary)
    }
}

@Composable
fun EmptyState(message: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PulseSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = PulseColors.TextSecondary)
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PulseSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
    ) {
        Text(
            text = "无法获取数据",
            style = MaterialTheme.typography.titleMedium,
            color = PulseColors.ErrorRose,
        )
        Text(
            text = message,
            style = PulseMonoText.chip,
            color = PulseColors.TextSecondary,
        )
        TextButton(onClick = onRetry) {
            Text(text = "重试", color = PulseColors.PrimaryCyan)
        }
    }
}

/**
 * Connection banners of report 3.2.C / 4.3: amber non-blocking notice for [ConnectionState.Stale],
 * blocking rose banner for [ConnectionState.Disconnected].
 */
@Composable
fun ConnectionBanners(connection: ConnectionState, modifier: Modifier = Modifier) {
    when (connection) {
        ConnectionState.Healthy -> Unit
        ConnectionState.Stale -> ConnectionBanner(
            text = "连接不稳定，正在保持同步…（超过 15 秒未收到数据）",
            color = PulseColors.WarningAmber,
            modifier = modifier,
        )

        ConnectionState.Disconnected -> ConnectionBanner(
            text = "与 AgentPulse 监控服务断开连接 · 正在自动重连（退避 1/2/4/8/15s）",
            color = PulseColors.ErrorRose,
            modifier = modifier,
            blocking = true,
        )
    }
}

@Composable
private fun ConnectionBanner(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    blocking: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = if (blocking) 0.22f else 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PulseSpacing.md, vertical = if (blocking) PulseSpacing.md else PulseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            PulseStatusDot(color = color, animated = true, size = 8.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

/**
 * In-app banner for active Critical tier alerts (Captain's intent).
 * Prominently displayed across all screens with direct navigation to the alert.
 */
@Composable
fun CriticalAlertBanner(
    alerts: List<dev.agentpulse.android.data.model.PulseAlert>,
    onOpenAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alerts.isEmpty()) return
    val latest = alerts.first()
    val count = alerts.size
    val headline = if (count == 1) {
        "【严重告警】${latest.title}：${latest.message}"
    } else {
        "【严重告警 · ${count} 条待处理】最新：${latest.title}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAlerts),
        color = PulseColors.ErrorRose.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, PulseColors.ErrorRose.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            PulseStatusDot(color = PulseColors.ErrorRose, animated = true, size = 10.dp)
            Text(
                text = headline,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.ErrorRose,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PulseTag(text = "查看 ➜", color = PulseColors.ErrorRose, filled = true)
        }
    }
}


// ---------------------------------------------------------------- clipboard

/** Copy-to-clipboard affordance (alerts screen "复制指令", terminal blocks). */
@Composable
fun CopyAction(
    text: String,
    label: String = "复制",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        },
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = PulseColors.PrimaryCyan)
    }
}

// ---------------------------------------------------------------- terminal

/**
 * Monospaced terminal block on the OLED terminal background. Accepts raw lines which may contain
 * ANSI SGR colour codes (report 3.1 `format=ansi`); they are parsed into styled spans.
 */
@Composable
fun TerminalBlock(
    lines: List<String>,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    copyable: Boolean = true,
    placeholder: String = "（暂无终端输出）",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TERMINAL",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            if (copyable && lines.isNotEmpty()) {
                CopyAction(text = lines.joinToString("\n"), label = "复制全部")
            }
        }
        Surface(
            shape = RoundedCornerShape(PulseRadii.badge),
            color = PulseColors.CodeTerminalBg,
            border = BorderStroke(1.dp, PulseColors.Outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = placeholder,
                    style = PulseMonoText.line,
                    color = PulseColors.TextMuted,
                    modifier = Modifier.padding(PulseSpacing.sm),
                )
            } else {
                val spans = remember(lines) { AnsiParser.parseLines(lines) }
                Column(modifier = Modifier.padding(PulseSpacing.sm)) {
                    for ((index, lineSpans) in spans.withIndex()) {
                        if (index >= maxLines) break
                        TerminalLine(lineSpans)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(spans: List<AnsiParser.AnsiSpan>) {
    val annotated = remember(spans) {
        AnnotatedString.Builder().apply {
            for (span in spans) {
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = span.argb?.let { Color(it) } ?: PulseColors.TextSecondary,
                        fontWeight = if (span.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                append(span.text)
                pop()
            }
        }.toAnnotatedString()
    }
    Text(
        text = annotated,
        style = PulseMonoText.line,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- top bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseTopBar(
    title: String,
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
            )
        },
        actions = {
            ConnectionChip(connection)
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PulseColors.Background),
    )
}

@Composable
fun ConnectionChip(state: ConnectionState) {
    val color = connectionColor(state)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
        modifier = Modifier.padding(end = PulseSpacing.sm),
    ) {
        PulseStatusDot(color = color, animated = state != ConnectionState.Disconnected)
        Text(
            text = state.labelZh,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Ticking wall clock (wireframe shows it in the top bar). */
@Composable
fun PulseClock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    Text(
        text = PulseTime.formatClock(now),
        style = PulseMonoText.chip,
        color = PulseColors.TextSecondary,
        modifier = modifier,
    )
}

/** Duration chip "00:12:45" for in-flight tasks; [sinceEpochMs] may be null when unknown. */
@Composable
fun DurationText(sinceEpochMs: Long?, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val text = if (sinceEpochMs == null) "--:--:--" else PulseTime.formatDuration(now - sinceEpochMs)
    Text(
        text = text,
        style = PulseMonoText.chip,
        color = PulseColors.TextSecondary,
        modifier = modifier,
    )
}

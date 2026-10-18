package dev.agentpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.core.PulseTime
import dev.agentpulse.android.data.DataPhase
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.model.EventKind
import dev.agentpulse.android.data.model.PulseEvent
import dev.agentpulse.android.ui.common.EmptyState
import dev.agentpulse.android.ui.common.ErrorState
import dev.agentpulse.android.ui.common.LoadingState
import dev.agentpulse.android.ui.common.PulseFilterChip
import dev.agentpulse.android.ui.common.eventTag
import dev.agentpulse.android.ui.common.severityColor
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseSpacing

/**
 * 屏 3：实时动态流（report 4.3 wireframe 3）.
 * Virtualized timeline that auto-scrolls to the newest event and pauses when the user scrolls up.
 */
@Composable
fun LiveFeedScreen(
    state: PulseUiState,
    onRefresh: () -> Unit,
) {
    val events = state.events
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
        }
    }

    // Pause auto-scroll when the user drags away from the newest event.
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress && !isAtBottom) {
            autoScroll = false
        }
    }

    // Follow the tail when auto-scroll is on.
    LaunchedEffect(events.size, autoScroll) {
        if (autoScroll && events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Text(
                text = "实时动态流 (Live Activity)",
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            PulseFilterChip(
                text = if (autoScroll) "自动滚动: 开" else "自动滚动: 关",
                selected = autoScroll,
                onClick = { autoScroll = !autoScroll },
            )
            if (!autoScroll && !isAtBottom) {
                PulseFilterChip(
                    text = "回到底部",
                    selected = false,
                    onClick = { autoScroll = true },
                )
            }
        }

        when {
            events.isEmpty() && state.eventsState.phase == DataPhase.Loading -> LoadingState(message = "正在接收事件流…")

            events.isEmpty() && state.eventsState.phase == DataPhase.Error -> ErrorState(
                message = state.eventsState.error ?: "无法获取事件流",
                onRetry = onRefresh,
            )

            events.isEmpty() -> EmptyState("暂无动态", "Agent 开始工作后，工具调用与状态变更会实时出现在这里")

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = PulseSpacing.md,
                    vertical = PulseSpacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                items(events, key = { it.dedupeKey }) { event ->
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: PulseEvent) {
    val accent = severityColor(event.severityLevel)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = PulseTime.formatIsoClock(event.timestamp),
                style = PulseMonoText.chip,
                color = PulseColors.TextMuted,
            )
            Spacer(Modifier.padding(start = PulseSpacing.sm))
            Text(
                text = eventTag(event.source, event.kind),
                style = PulseMonoText.chip,
                color = accent,
            )
            event.taskId?.let { id ->
                Spacer(Modifier.padding(start = PulseSpacing.sm))
                Text(
                    text = id,
                    style = PulseMonoText.chip,
                    color = PulseColors.TextSecondary,
                )
            }
        }
        eventBody(event).forEach { line ->
            Text(
                text = line,
                style = PulseMonoText.line,
                color = PulseColors.TextSecondary,
                modifier = Modifier.padding(start = PulseSpacing.lg),
            )
        }
    }
}

private fun eventBody(event: PulseEvent): List<String> = when (event.kind) {
    EventKind.ToolCall -> listOfNotNull(
        event.payloadString("toolName")?.let { "tool: $it" },
        event.payloadString("command")?.let { "$ $it" },
    )

    EventKind.ToolResult -> listOfNotNull(
        event.payloadString("exit_code")?.let { "↳ exit=$it" },
        event.payloadString("summary"),
        event.payloadString("text")?.lineSequence()?.firstOrNull(),
    )

    EventKind.ThinkingChunk -> listOfNotNull(
        event.payloadString("thinking") ?: event.payloadString("text"),
    )

    EventKind.TaskStateChanged -> listOfNotNull(
        event.payloadString("state")?.let { "state -> $it" },
        event.payloadString("note"),
    )

    EventKind.AgentStatusChanged -> listOfNotNull(
        event.payloadString("current_status")?.let { "status -> $it" },
        event.payloadString("pane_id")?.let { "pane: $it" },
    )

    EventKind.PaneOutput -> listOfNotNull(
        event.payloadString("line") ?: event.payloadString("text"),
    )

    EventKind.DecisionNeeded -> listOfNotNull(
        event.payloadString("summary") ?: event.payloadString("question"),
    )

    EventKind.TaskCompleted, EventKind.TaskFailed -> listOfNotNull(
        event.payloadString("note") ?: event.payloadString("reason"),
    )

    else -> emptyList()
}

package dev.agentpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.core.PulseTime
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.ui.common.CopyAction
import dev.agentpulse.android.ui.common.EmptyState
import dev.agentpulse.android.ui.common.PulseCard
import dev.agentpulse.android.ui.common.PulseTag
import dev.agentpulse.android.ui.common.SectionHeader
import dev.agentpulse.android.ui.common.severityColor
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseSpacing

/**
 * 屏 4：异常提醒与阻断（report 4.3 wireframe 4）.
 * Groups needs-decision, failures and disconnects, each with a copy-command affordance.
 * v1 is read-only: commands are copied to the clipboard for the desktop, never executed here.
 */
@Composable
fun AlertsScreen(
    state: PulseUiState,
    onOpenAlert: (String) -> Unit,
    onClearRead: () -> Unit,
) {
    val alerts = state.alerts

    val decisions = alerts.filter { it.category == AlertCategory.DecisionRequired }
    val failures = alerts.filter {
        it.category == AlertCategory.TaskFailed ||
            it.category == AlertCategory.AgentUnresponsive ||
            it.category == AlertCategory.PipelineBlocked ||
            it.category == AlertCategory.Unknown
    }
    val disconnects = alerts.filter { it.category == AlertCategory.ServiceDisconnected }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "异常提醒与待办",
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (alerts.isNotEmpty()) {
                TextButton(onClick = onClearRead) {
                    Text("清空已读", color = PulseColors.PrimaryCyan)
                }
            }
        }

        if (alerts.isEmpty()) {
            EmptyState(
                message = "暂无异常",
                hint = "任务失败、Agent 阻塞或等待人工决策时，异常会按类别出现在这里",
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            if (decisions.isNotEmpty()) {
                item(key = "hdr-decision") { SectionHeader(title = "等待人工决策 (NEEDS-DECISION)") }
                items(decisions, key = { "d-${it.alertId}" }) { alert -> AlertCard(alert, onOpenAlert) }
            }
            if (failures.isNotEmpty()) {
                item(key = "hdr-fail") { SectionHeader(title = "失败与阻塞 (FAILURES)") }
                items(failures, key = { "f-${it.alertId}" }) { alert -> AlertCard(alert, onOpenAlert) }
            }
            if (disconnects.isNotEmpty()) {
                item(key = "hdr-disc") { SectionHeader(title = "连接断开 (SERVICE-DISCONNECTED)") }
                items(disconnects, key = { "c-${it.alertId}" }) { alert -> AlertCard(alert, onOpenAlert) }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: PulseAlert, onOpen: (String) -> Unit) {
    val accent = severityColor(alert.severity)
    PulseCard(onClick = { onOpen(alert.alertId) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseTag(text = alert.category.code, color = accent)
            Spacer(Modifier.weight(1f))
            Text(
                text = PulseTime.formatClock(alert.epochMillis ?: 0L),
                style = PulseMonoText.chip,
                color = PulseColors.TextMuted,
            )
        }
        Text(alert.title, style = MaterialTheme.typography.titleMedium, color = PulseColors.TextPrimary)
        alert.taskId?.let { id ->
            Text(id, style = PulseMonoText.chip, color = PulseColors.PrimaryCyan)
        }
        Text(alert.message, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextSecondary)

        alert.primaryCommand?.let { command ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "电脑处理指令:",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                Spacer(Modifier.padding(start = PulseSpacing.sm))
                Text(
                    text = command,
                    style = PulseMonoText.chip,
                    color = PulseColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                CopyAction(text = command, label = "复制指令 📋")
            }
        }
    }
}

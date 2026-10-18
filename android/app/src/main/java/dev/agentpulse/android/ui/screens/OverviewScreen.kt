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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.core.PulseTime
import dev.agentpulse.android.data.ConnectionState
import dev.agentpulse.android.data.DataPhase
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.model.ActiveTask
import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.AlertStatus
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.PulseTask
import dev.agentpulse.android.data.model.TaskState
import dev.agentpulse.android.ui.common.DurationText
import dev.agentpulse.android.ui.common.EmptyState
import dev.agentpulse.android.ui.common.ErrorState
import dev.agentpulse.android.ui.common.LoadingState
import dev.agentpulse.android.ui.common.PulseCard
import dev.agentpulse.android.ui.common.PulseStatusDot
import dev.agentpulse.android.ui.common.PulseTag
import dev.agentpulse.android.ui.common.SectionHeader
import dev.agentpulse.android.ui.common.agentStatusColor
import dev.agentpulse.android.ui.common.connectionColor
import dev.agentpulse.android.ui.common.kindLabel
import dev.agentpulse.android.ui.common.taskStateColor
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseSpacing

/**
 * 屏 1：总览大盘（report 4.3 wireframe 1）.
 * Global system status, live metric trio, active tasks, pending decisions and the queued backlog.
 */
@Composable
fun OverviewScreen(
    state: PulseUiState,
    onRefresh: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val overview = state.overview
    when {
        overview == null && state.overviewState.phase == DataPhase.Loading -> LoadingState(message = "正在连接监控服务…")

        overview == null && state.overviewState.phase == DataPhase.Error -> ErrorState(
            message = state.overviewState.error ?: "无法获取总览数据",
            onRetry = onRefresh,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(PulseSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            item(key = "system-status") {
                SystemStatusCard(
                    connection = state.connection.state,
                    activeCount = overview?.metrics?.activeTasksCount ?: 0,
                    queuedCount = overview?.metrics?.queuedTasksCount ?: 0,
                    completedCount = overview?.metrics?.completedTasksCount ?: 0,
                    alertCount = state.pendingAlertCount,
                )
            }

            item(key = "active-header") {
                SectionHeader(title = "正在执行的任务")
            }
            val activeTasks = overview?.activeTasks.orEmpty()
            if (activeTasks.isEmpty()) {
                item(key = "active-empty") {
                    EmptyState("当前没有正在执行的任务", "工作流空闲中，队列任务等待调度")
                }
            } else {
                items(activeTasks, key = { "active-${it.id}" }) { task ->
                    ActiveTaskCard(task = task, onClick = { onOpenTask(task.id) })
                }
            }

            item(key = "decision-header") {
                SectionHeader(title = "待处理决策 (Needs Decision)")
            }
            val decisions = state.alerts.filter { it.category == AlertCategory.DecisionRequired && it.status == AlertStatus.Active }
            if (decisions.isEmpty()) {
                item(key = "decision-empty") {
                    Text(
                        text = "暂无阻断决策，工作流顺畅流转中",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(horizontal = PulseSpacing.xs),
                    )
                }
            } else {
                items(decisions, key = { "decision-${it.alertId}" }) { alert ->
                    DecisionCard(alert = alert)
                }
            }

            item(key = "queue-header") {
                SectionHeader(title = "排队队列 (Queued)")
            }
            val queued = state.tasks.filter { it.state == TaskState.Queued || it.state == TaskState.Blocked || it.state == TaskState.Held }
            if (queued.isEmpty()) {
                item(key = "queue-empty") {
                    Text(
                        text = "队列为空",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(horizontal = PulseSpacing.xs),
                    )
                }
            } else {
                items(queued, key = { "queued-${it.id}" }) { task ->
                    QueuedTaskCard(task = task, onClick = { onOpenTask(task.id) })
                }
            }

            if (state.overviewState.error != null && overview != null) {
                item(key = "soft-error") {
                    Text(
                        text = "上次刷新失败: ${state.overviewState.error}（显示的数据可能已过期）",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.WarningAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemStatusCard(
    connection: ConnectionState,
    activeCount: Int,
    queuedCount: Int,
    completedCount: Int,
    alertCount: Int,
) {
    val color = connectionColor(connection)
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseStatusDot(color = color, animated = connection != ConnectionState.Disconnected, size = 12.dp)
            Spacer(Modifier.padding(start = PulseSpacing.sm))
            Text(
                text = "${connection.labelZh} (${connection.name})",
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCell("在飞任务", activeCount, PulseColors.PrimaryCyan, Modifier.weight(1f))
            MetricCell("排队就绪", queuedCount, PulseColors.TextPrimary, Modifier.weight(1f))
            MetricCell("已完成", completedCount, PulseColors.SuccessGreen, Modifier.weight(1f))
            MetricCell(
                "异常告警",
                alertCount,
                if (alertCount > 0) PulseColors.ErrorRose else PulseColors.SuccessGreen,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = MaterialTheme.typography.displayLarge, color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextSecondary)
    }
}

@Composable
private fun ActiveTaskCard(task: ActiveTask, onClick: () -> Unit) {
    PulseCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseTag(text = kindLabel(task.kind), color = PulseColors.PrimaryCyan)
            Spacer(Modifier.padding(start = PulseSpacing.sm))
            Text(
                text = task.id,
                style = PulseMonoText.chip,
                color = PulseColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            DurationText(sinceEpochMs = PulseTime.parseIso(task.since)?.toEpochMilli())
        }

        val statusColor = agentStatusColor(task.agentStatus)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("状态: ", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
            PulseStatusDot(color = statusColor, animated = task.agentStatus.code == "WORKING", size = 8.dp)
            Spacer(Modifier.padding(start = PulseSpacing.xs))
            Text(task.agentStatus.code, style = PulseMonoText.chip, color = statusColor)
            val meta = buildString {
                if (!task.agent.isNullOrBlank()) append(task.agent)
                if (task.repo.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(task.repo)
                }
            }
            if (meta.isNotBlank()) {
                Text(" ($meta)", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextSecondary)
            }
        }

        if (task.title.isNotBlank()) {
            Text("目标: ${task.title}", style = MaterialTheme.typography.bodyMedium, color = PulseColors.TextPrimary)
        }
        if (!task.currentDoing.isNullOrBlank()) {
            Text("进度: ${task.currentDoing}", style = PulseMonoText.chip, color = PulseColors.TextSecondary)
        }
    }
}

@Composable
private fun DecisionCard(alert: PulseAlert) {
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseTag(text = "NEEDS-DECISION", color = PulseColors.WarningAmber)
            Spacer(Modifier.weight(1f))
            Text(
                text = PulseTime.formatClock(alert.epochMillis ?: 0L),
                style = PulseMonoText.chip,
                color = PulseColors.TextMuted,
            )
        }
        Text(alert.taskId ?: "-", style = PulseMonoText.chip, color = PulseColors.PrimaryCyan)
        Text(alert.message, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextSecondary)
    }
}

@Composable
private fun QueuedTaskCard(task: PulseTask, onClick: () -> Unit) {
    PulseCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseStatusDot(color = taskStateColor(task.state), animated = false, size = 8.dp)
            Spacer(Modifier.padding(start = PulseSpacing.sm))
            Text(
                text = task.id,
                style = PulseMonoText.chip,
                color = PulseColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            PulseTag(text = kindLabel(task.kind), color = PulseColors.TextSecondary)
        }
        val blocked = task.blockedBy ?: task.blockedByIds.firstOrNull()
        if (blocked != null) {
            Text("阻塞源: $blocked", style = MaterialTheme.typography.bodySmall, color = PulseColors.WarningAmber)
        } else if (task.holdReason != null) {
            Text("挂起: ${task.holdReason}", style = MaterialTheme.typography.bodySmall, color = PulseColors.WarningAmber)
        }
    }
}

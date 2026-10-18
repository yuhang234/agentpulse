package dev.agentpulse.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.data.DataPhase
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.model.PulseTask
import dev.agentpulse.android.data.model.TaskState
import dev.agentpulse.android.ui.common.EmptyState
import dev.agentpulse.android.ui.common.ErrorState
import dev.agentpulse.android.ui.common.LoadingState
import dev.agentpulse.android.ui.common.MetaRow
import dev.agentpulse.android.ui.common.PulseCard
import dev.agentpulse.android.ui.common.PulseFilterChip
import dev.agentpulse.android.ui.common.PulseStatusDot
import dev.agentpulse.android.ui.common.PulseTag
import dev.agentpulse.android.ui.common.kindLabel
import dev.agentpulse.android.ui.common.taskStateColor
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseSpacing

private enum class TaskFilter(val label: String) {
    All("全部"),
    InFlight("进行中"),
    Queued("排队中"),
    Done("已完成"),
}

/**
 * 屏 2：任务与 Agent 矩阵（report 4.3 wireframe 2）.
 * Vertical FirstMate task queue with state filter; tapping a card drills into detail
 * (worktree, branch, Herdr pane, terminal output).
 */
@Composable
fun TasksScreen(
    state: PulseUiState,
    onOpenTask: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val tasks = state.tasks
    var filter by remember { mutableStateOf(TaskFilter.All) }

    val filtered = when (filter) {
        TaskFilter.All -> tasks
        TaskFilter.InFlight -> tasks.filter { it.state == TaskState.InFlight }
        TaskFilter.Queued -> tasks.filter { it.state == TaskState.Queued || it.state == TaskState.Blocked || it.state == TaskState.Held }
        TaskFilter.Done -> tasks.filter { it.state == TaskState.Done }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            filterChip(TaskFilter.All, tasks.count { true }, filter) { filter = it }
            filterChip(TaskFilter.InFlight, tasks.count { it.state == TaskState.InFlight }, filter) { filter = it }
            filterChip(
                TaskFilter.Queued,
                tasks.count { it.state == TaskState.Queued || it.state == TaskState.Blocked || it.state == TaskState.Held },
                filter,
            ) { filter = it }
            filterChip(TaskFilter.Done, tasks.count { it.state == TaskState.Done }, filter) { filter = it }
        }

        when {
            tasks.isEmpty() && state.tasksState.phase == DataPhase.Loading -> LoadingState(message = "正在加载任务…")

            tasks.isEmpty() && state.tasksState.phase == DataPhase.Error -> ErrorState(
                message = state.tasksState.error ?: "无法获取任务列表",
                onRetry = onRefresh,
            )

            filtered.isEmpty() -> EmptyState("当前筛选下没有任务", "切换筛选或等待新任务被调度")

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                items(filtered, key = { it.id }) { task ->
                    TaskCard(task = task, onClick = { onOpenTask(task.id) })
                }
            }
        }
    }
}

@Composable
private fun filterChip(
    filter: TaskFilter,
    count: Int,
    current: TaskFilter,
    onSelect: (TaskFilter) -> Unit,
) {
    PulseFilterChip(
        text = "${filter.label}($count)",
        selected = current == filter,
        onClick = { onSelect(filter) },
    )
}

@Composable
private fun TaskCard(task: PulseTask, onClick: () -> Unit) {
    PulseCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseStatusDot(color = taskStateColor(task.state), animated = task.state == TaskState.InFlight, size = 8.dp)
            Spacer(Modifier.padding(start = PulseSpacing.sm))
            Text(
                text = task.id,
                style = PulseMonoText.chip,
                color = PulseColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PulseTag(text = kindLabel(task.kind), color = taskStateColor(task.state))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.state.labelZh, style = MaterialTheme.typography.bodySmall, color = taskStateColor(task.state))
            if (task.harness.isNotBlank()) {
                Text(" · harness: ${task.harness}", style = PulseMonoText.chip, color = PulseColors.TextSecondary)
            }
        }

        MetaRow("仓库", task.repo)
        MetaRow("目录", task.worktree)
        MetaRow("窗格", task.assignedPaneId)
        if (task.branch != null) {
            MetaRow("分支", task.branch)
        }

        val blocked = task.blockedBy ?: task.blockedByIds.firstOrNull()
        if (blocked != null) {
            Text(
                text = "阻塞于: $blocked",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.WarningAmber,
            )
        } else if (task.holdReason != null) {
            Text(
                text = "挂起: ${task.holdReason}",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.WarningAmber,
            )
        }
    }
}

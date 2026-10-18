package dev.agentpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentpulse.android.data.PulseUiState
import dev.agentpulse.android.data.TransportSettings
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.ui.AppViewModel
import dev.agentpulse.android.ui.common.CopyAction
import dev.agentpulse.android.ui.common.ErrorState
import dev.agentpulse.android.ui.common.LoadingState
import dev.agentpulse.android.ui.common.MetaRow
import dev.agentpulse.android.ui.common.PulseTag
import dev.agentpulse.android.ui.common.SectionHeader
import dev.agentpulse.android.ui.common.TerminalBlock
import dev.agentpulse.android.ui.common.severityColor
import dev.agentpulse.android.ui.theme.PulseColors
import dev.agentpulse.android.ui.theme.PulseMonoText
import dev.agentpulse.android.ui.theme.PulseSpacing

// ---------------------------------------------------------------- task detail

/** Full-screen task detail: worktree / branch / pane meta + live ANSI terminal tail. */
@Composable
fun TaskDetailOverlay(
    taskId: String,
    state: PulseUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val task = state.tasks.firstOrNull { it.id == taskId }
    var terminalLines by remember(taskId) { mutableStateOf<List<String>?>(null) }
    var terminalError by remember(taskId) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(taskId, reloadKey) {
        terminalLines = null
        terminalError = null
        viewModel.loadTerminal(taskId, lines = 60, ansi = true)
            .onSuccess { terminalLines = it.lines }
            .onFailure { terminalError = it.message }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = PulseColors.Background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PulseSpacing.xs, vertical = PulseSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = PulseColors.TextPrimary)
                }
                Text(
                    text = taskId,
                    style = PulseMonoText.chip,
                    color = PulseColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                task?.let { PulseTag(text = dev.agentpulse.android.ui.common.kindLabel(it.kind), color = PulseColors.PrimaryCyan) }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PulseSpacing.md),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                if (task == null) {
                    ErrorState(message = "找不到任务 $taskId", onRetry = onBack)
                } else {
                    MetaRow("状态", task.state.labelZh, mono = false)
                    MetaRow("仓库", task.repo)
                    MetaRow("目录", task.worktree)
                    MetaRow("分支", task.branch)
                    MetaRow("Harness", task.harness)
                    MetaRow("模型", task.model)
                    MetaRow("窗格", task.assignedPaneId)
                    if (task.prUrl != null) MetaRow("PR", task.prUrl)
                }

                SectionHeader(title = "终端输出")
                when {
                    terminalError != null -> ErrorState(message = terminalError ?: "终端读取失败", onRetry = { reloadKey++ })
                    terminalLines == null -> LoadingState(message = "正在读取终端输出…")
                    else -> TerminalBlock(lines = terminalLines.orEmpty())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- alert sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSheet(alert: PulseAlert, onDismiss: () -> Unit) {
    val accent = severityColor(alert.severity)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PulseColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseTag(text = alert.category.code, color = accent)
                Spacer(Modifier.weight(1f))
                PulseTag(text = alert.severity.labelZh, color = accent, filled = true)
            }
            Text(alert.title, style = MaterialTheme.typography.headlineMedium, color = PulseColors.TextPrimary)
            alert.taskId?.let { id -> Text(id, style = PulseMonoText.chip, color = PulseColors.PrimaryCyan) }
            Text(alert.message, style = MaterialTheme.typography.bodyMedium, color = PulseColors.TextSecondary)

            if (alert.suggestedActions.isNotEmpty()) {
                SectionHeader(title = "电脑处理指令")
                alert.suggestedActions.forEach { command ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = command,
                            style = PulseMonoText.chip,
                            color = PulseColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        CopyAction(text = command, label = "复制 📋")
                    }
                }
            }
            Spacer(Modifier.padding(bottom = PulseSpacing.lg))
        }
    }
}

// ---------------------------------------------------------------- settings

/** Settings surface: host / port / socket path / api prefix, with save + reset-to-default. */
@Composable
fun SettingsDialog(viewModel: AppViewModel) {
    val transport = viewModel.uiState.collectAsState().value.transport

    var host by remember { mutableStateOf(transport.host) }
    var port by remember { mutableStateOf(transport.port.toString()) }
    var socketPath by remember { mutableStateOf(transport.socketPath) }
    var apiPrefix by remember { mutableStateOf(transport.apiPrefix) }

    AlertDialog(
        onDismissRequest = viewModel::closeSettings,
        title = { Text("连接设置", color = PulseColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                Text(
                    text = "监控服务地址（局域网直连如 192.168.x.x:7890，或本地隧道 127.0.0.1:7890）",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机 host") }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口 port") }, singleLine = true)
                OutlinedTextField(value = socketPath, onValueChange = { socketPath = it }, label = { Text("WebSocket 路径 path") }, singleLine = true)
                OutlinedTextField(value = apiPrefix, onValueChange = { apiPrefix = it }, label = { Text("API 前缀 apiPrefix") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateTransport { current ->
                        current.copy(
                            host = host,
                            port = port.toIntOrNull() ?: current.port,
                            socketPath = socketPath,
                            apiPrefix = apiPrefix,
                        ).sanitized()
                    }
                    viewModel.closeSettings()
                },
            ) { Text("保存", color = PulseColors.PrimaryCyan) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    viewModel.resetTransport()
                    viewModel.closeSettings()
                }) { Text("恢复默认", color = PulseColors.TextSecondary) }
                TextButton(onClick = viewModel::closeSettings) { Text("取消", color = PulseColors.TextSecondary) }
            }
        },
        containerColor = PulseColors.Surface,
    )
}

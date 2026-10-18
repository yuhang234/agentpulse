package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Herdr Agent 运行状态 —— 报告 2.2.B `agent_status` 枚举。 */
@Serializable
enum class AgentStatus {
    @SerialName("working") Working,
    @SerialName("idle") Idle,
    @SerialName("blocked") Blocked,
    @SerialName("done") Done,
    @SerialName("unknown") Unknown;

    val labelZh: String
        get() = when (this) {
            Working -> "工作中"
            Idle -> "空闲"
            Blocked -> "阻塞"
            Done -> "已完成"
            Unknown -> "未知"
        }

    /** 统一大写标识，用于卡片上的 `WORKING` 徽标（保留英文，报告 4.3 屏 1 线框图）。 */
    val code: String get() = name.uppercase()
}

/** Herdr 前台进程 —— 报告 2.2.B `foreground_processes[]` 元素。 */
@Serializable
data class ForegroundProcess(
    val name: String = "",
    val pid: Long = 0L,
    val cwd: String = "",
)

/**
 * TerminalPane 实体（Herdr 窗格状态）—— 报告 2.2.B。
 *
 * 描述一个终端窗格的实时状态与最近输出尾巴，是「任务 ↔ 终端」关联的落点。
 */
@Serializable
data class TerminalPane(
    @SerialName("pane_id") val paneId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String? = null,
    @SerialName("terminal_title") val terminalTitle: String = "",
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val cwd: String = "",
    @SerialName("foreground_cwd") val foregroundCwd: String = "",
    val focused: Boolean = false,
    @SerialName("foreground_processes") val foregroundProcesses: List<ForegroundProcess> = emptyList(),
    @SerialName("recent_output_tail") val recentOutputTail: List<String> = emptyList(),
)

/** 任务终端输出 —— 报告 3.1.4 `GET /api/v1/tasks/{id}/terminal`。 */
@Serializable
data class TerminalOutput(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("pane_id") val paneId: String? = null,
    val format: String = "text",
    @SerialName("line_count") val lineCount: Int = 0,
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
) {
    val isAnsi: Boolean get() = format.equals("ansi", ignoreCase = true)
}

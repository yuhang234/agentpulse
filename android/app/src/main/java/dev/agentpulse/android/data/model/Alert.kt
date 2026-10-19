package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 告警类别 —— 报告 2.2.D `category` 枚举。 */
@Serializable
enum class AlertCategory {
    @SerialName("decision_required") DecisionRequired,
    @SerialName("task_failed") TaskFailed,
    @SerialName("agent_unresponsive") AgentUnresponsive,
    @SerialName("pipeline_blocked") PipelineBlocked,
    @SerialName("service_disconnected") ServiceDisconnected,
    @SerialName("unknown") Unknown;

    val labelZh: String
        get() = when (this) {
            DecisionRequired -> "等待人工决策"
            TaskFailed -> "任务失败"
            AgentUnresponsive -> "Agent 无响应"
            PipelineBlocked -> "流水线阻塞"
            ServiceDisconnected -> "服务断开连接"
            Unknown -> "其他异常"
        }

    /** 异常提醒屏的分组标题使用的大写英文标识（报告 4.3 屏 4 线框图）。 */
    val code: String
        get() = when (this) {
            DecisionRequired -> "NEEDS-DECISION"
            TaskFailed -> "TASK-FAILED"
            AgentUnresponsive -> "AGENT-UNRESPONSIVE"
            PipelineBlocked -> "PIPELINE-BLOCKED"
            ServiceDisconnected -> "SERVICE-DISCONNECTED"
            Unknown -> "UNKNOWN"
        }
}

/** 告警状态 —— 报告 2.2.D `status` 枚举。 */
@Serializable
enum class AlertStatus {
    @SerialName("active") Active,
    @SerialName("acknowledged") Acknowledged,
    @SerialName("resolved") Resolved,
    @SerialName("unknown") Unknown;
}

/**
 * Alert 实体（异常与通知）—— 报告 2.2.D。
 *
 * `severity` 只允许 warning / error / critical，正好对应报告 4.4 的打扰分级：
 * critical 走系统 Heads-up，warning 走静默角标，二者都进异常列表。
 */
@Serializable
data class PulseAlert(
    @SerialName("alert_id") val alertId: String = "",
    @SerialName("task_id") val taskId: String? = null,
    val category: AlertCategory = AlertCategory.Unknown,
    val severity: Severity = Severity.Unknown,
    val tier: String = "",
    val title: String = "",
    val message: String = "",
    val key: String? = null,
    @SerialName("suggested_actions") val suggestedActions: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    val status: AlertStatus = AlertStatus.Active,
) {
    val tierLevel: PulseTier
        get() = if (tier.isNotBlank()) {
            PulseTier.fromWire(tier, severity)
        } else {
            PulseTier.fromWire(null, severity)
        }

    val epochMillis: Long? get() = TimeFormat.parseToEpochMillis(createdAt)

    /** 只读版本：界面上提供「复制指令」按钮，但绝不代执行（报告 4.3 屏 4 核心职能）。 */
    val primaryCommand: String? get() = suggestedActions.firstOrNull { it.isNotBlank() }

    val isResolved: Boolean get() = status == AlertStatus.Resolved
    val needsAttention: Boolean get() = status == AlertStatus.Active
}

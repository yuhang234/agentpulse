package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 时间戳解析 —— 报告 2.2 各实体的 `timestamp` / `created_at` / `updated_at` 均为 ISO-8601 date-time。
 *
 * 服务端可能给出带 `Z` 的 UTC 串、带偏移量的串、或（降级情况下）不带时区的本地串，
 * 三种形态都要能吃下并且不抛异常——监控客户端绝不能因为一个时间戳格式就整屏失败。
 */
object TimeFormat {
    /** @return epoch 毫秒；无法解析时返回 null。 */
    fun parseToEpochMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching {
                java.time.LocalDateTime.parse(text)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli()
            }
            .getOrNull()
    }
}

/** 任务状态 —— 报告 2.2.A `state` 枚举。 */
@Serializable
enum class TaskState {
    @SerialName("queued") Queued,
    @SerialName("in_flight") InFlight,
    @SerialName("done") Done,
    @SerialName("held") Held,
    @SerialName("failed") Failed,
    @SerialName("blocked") Blocked,

    /** 降级兜底：服务端新增了未知状态时不让反序列化崩掉。 */
    @SerialName("unknown") Unknown;

    /** 简体中文界面文案。 */
    val labelZh: String
        get() = when (this) {
            Queued -> "排队中"
            InFlight -> "进行中"
            Done -> "已完成"
            Held -> "已挂起"
            Failed -> "已失败"
            Blocked -> "已阻塞"
            Unknown -> "未知"
        }
}

/** 任务类别 —— 报告 2.2.A `kind` 枚举。 */
@Serializable
enum class TaskKind {
    @SerialName("scout") Scout,
    @SerialName("ship") Ship,
    @SerialName("secondmate") Secondmate,
    @SerialName("custom") Custom,
    @SerialName("unknown") Unknown;

    val labelZh: String
        get() = when (this) {
            Scout -> "侦察"
            Ship -> "交付"
            Secondmate -> "二级主管"
            Custom -> "自定义"
            Unknown -> "未知"
        }
}

/** 挂起类型 —— 报告 2.2.A `hold_kind` 枚举（可为 null）。 */
@Serializable
enum class HoldKind {
    @SerialName("captain") Captain,
    @SerialName("blocker") Blocker,
    @SerialName("unknown") Unknown;
}

/**
 * Task 实体（统一任务）—— 报告 2.2.A。
 *
 * `required` 字段给出非空类型，可选字段全部显式可空并带默认值，
 * 保证旧版服务端缺少某字段时客户端仍能渲染。
 */
@Serializable
data class PulseTask(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val state: TaskState = TaskState.Unknown,
    val kind: TaskKind = TaskKind.Unknown,
    val repo: String = "",
    val worktree: String? = null,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    val harness: String = "",
    val model: String = "",
    @SerialName("blocked_by") val blockedBy: String? = null,
    @SerialName("blocked_by_ids") val blockedByIds: List<String> = emptyList(),
    @SerialName("hold_reason") val holdReason: String? = null,
    @SerialName("hold_kind") val holdKind: HoldKind? = null,
    @SerialName("assigned_pane_id") val assignedPaneId: String? = null,
    @SerialName("active_agent_session_id") val activeAgentSessionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val isActive: Boolean get() = state == TaskState.InFlight
    val isQueued: Boolean get() = state == TaskState.Queued
}

/**
 * 总览页在飞任务摘要 —— 报告 3.1.2 `GET /api/v1/overview` 的 `active_tasks[]` 元素。
 *
 * 与完整 [PulseTask] 不同：总览只附带监控所需的运行态切片（Agent 身份、Herdr 状态、当前动作）。
 */
@Serializable
data class ActiveTask(
    val id: String = "",
    val title: String = "",
    val kind: TaskKind = TaskKind.Unknown,
    val repo: String = "",
    val state: TaskState = TaskState.Unknown,
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val since: String? = null,
    @SerialName("current_doing") val currentDoing: String? = null,
)

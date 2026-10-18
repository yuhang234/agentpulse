package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 事件来源 —— 报告 2.2.C `source` 枚举。 */
@Serializable
enum class EventSource {
    @SerialName("firstmate") Firstmate,
    @SerialName("herdr") Herdr,
    @SerialName("pi") Pi,
    @SerialName("service") Service,
    @SerialName("unknown") Unknown;

    /** 时间轴上的英文来源标签（报告 4.3 屏 3 线框图使用 `[Herdr]` / `[Pi/Tool]` 形式）。 */
    val code: String
        get() = when (this) {
            Firstmate -> "FirstMate"
            Herdr -> "Herdr"
            Pi -> "Pi"
            Service -> "Service"
            Unknown -> "Unknown"
        }
}

/** 事件种类 —— 报告 2.2.C `kind` 枚举。 */
@Serializable
enum class EventKind {
    @SerialName("task_state_changed") TaskStateChanged,
    @SerialName("agent_status_changed") AgentStatusChanged,
    @SerialName("tool_call") ToolCall,
    @SerialName("tool_result") ToolResult,
    @SerialName("thinking_chunk") ThinkingChunk,
    @SerialName("pane_output") PaneOutput,
    @SerialName("decision_needed") DecisionNeeded,
    @SerialName("task_completed") TaskCompleted,
    @SerialName("task_failed") TaskFailed,
    @SerialName("unknown") Unknown;

    /** 时间轴标题样式：`[Pi/Tool]` 这类复合标签。 */
    val code: String
        get() = when (this) {
            TaskStateChanged -> "状态"
            AgentStatusChanged -> "Agent"
            ToolCall -> "Tool"
            ToolResult -> "Result"
            ThinkingChunk -> "Thinking"
            PaneOutput -> "Pane"
            DecisionNeeded -> "Decision"
            TaskCompleted -> "Done"
            TaskFailed -> "Failed"
            Unknown -> "Event"
        }
}

/** 事件严重度阶梯 —— 报告 2.2.C / 2.4.1。 */
@Serializable
enum class Severity {
    @SerialName("debug") Debug,
    @SerialName("info") Info,
    @SerialName("warning") Warning,
    @SerialName("error") Error,
    @SerialName("critical") Critical,
    @SerialName("unknown") Unknown;

    val labelZh: String
        get() = when (this) {
            Debug -> "调试"
            Info -> "信息"
            Warning -> "警告"
            Error -> "错误"
            Critical -> "严重"
            Unknown -> "未知"
        }

    companion object {
        /**
         * 宽松解析：服务端大小写漂移或新增等级时一律降级为 [Unknown]，
         * 事件流不因单条脏数据中断（报告 1.4 平滑降级矩阵）。
         */
        fun fromWire(raw: String?): Severity =
            when (raw?.trim()?.lowercase()) {
                "debug" -> Debug
                "info" -> Info
                "warning" -> Warning
                "error" -> Error
                "critical" -> Critical
                else -> Unknown
            }
    }
}

/**
 * Event 实体（统一事件流）—— 报告 2.2.C。
 *
 * [seq] 是严格递增的全局事件序号，也是移动端增量同步与去重的唯一游标。
 *
 * 注意：`event_push` 封包（报告 3.2.B.1）把 `seq` 放在信封层而非 `data` 内，
 * 因此本地默认值为 0，由 [WsEnvelope] 在解析时补回。
 */
@Serializable
data class PulseEvent(
    @SerialName("event_id") val eventId: String = "",
    val seq: Long = 0L,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("pane_id") val paneId: String? = null,
    val source: EventSource = EventSource.Unknown,
    val kind: EventKind = EventKind.Unknown,
    val severity: String = "info",
    val timestamp: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    val severityLevel: Severity get() = Severity.fromWire(severity)

    val epochMillis: Long? get() = TimeFormat.parseToEpochMillis(timestamp)

    /** 去重键：优先使用服务端 `event_id`，缺失时退回 `seq`（报告 2.4.2 幂等去重）。 */
    val dedupeKey: String
        get() = eventId.ifBlank { "seq:$seq" }

    /** 从多态载荷中安全读取字符串字段。 */
    fun payloadString(key: String): String? {
        val prim = payload[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (prim is kotlinx.serialization.json.JsonNull) return null
        return prim.content
    }
}

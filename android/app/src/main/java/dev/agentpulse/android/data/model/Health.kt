package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 单个采集器健康度 —— 报告 3.1.1 `collectors` 中的子对象。 */
@Serializable
data class CollectorStatus(
    val status: String = "unknown",
    @SerialName("last_sync_epoch") val lastSyncEpoch: Long? = null,
    val socket: String? = null,
    val protocol: Int? = null,
    @SerialName("active_sessions_tracked") val activeSessionsTracked: Int? = null,
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true) || status.equals("healthy", ignoreCase = true)
}

/**
 * 系统健康状态 —— 报告 3.1.1 `GET /api/v1/health`。
 *
 * 三个采集器是固定命名的上游数据源；未知采集器由 `ignoreUnknownKeys` 容忍，
 * 图上遗漏的采集器则渲染为「未上报」而不是崩溃。
 */
@Serializable
data class HealthSnapshot(
    val status: String = "unknown",
    val version: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0L,
    val timestamp: String? = null,
    val collectors: Map<String, CollectorStatus> = emptyMap(),
    @SerialName("clients_connected") val clientsConnected: Int = 0,
) {
    val isHealthy: Boolean get() = status.equals("healthy", ignoreCase = true)

    fun collector(name: String): CollectorStatus? = collectors[name]

    /** 已知采集器的稳定展示顺序：FirstMate → Herdr → Pi。 */
    val orderedCollectors: List<Pair<String, CollectorStatus>>
        get() = KNOWN_COLLECTORS.mapNotNull { name -> collectors[name]?.let { name to it } }

    companion object {
        val KNOWN_COLLECTORS = listOf("firstmate", "herdr", "pi")
    }
}

/** 总览页顶部指标条 —— 报告 3.1.2 `metrics`。 */
@Serializable
data class OverviewMetrics(
    @SerialName("active_tasks_count") val activeTasksCount: Int = 0,
    @SerialName("queued_tasks_count") val queuedTasksCount: Int = 0,
    @SerialName("completed_tasks_count") val completedTasksCount: Int = 0,
    @SerialName("active_alerts_count") val activeAlertsCount: Int = 0,
    @SerialName("working_agents_count") val workingAgentsCount: Int = 0,
)

/**
 * 全局监控总览 —— 报告 3.1.2 `GET /api/v1/overview`。
 *
 * 手机总览页首屏的唯一数据源：毫秒级返回，聚合指标 + 在飞任务 + 待决策 + 严重告警。
 */
@Serializable
data class Overview(
    @SerialName("generated_at") val generatedAt: String? = null,
    val metrics: OverviewMetrics = OverviewMetrics(),
    @SerialName("active_tasks") val activeTasks: List<ActiveTask> = emptyList(),
    @SerialName("pending_decisions") val pendingDecisions: List<PulseAlert> = emptyList(),
    @SerialName("critical_alerts") val criticalAlerts: List<PulseAlert> = emptyList(),
)

/** 任务列表 —— 报告 3.1.3 `GET /api/v1/tasks`。 */
@Serializable
data class TasksResponse(
    val total: Int = 0,
    val tasks: List<PulseTask> = emptyList(),
)

/** 增量事件流 —— 报告 3.1.5 `GET /api/v1/events`。 */
@Serializable
data class EventsResponse(
    @SerialName("since_seq") val sinceSeq: Long = 0L,
    @SerialName("max_seq") val maxSeq: Long = 0L,
    @SerialName("has_more") val hasMore: Boolean = false,
    val events: List<PulseEvent> = emptyList(),
)

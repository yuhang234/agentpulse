package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.HealthSnapshot
import dev.agentpulse.android.data.model.Overview
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.PulseEvent
import dev.agentpulse.android.data.model.PulseTask

/**
 * 单个数据面的加载相位。
 *
 * 四个值与报告要求的状态一一对应，并且与连接三态正交：
 * - [Loading]：首次请求进行中，屏上显示骨架进度。
 * - [Ready]：已拿到数据。
 * - [Empty]：请求成功但集合为空（例如「暂无排队任务」）。
 * - [Error]：请求失败且没有可回退的旧数据；屏上给出重试入口。
 *
 * 「陈旧（stale）」与「断开」由 [ConnectionSnapshot.state] 统一表达，
 * 因为它们是链路状态而非某个数据面的状态。
 */
enum class DataPhase { Loading, Ready, Empty, Error }

data class DataState(
    val phase: DataPhase = DataPhase.Loading,
    val error: String? = null,
    val updatedAtEpochMs: Long? = null,
) {
    val isReady: Boolean get() = phase == DataPhase.Ready
    val hasData: Boolean get() = phase == DataPhase.Ready || phase == DataPhase.Empty

    /** 出错时保留旧数据，仅提示失败，避免整屏闪烁。 */
    fun failed(message: String): DataState =
        if (hasData) copy(error = message) else DataState(DataPhase.Error, message, updatedAtEpochMs)

    fun loaded(isEmpty: Boolean, at: Long): DataState =
        DataState(if (isEmpty) DataPhase.Empty else DataPhase.Ready, null, at)
}

/**
 * 全应用单一状态源。
 *
 * 四大主屏与所有详情面都从这里派生，不存在第二条数据路径，
 * 因此连接状态、告警角标、时间轴游标在屏与屏之间永远一致。
 */
data class PulseUiState(
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val transport: TransportSettings = TransportSettings(),
    val health: HealthSnapshot? = null,
    val healthState: DataState = DataState(),
    val overview: Overview? = null,
    val overviewState: DataState = DataState(),
    val tasks: List<PulseTask> = emptyList(),
    val tasksState: DataState = DataState(),
    val events: List<PulseEvent> = emptyList(),
    val eventsState: DataState = DataState(),
    val alerts: List<PulseAlert> = emptyList(),
    /** 最近一次成功同步（任何数据面）的时刻。 */
    val lastSyncEpochMs: Long? = null,
) {
    val activeTasks: List<PulseTask> get() = tasks.filter { it.state == dev.agentpulse.android.data.model.TaskState.InFlight }
    val queuedTasks: List<PulseTask> get() = tasks.filter { it.state == dev.agentpulse.android.data.model.TaskState.Queued }

    /** 「异常」Tab 的角标计数（报告 4.3 屏 4 / 4.4.2）。 */
    val pendingAlertCount: Int get() = alerts.count { it.needsAttention }

    val criticalAlerts: List<PulseAlert> get() = alerts.filter { it.needsAttention && it.tierLevel == dev.agentpulse.android.data.model.PulseTier.Critical }
    val warningAlerts: List<PulseAlert> get() = alerts.filter { it.needsAttention && it.tierLevel == dev.agentpulse.android.data.model.PulseTier.Warning }

    val criticalAlertCount: Int get() = criticalAlerts.size
    val warningAlertCount: Int get() = warningAlerts.size

    /** 当前最大事件 seq，即下次增量同步游标。 */
    val maxSeq: Long get() = events.maxOfOrNull { it.seq } ?: 0L
}

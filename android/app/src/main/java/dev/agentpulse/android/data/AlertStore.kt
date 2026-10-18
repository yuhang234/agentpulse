package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.AlertStatus
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.Severity

/**
 * 告警仓库 —— 报告 2.2.D Alert 实体 + 4.3 屏 4 的分组呈现底座。
 *
 * 三个来源统一入池：
 * 1. 服务端总览接口的 `pending_decisions` / `critical_alerts`；
 * 2. 从事件流派生的告警（`decision_needed` / `task_failed` / `agent_status_changed -> blocked`）；
 * 3. 客户端本地合成的连接降级告警（`service_disconnected`）。
 *
 * 去重键为 `alert_id`：客户端合成的告警使用稳定前缀，重连补发不会产生重复条目。
 * 已读状态独立存储，因此服务端刷新不会把「清空已读」结果冲掉（v1 只读，不向上游写状态）。
 */
class AlertStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val byId = LinkedHashMap<String, PulseAlert>()
    private var acknowledgedIds: MutableSet<String> = mutableSetOf()

    fun replaceAcknowledged(ids: Set<String>) {
        acknowledgedIds = ids.toMutableSet()
    }

    fun acknowledgedSnapshot(): Set<String> = acknowledgedIds.toSet()

    /** 时间倒序 + 活跃优先的展示顺序。 */
    fun snapshot(): List<PulseAlert> = byId.values
        .map { applyAck(it) }
        .sortedWith(
            compareByDescending<PulseAlert> { it.needsAttention }
                .thenByDescending { it.epochMillis ?: Long.MIN_VALUE },
        )

    fun find(id: String): PulseAlert? = byId[id]?.let { applyAck(it) }

    /**
     * 归并一批告警。
     * @return 首次入库的告警（用于通知派发；重复项不会重复打扰）。
     */
    fun merge(incoming: Collection<PulseAlert>): List<PulseAlert> {
        if (incoming.isEmpty()) return emptyList()
        val added = ArrayList<PulseAlert>()
        for (alert in incoming) {
            val id = alert.alertId.ifBlank { fallbackId(alert) }
            val normalized = alert.copy(alertId = id)
            val existing = byId[id]
            when {
                existing == null -> {
                    byId[id] = normalized
                    added.add(normalized)
                }
                // 服务端是权威：状态/严重度以服务端新值为准，但不重复通知。
                existing != normalized -> byId[id] = normalized
            }
        }
        trim()
        return added
    }

    /** 本地合成告警（连接降级等）。 */
    fun raise(alert: PulseAlert): Boolean = merge(listOf(alert)).isNotEmpty()

    /** 标记已读（v1 只读：只改本地视图状态）。 */
    fun acknowledge(id: String) {
        acknowledgedIds.add(id)
    }

    /** 「清空已读」：把所有当前可见告警标记为已读。 */
    fun acknowledgeAll() {
        acknowledgedIds.addAll(byId.keys)
    }

    /**
     * 按条件批量置为已解决，并改写 message 记录恢复时刻。
     * @return 实际发生变化的告警。
     */
    fun resolve(
        match: (PulseAlert) -> Boolean,
        message: (PulseAlert) -> String = { it.message },
    ): List<PulseAlert> {
        val changed = ArrayList<PulseAlert>()
        for ((id, alert) in byId.entries.toList()) {
            if (alert.status == AlertStatus.Resolved) continue
            if (!match(alert)) continue
            val updated = alert.copy(status = AlertStatus.Resolved, message = message(alert))
            byId[id] = updated
            changed.add(updated)
        }
        return changed
    }

    /** 当前仍然活跃的连接类告警（用于避免重复开新条目）。 */
    fun activeDisconnectAlert(): PulseAlert? = byId.values.firstOrNull {
        it.category == AlertCategory.ServiceDisconnected && it.status != AlertStatus.Resolved
    }

    fun clear() {
        byId.clear()
    }

    val size: Int get() = byId.size

    private fun applyAck(alert: PulseAlert): PulseAlert =
        if (acknowledgedIds.contains(alert.alertId) && alert.status == AlertStatus.Active) {
            alert.copy(status = AlertStatus.Acknowledged)
        } else {
            alert
        }

    private fun trim() {
        while (byId.size > capacity) {
            val oldestResolved = byId.values
                .filter { it.status == AlertStatus.Resolved }
                .minByOrNull { it.epochMillis ?: Long.MIN_VALUE }
            val victim = oldestResolved ?: byId.values.first()
            byId.remove(victim.alertId)
        }
    }

    private fun fallbackId(alert: PulseAlert): String =
        "local:${alert.category}:${alert.taskId ?: "global"}:${alert.title}:${alert.createdAt ?: ""}"

    companion object {
        const val DEFAULT_CAPACITY = 200

        /** 本地连接降级告警的稳定 id：同一段断线只占一条。 */
        const val LOCAL_DISCONNECT_ID = "local:service_disconnected"

        /** 本地连接降级告警（短时 Stale，静默记录）。 */
        fun localStaleAlert(at: String): PulseAlert = PulseAlert(
            alertId = LOCAL_DISCONNECT_ID,
            category = AlertCategory.ServiceDisconnected,
            severity = Severity.Warning,
            title = "连接不稳定",
            message = "与 AgentPulse 监控服务的心跳中断，正在保持同步…",
            createdAt = at,
            status = AlertStatus.Active,
        )

        /** 本地连接断开告警（CRITICAL，报告 4.4 中断级）。 */
        fun localDisconnectedAlert(at: String, silenceSeconds: Long): PulseAlert = PulseAlert(
            alertId = LOCAL_DISCONNECT_ID,
            category = AlertCategory.ServiceDisconnected,
            severity = Severity.Critical,
            title = "服务断开连接",
            message = "已静默 ${silenceSeconds}s，后台正在按指数退避重连。",
            suggestedActions = listOf(
                "确认手机与电脑在同一 Wi-Fi，且设置页填写的电脑局域网 IP:端口 正确",
                "adb reverse tcp:7890 tcp:7890",
                "ssh -L 7890:127.0.0.1:7890 <user>@<host>",
            ),
            createdAt = at,
            status = AlertStatus.Active,
        )
    }
}

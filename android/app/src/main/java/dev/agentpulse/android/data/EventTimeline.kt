package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.PulseEvent

/**
 * 事件时间轴 —— 按 `seq` 归并的事件流（报告 2.2.C / 2.4.2）。
 *
 * 这是实时动态屏、增量同步与通知派发的共同底座，必须同时满足：
 *
 * 1. **严格按 `seq` 排序**：REST 补偿拉取与 WebSocket 实时推送会交叉到达，
 *    `replay` 补发的事件也可能插入到已在屏事件之前。
 * 2. **幂等去重**：同一事件可能同时经由 `/api/v1/events` 与 `event_push` 抵达；
 *    以 `event_id`（缺失时退回 `seq`）为去重键，重复事件不产生第二条时间轴记录，
 *    也不重复触发通知。
 * 3. **容量上限**：移动端只保留最近 [capacity] 条，超出后从最旧一端裁剪
 *    （报告 2.4.3 内存环形缓冲区在客户端的对应实现）。
 *
 * 本类不做线程同步：调用方（[PulseRepository]）在其单订阅者协程内串行访问。
 */
class EventTimeline(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private data class Row(val key: Long, val event: PulseEvent)

    private val rows = ArrayList<Row>()
    private val dedupeKeys = HashSet<String>()
    private val usedSeqs = HashSet<Long>()

    /** 已接收事件里的最大 `seq`，即下次增量同步的游标。 */
    var maxSeq: Long = 0L
        private set

    /** 因为超出 [capacity] 而被裁掉的事件数量（诊断用）。 */
    var evictedCount: Int = 0
        private set

    /** 无 `seq` 事件（本地合成）的排序代理键。 */
    private var surrogateKey: Long = 0L

    val size: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty()

    /** 当前时间轴快照，按 `seq` 升序。 */
    fun snapshot(): List<PulseEvent> = rows.map { it.event }

    /**
     * 归并一批事件。
     *
     * @return 实际新增的事件（按时间轴顺序），已存在的事件不会出现在返回值里。
     *   通知派发只应依赖这个返回值，避免重连补发造成的重复打扰。
     */
    fun merge(incoming: Collection<PulseEvent>): List<PulseEvent> {
        if (incoming.isEmpty()) return emptyList()
        val added = ArrayList<PulseEvent>(incoming.size)
        for (event in incoming) {
            if (!isNew(event)) continue
            val key = orderKeyFor(event)
            rows.add(indexForKey(key), Row(key, event))
            dedupeKeys.add(event.dedupeKey)
            if (event.seq > 0L) usedSeqs.add(event.seq)
            if (event.seq > maxSeq) maxSeq = event.seq
            added.add(event)
        }
        if (added.isNotEmpty()) trim()
        return added
    }

    /** 便捷入口：单条事件。 */
    fun merge(event: PulseEvent): Boolean = merge(listOf(event)).isNotEmpty()

    /** 是否已包含该事件。 */
    fun contains(event: PulseEvent): Boolean = !isNew(event)

    fun clear() {
        rows.clear()
        dedupeKeys.clear()
        usedSeqs.clear()
        maxSeq = 0L
        evictedCount = 0
        surrogateKey = 0L
    }

    private fun isNew(event: PulseEvent): Boolean {
        val key = event.dedupeKey
        if (dedupeKeys.contains(key)) return false
        // 不同 event_id 却复用同一 seq，同样视为重复：seq 是全局单调游标，不允许出现两条。
        if (event.seq > 0L && usedSeqs.contains(event.seq)) return false
        return true
    }

    private fun orderKeyFor(event: PulseEvent): Long {
        if (event.seq > 0L) return event.seq
        // 本地合成事件（例如连接降级告警）没有服务端 seq：排在当前已知最新事件之后，
        // 并保持合成顺序稳定。
        surrogateKey = maxOf(surrogateKey + 1L, maxSeq + 1L)
        return surrogateKey
    }

    private fun indexForKey(key: Long): Int {
        var low = 0
        var high = rows.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (rows[mid].key <= key) low = mid + 1 else high = mid
        }
        return low
    }

    private fun trim() {
        val overflow = rows.size - capacity
        if (overflow <= 0) return
        repeat(overflow) { rows.removeAt(0) }
        evictedCount += overflow
    }

    companion object {
        /** 报告 2.4.3：每个活动任务在内存中保留最新 1000 条；移动端整机共享一份，取同量级上限。 */
        const val DEFAULT_CAPACITY: Int = 1_000
    }
}

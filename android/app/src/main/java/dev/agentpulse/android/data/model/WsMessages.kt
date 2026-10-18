package dev.agentpulse.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WebSocket 消息封包 —— 报告 3.2.B。
 *
 * 服务端 → 客户端：
 * - `event_push`：单条实时事件，`seq` 在信封层，`data` 为 Event 实体（不含 seq）。
 * - `replay`：握手后的批量补发；报告未固定其内层形状，因此按 [data] 宽容解析
 *   （既接受 `{ "events": [...] }`，也接受裸数组）。
 * - `live_ready`：补发结束，转入纯实时推送。
 * - `pong`：心跳应答。
 *
 * 客户端 → 服务端：
 * - `hello`：连接建立时声明 `client_id` 与本地游标（与 URL 查询参数双保险）。
 * - `ping`：每 15 秒心跳。
 */
@Serializable
data class WsEnvelope(
    val type: String = "",
    /** 信封层递增序号；`event_push` 的权威 seq 来源。 */
    val seq: Long = 0L,
    val topic: String? = null,
    val timestamp: Long? = null,
    val data: JsonElement? = null,
) {
    /**
     * 从 `data` 还原 Event 实体，并把信封层 [seq] 补进事件
     * （报告示例中 `data` 内部没有 `seq` 字段）。
     */
    fun toEvent(): PulseEvent? {
        val obj = data as? JsonObject ?: return null
        val decoded = runCatching {
            AgentPulseJson.instance.decodeFromJsonElement(PulseEvent.serializer(), obj)
        }.getOrNull() ?: return null
        return if (decoded.seq == 0L && seq != 0L) decoded.copy(seq = seq) else decoded
    }

    /** `replay` 封包内层的事件列表，兼容数组与 `{events:[...]}` 两种形状。 */
    fun toReplayEvents(): List<PulseEvent> {
        val element = data ?: return emptyList()
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["events"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            runCatching {
                AgentPulseJson.instance.decodeFromJsonElement(PulseEvent.serializer(), obj)
            }.getOrNull()
        }
    }

    /** 心跳往返时间戳（服务端原样回传客户端的 `timestamp`）。 */
    val heartbeatTimestamp: Long? get() = timestamp ?: (data as? JsonObject)?.get("timestamp")?.jsonPrimitive?.content?.toLongOrNull()
}

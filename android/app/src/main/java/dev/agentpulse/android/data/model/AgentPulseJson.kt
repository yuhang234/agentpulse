package dev.agentpulse.android.data.model

import kotlinx.serialization.json.Json

/**
 * 全应用唯一的 JSON 配置。
 *
 * - `ignoreUnknownKeys`：服务端新增字段不炸掉老客户端。
 * - `coerceInputValues`：非法枚举 / null 落到属性默认值，实现报告 1.4 的平滑降级。
 * - `explicitNulls = false`：缺失与显式 null 等价处理，简化可空字段语义。
 */
object AgentPulseJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
        encodeDefaults = true
    }
}

package dev.agentpulse.android.data

/**
 * 三态连接模型 —— 报告 3.2.C。
 *
 * 1. [Healthy]（绿色）：正常收发消息或心跳延迟 < 3 秒。
 * 2. [Stale]（琥珀色）：超过 15 秒未收到任何数据包；界面不阻断操作，
 *    只在顶栏提示「连接不稳定，正在保持同步…」，界面打上轻微半透明降级标识。
 * 3. [Disconnected]（红色）：超过 30 秒无心跳，或 TCP Socket 直接断开；
 *    顶部弹出不可忽略的红色横幅，并在后台按指数退避重连。
 */
enum class ConnectionState {
    Healthy,
    Stale,
    Disconnected;

    /** 顶栏 / 横幅使用的简体中文说明。 */
    val labelZh: String
        get() = when (this) {
            Healthy -> "工作流运转中"
            Stale -> "连接不稳定"
            Disconnected -> "已断开连接"
        }

    val isBlocking: Boolean get() = this == Disconnected
    val isDegraded: Boolean get() = this != Healthy
}

/**
 * 连接状态判定器 —— 纯函数，便于 JVM 单测覆盖边界值。
 *
 * 阈值取自报告 3.2.C，构造参数化是为了让测试显式钉住边界。
 */
class ConnectionStateMachine(
    /** 心跳延迟健康阈值（报告 3.2.C.1：「心跳延迟 < 3 秒」）。 */
    val freshLatencyMs: Long = 3_000L,
    /** 静默多久进入 Stale（报告 3.2.C.2）。 */
    val staleAfterMs: Long = 15_000L,
    /** 静默多久进入 Disconnected（报告 3.2.C.3）。 */
    val disconnectedAfterMs: Long = 30_000L,
) {
    /**
     * @param silenceMs 距最近一次收到任何数据包（事件或 pong）的毫秒数。
     * @param socketOpen TCP/WS 通道是否仍然建立。报告 3.2.C.3 明确「TCP Socket 直接断开」即刻判定为 Disconnected。
     */
    fun evaluate(silenceMs: Long, socketOpen: Boolean): ConnectionState = when {
        !socketOpen -> ConnectionState.Disconnected
        silenceMs >= disconnectedAfterMs -> ConnectionState.Disconnected
        silenceMs > staleAfterMs -> ConnectionState.Stale
        else -> ConnectionState.Healthy
    }

    /**
     * 心跳延迟是否仍然健康。
     *
     * 3 秒阈值针对的是「一次 ping/pong 往返」：在 3 秒内完成的往返，
     * 即便当前处于 Stale 静默窗口，也说明链路质量本身没有问题。
     */
    fun isLatencyHealthy(latencyMs: Long?): Boolean =
        latencyMs == null || latencyMs <= freshLatencyMs
}

/**
 * 指数退避重连算法 —— 报告 3.2.C.3：`1s -> 2s -> 4s -> 8s -> 15s`（封顶 15 秒）。
 *
 * 单线程使用（由重连协程私有持有）。
 */
class ReconnectBackoff(
    private val scheduleMs: List<Long> = DEFAULT_SCHEDULE_MS,
) {
    var attempt: Int = 0
        private set

    /** 取下一次重连等待时长，并推进档位。 */
    fun nextDelayMillis(): Long {
        val index = attempt.coerceAtMost(scheduleMs.lastIndex)
        val delay = scheduleMs[index]
        attempt += 1
        return delay
    }

    /** 连接成功后清零，下一次断线重新从 1 秒开始。 */
    fun reset() {
        attempt = 0
    }

    companion object {
        /** 报告 3.2.C.3 的完整退避阶梯。 */
        val DEFAULT_SCHEDULE_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    }
}

/**
 * 连接快照：顶栏与横幅渲染所需的全部连接态信息。
 */
data class ConnectionSnapshot(
    val state: ConnectionState = ConnectionState.Disconnected,
    /** 距最近一次收到数据包的毫秒数（用于「静默 N 秒」提示）。 */
    val silenceMs: Long = 0L,
    /** 最近一次心跳往返延迟；null 表示尚未测量。 */
    val latencyMs: Long? = null,
    /** 当前重连尝试次数，0 表示未在重连。 */
    val reconnectAttempt: Int = 0,
    /** 本次连接建立时刻（epoch 毫秒）。 */
    val connectedSinceEpochMs: Long? = null,
    val lastPacketEpochMs: Long? = null,
) {
    val latencyHealthy: Boolean get() = silenceMs <= 3_000L
}

package dev.agentpulse.android.data.remote

import dev.agentpulse.android.data.TransportSettings
import dev.agentpulse.android.data.model.EventsResponse
import dev.agentpulse.android.data.model.HealthSnapshot
import dev.agentpulse.android.data.model.Overview
import dev.agentpulse.android.data.model.TaskState
import dev.agentpulse.android.data.model.TasksResponse
import dev.agentpulse.android.data.model.TerminalOutput
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.withTimeout

/**
 * 监控服务 REST 客户端 —— 报告 3.1 五个接口的完整实现。
 *
 * 全部为只读 GET；v1 不存在任何写接口或远程执行入口。
 */
class AgentPulseApi(
    private val client: HttpClient,
    /** 每次调用即时求值，使设置页改动立即对新请求生效。 */
    private val settingsProvider: () -> TransportSettings,
    /** 单次请求的协作式超时，避免隧道半死时把界面卡在 Loading。 */
    private val requestTimeoutMs: Long = 10_000L,
) {
    private val settings: TransportSettings get() = settingsProvider()

    /** 1. `GET /api/v1/health` */
    suspend fun health(): HealthSnapshot = get("/health")

    /** 2. `GET /api/v1/overview` —— 总览页首屏数据。 */
    suspend fun overview(): Overview = get("/overview")

    /** 3. `GET /api/v1/tasks?state=&repo=` */
    suspend fun tasks(
        states: Collection<TaskState> = emptyList(),
        repo: String? = null,
    ): TasksResponse {
        val stateParam = states
            .filter { it != TaskState.Unknown }
            .joinToString(",") { it.wireName }
            .takeIf { it.isNotBlank() }
        return get("/tasks") {
            stateParam?.let { parameter("state", it) }
            repo?.takeIf { it.isNotBlank() }?.let { parameter("repo", it) }
        }
    }

    /** 4. `GET /api/v1/tasks/{id}/terminal?lines=&format=` */
    suspend fun terminal(
        taskId: String,
        lines: Int = DEFAULT_TERMINAL_LINES,
        ansi: Boolean = true,
    ): TerminalOutput = get("/tasks/$taskId/terminal") {
        parameter("lines", lines.coerceIn(1, MAX_TERMINAL_LINES))
        parameter("format", if (ansi) "ansi" else "text")
    }

    /** 5. `GET /api/v1/events?since_seq=&limit=&task_id=` —— 断线重连后的增量补偿。 */
    suspend fun events(
        sinceSeq: Long,
        limit: Int = DEFAULT_EVENT_LIMIT,
        taskId: String? = null,
    ): EventsResponse = get("/events") {
        parameter("since_seq", sinceSeq)
        parameter("limit", limit.coerceIn(1, MAX_EVENT_LIMIT))
        taskId?.takeIf { it.isNotBlank() }?.let { parameter("task_id", it) }
    }

    private suspend inline fun <reified T> get(
        path: String,
        noinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val url = settings.apiBaseUrl + path
        return withTimeout(requestTimeoutMs) {
            client.get(url) { block() }.body()
        }
    }

    private val TaskState.wireName: String
        get() = when (this) {
            TaskState.Queued -> "queued"
            TaskState.InFlight -> "in_flight"
            TaskState.Done -> "done"
            TaskState.Held -> "held"
            TaskState.Failed -> "failed"
            TaskState.Blocked -> "blocked"
            TaskState.Unknown -> "unknown"
        }

    companion object {
        const val DEFAULT_TERMINAL_LINES = 50
        const val MAX_TERMINAL_LINES = 200
        const val DEFAULT_EVENT_LIMIT = 100
        const val MAX_EVENT_LIMIT = 500
    }
}

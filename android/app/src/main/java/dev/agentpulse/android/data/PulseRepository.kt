package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.AgentPulseJson
import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.AlertStatus
import dev.agentpulse.android.data.model.EventKind
import dev.agentpulse.android.data.model.HealthSnapshot
import dev.agentpulse.android.data.model.Overview
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.PulseEvent
import dev.agentpulse.android.data.model.PulseTask
import dev.agentpulse.android.data.model.Severity
import dev.agentpulse.android.data.model.TerminalOutput
import dev.agentpulse.android.data.model.WsEnvelope
import dev.agentpulse.android.data.remote.AgentPulseApi
import dev.agentpulse.android.data.remote.AgentPulseSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * 监控数据仓库 —— 报告第二章（统一数据模型）与第三章（服务契约）在客户端的唯一落地。
 *
 * 职责边界：
 * - **只读**：全部出站流量只有 WebSocket 握手与心跳，没有任何写接口或远程执行路径。
 * - **三态连接模型**：由 [ConnectionStateMachine] 判定，[tickerLoop] 每秒复算一次。
 * - **增量同步**：`since_seq` 游标来自 [EventTimeline.maxSeq]；每次重连都随握手带上，
 *   并在连接建立后额外做一次 `/api/v1/events` 补偿拉取，双保险。
 * - **事件归并**：REST 补偿与 WS 推送都进入同一个 [EventTimeline]，按 `seq` 排序去重。
 * - **通知派发**：通过 [incomingEvents] / [raisedAlerts] 两个冷启动无关的 SharedFlow
 *   交给 Android 层，仓库本身不依赖任何 Android API，可直接 JVM 单测。
 */
class PulseRepository(
    private val api: AgentPulseApi,
    private val socket: AgentPulseSocket,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stateMachine: ConnectionStateMachine = ConnectionStateMachine(),
    private val backoffFactory: () -> ReconnectBackoff = { ReconnectBackoff() },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val socketWatchdogIntervalMs: Long = SOCKET_WATCHDOG_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private val timeline = EventTimeline()
    private val alertStore = AlertStore()
    private val outbound = Channel<String>(Channel.BUFFERED)

    private val jobs = mutableListOf<Job>()
    private var socketLoopJob: Job? = null

    // ---- 内部状态（一律在 mutex 内读写） ----
    private var startedAt = 0L
    private var lastPacketAt = 0L
    private var lastSocketFrameAt = 0L
    private var socketOpen = false
    private var everConnected = false
    private var connectedSince: Long? = null
    private var latencyMs: Long? = null
    private var reconnectAttempt = 0
    private var lastSyncAt: Long? = null
    private var currentState = ConnectionState.Stale
    private var pendingPingAt: Long? = null
    private var eventsDirty = true
    private var alertsDirty = true
    private var cachedEvents: List<PulseEvent> = emptyList()
    private var cachedAlerts: List<PulseAlert> = emptyList()

    private var health: HealthSnapshot? = null
    private var healthState = DataState()
    private var overview: Overview? = null
    private var overviewState = DataState()
    private var tasks: List<PulseTask> = emptyList()
    private var tasksState = DataState()

    private val _uiState = MutableStateFlow(PulseUiState())
    val uiState: StateFlow<PulseUiState> = _uiState.asStateFlow()

    /** 新入库事件（已去重）。通知层只应依赖它，重连补发不会重复打扰。 */
    private val _incomingEvents = MutableSharedFlow<List<PulseEvent>>(extraBufferCapacity = 256)
    val incomingEvents: SharedFlow<List<PulseEvent>> = _incomingEvents.asSharedFlow()

    /** 新入库告警。 */
    private val _raisedAlerts = MutableSharedFlow<List<PulseAlert>>(extraBufferCapacity = 64)
    val raisedAlerts: SharedFlow<List<PulseAlert>> = _raisedAlerts.asSharedFlow()

    fun start() {
        if (jobs.isNotEmpty()) return
        startedAt = clock()
        lastPacketAt = startedAt
        lastSocketFrameAt = startedAt
        alertStore.replaceAcknowledged(settingsStore.acknowledgedAlertIds())

        jobs += scope.launch { publishInitial() }
        socketLoopJob = scope.launch { socketLoop() }
        jobs += socketLoopJob!!
        jobs += scope.launch { heartbeatLoop() }
        jobs += scope.launch { pollLoop() }
        jobs += scope.launch { tickerLoop() }
        jobs += scope.launch {
            settingsStore.settings.collect { transport ->
                mutex.withLock { buildSnapshotLocked(transport) }.let { _uiState.value = it }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        outbound.cancel()
    }

    // ------------------------------------------------------------------ 对外操作

    /** 手动刷新所有 REST 数据面（下拉刷新 / 错误重试共用入口）。 */
    suspend fun refresh() {
        refreshHealth()
        refreshOverview()
        refreshTasks()
    }

    /**
     * 接入参数变更后强制重建 WebSocket 连接（设置页保存 / 恢复默认时调用）。
     * 复用 [socketLoop] 的既有生命周期：取消旧连接后立即以新参数重连。
     */
    fun reconnect() {
        if (socketLoopJob == null) return
        socketLoopJob?.cancel()
        socketLoopJob = scope.launch { socketLoop() }
        scope.launch { refresh() }
    }

    /** 更新接入参数并立即重建连接（设置页保存）。 */
    fun updateTransport(transform: (TransportSettings) -> TransportSettings) {
        settingsStore.update(transform)
        reconnect()
    }

    /** 恢复默认接入参数并重建连接（设置页「恢复默认」）。 */
    fun resetTransport() {
        settingsStore.resetToDefaults()
        reconnect()
    }

    /** 读取某任务的终端输出（报告 3.1.4），供任务详情页按需调用。 */
    suspend fun loadTerminal(taskId: String, lines: Int, ansi: Boolean): Result<TerminalOutput> =
        runCatching { api.terminal(taskId, lines, ansi) }

    fun acknowledgeAlert(alertId: String) {
        alertStore.acknowledge(alertId)
        settingsStore.persistAcknowledged(alertStore.acknowledgedSnapshot())
        scope.launch { publish() }
    }

    /** 「清空已读」——本地视图状态，v1 不向上游写回。 */
    fun acknowledgeAllAlerts() {
        alertStore.acknowledgeAll()
        settingsStore.persistAcknowledged(alertStore.acknowledgedSnapshot())
        scope.launch { publish() }
    }

    /** 主动触发一条本地告警（例如测试通知）。 */
    fun raiseAlert(alert: PulseAlert) {
        scope.launch {
            val raised = locked { alertStore.merge(listOf(alert)) }
            if (raised.isNotEmpty()) {
                _raisedAlerts.tryEmit(raised)
            }
        }
    }

    // ------------------------------------------------------------------ 数据面刷新

    private suspend fun refreshHealth() {
        runCatching { api.health() }
            .onSuccess { snapshot ->
                locked { health = snapshot; healthState = healthState.loaded(false, clock()) }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                locked { healthState = healthState.failed(describe(error)) }
            }
    }

    private suspend fun refreshOverview() {
        runCatching { api.overview() }
            .onSuccess { snapshot ->
                val now = clock()
                locked {
                    overview = snapshot
                    lastSyncAt = now
                    markPacketLocked(now)
                    overviewState = overviewState.loaded(
                        isEmpty = snapshot.activeTasks.isEmpty() &&
                            snapshot.pendingDecisions.isEmpty() &&
                            snapshot.criticalAlerts.isEmpty() &&
                            snapshot.metrics.activeTasksCount == 0 &&
                            snapshot.metrics.queuedTasksCount == 0,
                        at = now,
                    )
                    ingestServerAlertsLocked(snapshot.pendingDecisions + snapshot.criticalAlerts, now)
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                locked { overviewState = overviewState.failed(describe(error)) }
            }
    }

    private suspend fun refreshTasks() {
        runCatching { api.tasks() }
            .onSuccess { response ->
                val now = clock()
                locked {
                    tasks = response.tasks
                    lastSyncAt = now
                    markPacketLocked(now)
                    tasksState = tasksState.loaded(response.tasks.isEmpty(), now)
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                locked { tasksState = tasksState.failed(describe(error)) }
            }
    }

    /** 重连后的 `since_seq` 补偿拉取（报告 3.1.5）。 */
    private suspend fun catchUpEvents() {
        val since = mutex.withLock { timeline.maxSeq }
        runCatching { api.events(sinceSeq = since) }
            .onSuccess { response ->
                val now = clock()
                val added = locked {
                    markPacketLocked(now)
                    ingestEventsLocked(response.events, now)
                }
                dispatch(added, now)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                // 补偿失败不致命：WS 的 replay 仍是主通道。
            }
    }

    // ------------------------------------------------------------------ 循环

    private suspend fun publishInitial() {
        locked { buildSnapshotLocked(settingsStore.current()) }.let { _uiState.value = it }
    }

    private suspend fun pollLoop() {
        refresh()
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            refresh()
        }
    }

    /** 每秒复算连接三态，并在跨档位时做一次状态迁移副作用。 */
    private suspend fun tickerLoop() {
        while (currentCoroutineContext().isActive) {
            delay(tickIntervalMs)
            locked { evaluateConnectionLocked() }
        }
    }

    /** 每 15 秒发送一次应用层心跳（报告 3.2.B.2）。 */
    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            delay(heartbeatIntervalMs)
            val now = clock()
            val open = mutex.withLock { socketOpen.also { if (it) pendingPingAt = now } }
            if (!open) continue
            outbound.send("""{"type":"ping","timestamp":${now / 1000}}""")
        }
    }

    /**
     * WebSocket 驱动循环：一次迭代 = 一个连接生命周期 + 一次退避等待。
     *
     * 单独看守协程负责掐掉「TCP 假装还活着」的僵尸连接：报告 3.2.C.3 要求
     * 30 秒无心跳即判定断开，这里把它同时落成一次真实的重连动作。
     */
    private suspend fun socketLoop() {
        val backoff = backoffFactory()
        while (currentCoroutineContext().isActive) {
            val url = mutex.withLock { resumeUrlLocked() }
            var connectedThisAttempt = false
            try {
                coroutineScope {
                    val session = launch {
                        socket.run(
                            url = url,
                            outbound = outbound,
                            onOpened = {
                                connectedThisAttempt = true
                                locked { onSocketOpenedLocked() }
                                // 握手后立刻做一次增量补偿，避免依赖服务端 replay 实现。
                                scope.launch { catchUpEvents() }
                            },
                            onText = { handleFrame(it) },
                        )
                    }
                    val watchdog = launch {
                        while (isActive) {
                            delay(socketWatchdogIntervalMs)
                            val silent = mutex.withLock {
                                if (socketOpen && clock() - lastSocketFrameAt > SOCKET_SILENCE_LIMIT_MS) {
                                    socketOpen = false
                                    true
                                } else {
                                    false
                                }
                            }
                            if (silent) {
                                session.cancel()
                                break
                            }
                        }
                    }
                    session.join()
                    watchdog.cancel()
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (error: Throwable) {
                // 连接失败是常态：交给退避阶梯，不向上抛。
            } finally {
                locked { onSocketClosedLocked() }
            }

            if (connectedThisAttempt) backoff.reset()
            val waitMs = backoff.nextDelayMillis()
            locked { reconnectAttempt = if (socketOpen) 0 else backoff.attempt }
            delay(waitMs)
        }
    }

    // ------------------------------------------------------------------ 帧处理

    private suspend fun handleFrame(raw: String) {
        val now = clock()
        val envelope = runCatching {
            AgentPulseJson.instance.decodeFromString(WsEnvelope.serializer(), raw)
        }.getOrNull()
        if (envelope == null) {
            locked { markSocketFrameLocked(now) }
            return
        }

        when (envelope.type.lowercase()) {
            "event_push" -> {
                val event = envelope.toEvent()
                val added = locked {
                    markSocketFrameLocked(now)
                    if (event == null) emptyList() else ingestEventsLocked(listOf(event), now)
                }
                dispatch(added, now)
            }

            "replay" -> {
                val added = locked {
                    markSocketFrameLocked(now)
                    ingestEventsLocked(envelope.toReplayEvents(), now)
                }
                dispatch(added, now)
            }

            "live_ready" -> locked {
                markSocketFrameLocked(now)
                lastSyncAt = now
            }

            "pong" -> locked {
                markSocketFrameLocked(now)
                pendingPingAt?.let { latencyMs = (now - it).coerceAtLeast(0L) }
                pendingPingAt = null
            }

            // 服务端主动 ping：按同一协议回 pong（报告 3.2.B.2 的对称实现）。
            "ping" -> {
                locked { markSocketFrameLocked(now) }
                outbound.send("""{"type":"pong","timestamp":${envelope.timestamp ?: (now / 1000)}}""")
            }

            else -> locked { markSocketFrameLocked(now) }
        }
    }

    /** 把新事件送进通知层（在锁外调用，避免持有互斥量时挂起）。 */
    private suspend fun dispatch(added: List<PulseEvent>, now: Long) {
        if (added.isEmpty()) return
        _incomingEvents.tryEmit(added)
        val synthesized = added.mapNotNull { alertFromEvent(it, now) }
        if (synthesized.isEmpty()) return
        val raised = locked { alertStore.merge(synthesized) }
        if (raised.isNotEmpty()) _raisedAlerts.tryEmit(raised)
        locked { /* 触发一次快照重建，让告警角标立即变化 */ }
    }

    // ------------------------------------------------------------------ 锁内实现

    private suspend fun <T> locked(block: () -> T): T {
        val result: T
        val snapshot = mutex.withLock {
            result = block()
            buildSnapshotLocked(settingsStore.current())
        }
        _uiState.value = snapshot
        return result
    }

    private suspend fun publish() = locked { }

    private fun markPacketLocked(now: Long) {
        lastPacketAt = now
        evaluateConnectionLocked()
    }

    private fun markSocketFrameLocked(now: Long) {
        lastSocketFrameAt = now
        markPacketLocked(now)
    }

    private fun onSocketOpenedLocked() {
        val now = clock()
        socketOpen = true
        everConnected = true
        connectedSince = now
        lastPacketAt = now
        lastSocketFrameAt = now
        reconnectAttempt = 0
        latencyMs = null
        pendingPingAt = null
        // 握手声明：与 URL 查询参数双保险（报告 3.2.A Hello / Resume）。
        outbound.trySend(
            buildString {
                append("""{"type":"hello","client_id":"${settingsStore.current().clientId}"""")
                append(""","since_seq":${timeline.maxSeq}""")
                append(""","resumed":${timeline.maxSeq > 0L}}""")
            },
        )
        evaluateConnectionLocked()
    }

    private fun onSocketClosedLocked() {
        socketOpen = false
        connectedSince = null
        pendingPingAt = null
        // 丢弃上一次连接残留的出站帧。
        while (outbound.tryReceive().isSuccess) Unit
        evaluateConnectionLocked()
    }

    private fun resumeUrlLocked(): String {
        val settings = settingsStore.current()
        val clientId = java.net.URLEncoder.encode(settings.clientId, Charsets.UTF_8.name())
        val since = timeline.maxSeq
        return "${settings.socketUrl}?client_id=$clientId&since_seq=$since"
    }

    /**
     * 连接状态迁移。冷启动阶段（尚未成功连接过、且距启动不足 30 秒）按 Stale 处理：
     * 此时不存在「断开的连接」，红色阻断横幅会误导使用者；超过 30 秒仍未连上，
     * 才落入报告 3.2.C.3 的 Disconnected 语义。
     */
    private fun evaluateConnectionLocked() {
        val now = clock()
        val silence = now - lastPacketAt
        val next = if (!everConnected && now - startedAt < stateMachine.disconnectedAfterMs) {
            ConnectionState.Stale
        } else {
            stateMachine.evaluate(silence, socketOpen)
        }
        if (next == currentState) return
        val previous = currentState
        currentState = next

        val isInitialConnect = !everConnected
        when (next) {
            ConnectionState.Disconnected -> {
                val alert = AlertStore.localDisconnectedAlert(nowIso(now), silence / 1000)
                val raised = alertStore.merge(listOf(alert))
                // 同一段断线只产生一次打扰：alert_id 稳定，merge 去重后 raised 为空。
                if (raised.isNotEmpty()) _raisedAlerts.tryEmit(raised)
            }

            ConnectionState.Stale -> {
                if (!isInitialConnect && previous == ConnectionState.Healthy) {
                    val alert = AlertStore.localStaleAlert(nowIso(now))
                    // Stale 只更新告警内容，不重复打扰（报告 4.4.2 静默级）。
                    alertStore.merge(listOf(alert))
                }
            }

            ConnectionState.Healthy -> {
                val resolvedAt = nowIso(now)
                alertStore.resolve(
                    match = { it.category == AlertCategory.ServiceDisconnected && it.status != AlertStatus.Resolved },
                    message = { "${it.message} 状态：已恢复重连（发生于 ${localTime(now)}）" },
                )
                reconnectAttempt = 0
            }
        }
    }

    private fun ingestServerAlertsLocked(serverAlerts: List<PulseAlert>, now: Long) {
        if (serverAlerts.isEmpty()) return
        alertStore.merge(serverAlerts)
        // 服务端是权威：同一 (类别, 任务) 的事件派生告警让位，避免屏上出现两张同义卡片。
        alertStore.resolve(
            match = { derived ->
                derived.alertId.startsWith(EVENT_ALERT_PREFIX) &&
                    serverAlerts.any { it.category == derived.category && it.taskId == derived.taskId }
            },
            message = { "已由服务端统一告警接管（$now）" },
        )
    }

    private fun ingestEventsLocked(events: List<PulseEvent>, now: Long): List<PulseEvent> {
        val added = timeline.merge(events)
        if (added.isNotEmpty()) eventsDirty = true
        if (timeline.maxSeq > 0L) lastSyncAt = now
        return added
    }

    /** 从事件派生告警（报告 2.2.D 的 category 映射）。 */
    private fun alertFromEvent(event: PulseEvent, now: Long): PulseAlert? {
        val taskLabel = event.taskId ?: "全局"
        return when (event.kind) {
            EventKind.DecisionNeeded -> PulseAlert(
                alertId = "$EVENT_ALERT_PREFIX${event.dedupeKey}",
                taskId = event.taskId,
                category = AlertCategory.DecisionRequired,
                severity = Severity.Critical,
                tier = if (event.tier.isNotBlank()) event.tier else "critical",
                title = "等待人工决策",
                message = event.payloadString("summary")
                    ?: event.payloadString("question")
                    ?: event.payloadString("message")
                    ?: "任务 $taskLabel 正在等待人工决策。",
                key = event.payloadString("key"),
                suggestedActions = buildList {
                    event.payloadString("command")?.let { add(it) }
                    event.payloadString("key")?.let {
                        add("bin/fm-send.sh $taskLabel '选择方案1'")
                    }
                },
                createdAt = event.timestamp ?: nowIso(now),
                status = AlertStatus.Active,
            )

            EventKind.TaskFailed -> PulseAlert(
                alertId = "$EVENT_ALERT_PREFIX${event.dedupeKey}",
                taskId = event.taskId,
                category = AlertCategory.TaskFailed,
                severity = Severity.Critical,
                tier = if (event.tier.isNotBlank()) event.tier else "critical",
                title = "任务失败",
                message = event.payloadString("reason")
                    ?: event.payloadString("message")
                    ?: "任务 $taskLabel 执行失败。",
                suggestedActions = listOfNotNull(
                    event.payloadString("command"),
                ),
                createdAt = event.timestamp ?: nowIso(now),
                status = AlertStatus.Active,
            )

            EventKind.AgentStatusChanged -> {
                val current = event.payloadString("current_status")
                if (!current.equals("blocked", ignoreCase = true)) {
                    null
                } else {
                    PulseAlert(
                        alertId = "$EVENT_ALERT_PREFIX${event.dedupeKey}",
                        taskId = event.taskId,
                        category = AlertCategory.AgentUnresponsive,
                        severity = Severity.Warning,
                        tier = if (event.tier.isNotBlank()) event.tier else "warning",
                        title = "Agent 进入阻塞",
                        message = "窗格 ${event.paneId ?: event.payloadString("pane_id") ?: "未知"} 状态流转为 BLOCKED。",
                        createdAt = event.timestamp ?: nowIso(now),
                        status = AlertStatus.Active,
                    )
                }
            }

            else -> null
        }
    }

    private fun buildSnapshotLocked(transport: TransportSettings): PulseUiState {
        if (eventsDirty) {
            cachedEvents = timeline.snapshot()
            eventsDirty = false
        }
        if (alertsDirty) {
            cachedAlerts = alertStore.snapshot()
            alertsDirty = false
        }
        val now = clock()
        return PulseUiState(
            connection = ConnectionSnapshot(
                state = currentState,
                silenceMs = if (lastPacketAt == 0L) 0L else now - lastPacketAt,
                latencyMs = latencyMs,
                reconnectAttempt = reconnectAttempt,
                connectedSinceEpochMs = connectedSince,
                lastPacketEpochMs = lastPacketAt.takeIf { it > 0L },
            ),
            transport = transport,
            health = health,
            healthState = healthState,
            overview = overview,
            overviewState = overviewState,
            tasks = tasks,
            tasksState = tasksState,
            events = cachedEvents,
            eventsState = when {
                cachedEvents.isNotEmpty() -> DataState(DataPhase.Ready, null, lastSyncAt ?: now)
                currentState == ConnectionState.Disconnected -> DataState(DataPhase.Error, "与监控服务断开连接", null)
                else -> DataState(DataPhase.Loading, null, null)
            },
            alerts = cachedAlerts,
            lastSyncEpochMs = lastSyncAt,
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun describe(error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
        return when (error) {
            is java.net.ConnectException -> "无法连接监控服务（$detail）"
            is java.net.UnknownHostException -> "无法解析主机（$detail）"
            is java.net.SocketTimeoutException -> "请求超时（$detail）"
            else -> detail
        }
    }

    private fun nowIso(now: Long): String = Instant.ofEpochMilli(now).toString()

    private fun localTime(now: Long): String = java.time.Instant.ofEpochMilli(now)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    companion object {
        /** REST 兜底轮询周期：比 Stale 阈值（15s）更密，保证 WS 静默时数据仍然新鲜。 */
        const val POLL_INTERVAL_MS = 10_000L

        /** 连接三态复算周期。 */
        const val TICK_INTERVAL_MS = 1_000L

        /** 应用层心跳周期（报告 3.2.B.2：15 秒）。 */
        const val HEARTBEAT_INTERVAL_MS = 15_000L

        /** 僵尸连接看守周期。 */
        const val SOCKET_WATCHDOG_INTERVAL_MS = 5_000L

        /** WS 静默上限：超过即认为连接已死并触发重连（报告 3.2.C.3 的 30 秒）。 */
        const val SOCKET_SILENCE_LIMIT_MS = 30_000L

        const val EVENT_ALERT_PREFIX = "evt:"
    }
}

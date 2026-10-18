package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.AgentPulseJson
import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.AlertStatus
import dev.agentpulse.android.data.model.CollectorStatus
import dev.agentpulse.android.data.model.EventsResponse
import dev.agentpulse.android.data.model.HealthSnapshot
import dev.agentpulse.android.data.model.Overview
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.Severity
import dev.agentpulse.android.data.model.TasksResponse
import dev.agentpulse.android.data.model.TerminalOutput
import dev.agentpulse.android.data.model.TimeFormat
import dev.agentpulse.android.data.model.WsEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO deserialization tests against the JSON examples of design report chapter 3.
 * Ensures the kotlinx.serialization mapping stays true to the wire contract.
 */
class DtoDeserializationTest {

    private val json = AgentPulseJson.instance

    @Test
    fun `health snapshot decodes collectors and uptime`() {
        val raw = """
            {
              "status": "healthy",
              "version": "1.0.0",
              "uptime_seconds": 3840,
              "timestamp": "2026-10-18T08:50:00Z",
              "collectors": {
                "firstmate": { "status": "ok", "last_sync_epoch": 1792313390 },
                "herdr": { "status": "ok", "socket": "/home/Yuhang/.config/herdr/herdr.sock", "protocol": 22 },
                "pi": { "status": "ok", "active_sessions_tracked": 1 }
              },
              "clients_connected": 2
            }
        """.trimIndent()

        val health = json.decodeFromString(HealthSnapshot.serializer(), raw)

        assertTrue(health.isHealthy)
        assertEquals("1.0.0", health.version)
        assertEquals(3840L, health.uptimeSeconds)
        assertEquals(2, health.clientsConnected)
        assertEquals(3, health.collectors.size)
        assertEquals("ok", health.collector("firstmate")?.status)
        assertEquals(22, health.collector("herdr")?.protocol)
        assertEquals(1, health.collector("pi")?.activeSessionsTracked)
        assertEquals(
            listOf("firstmate", "herdr", "pi"),
            health.orderedCollectors.map { it.first },
        )
    }

    @Test
    fun `overview decodes metrics and active task`() {
        val raw = """
            {
              "generated_at": "2026-10-18T08:50:00Z",
              "metrics": {
                "active_tasks_count": 1,
                "queued_tasks_count": 2,
                "completed_tasks_count": 0,
                "active_alerts_count": 0,
                "working_agents_count": 1
              },
              "active_tasks": [
                {
                  "id": "agentpulse-design",
                  "title": "AgentPulse: data-source inventory",
                  "kind": "scout",
                  "repo": "agentpulse",
                  "state": "in_flight",
                  "agent": "agy",
                  "agent_status": "working",
                  "since": "2026-10-18",
                  "current_doing": "harness busy (herdr-native)"
                }
              ],
              "pending_decisions": [],
              "critical_alerts": []
            }
        """.trimIndent()

        val overview = json.decodeFromString(Overview.serializer(), raw)

        assertEquals(1, overview.metrics.activeTasksCount)
        assertEquals(2, overview.metrics.queuedTasksCount)
        assertEquals(0, overview.metrics.completedTasksCount)
        assertEquals(1, overview.metrics.workingAgentsCount)
        assertEquals(1, overview.activeTasks.size)
        assertEquals("agentpulse-design", overview.activeTasks[0].id)
        assertEquals("agy", overview.activeTasks[0].agent)
        assertEquals("WORKING", overview.activeTasks[0].agentStatus.code)
    }

    @Test
    fun `tasks response decodes queued and blocked fields`() {
        val raw = """
            {
              "total": 2,
              "tasks": [
                {
                  "id": "agentpulse-design",
                  "title": "design",
                  "state": "in_flight",
                  "kind": "scout",
                  "repo": "agentpulse",
                  "worktree": "/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse",
                  "branch": null,
                  "assigned_pane_id": "w1D:p2",
                  "harness": "agy",
                  "model": "claude-opus-4-6-thinking",
                  "blocked_by": null,
                  "created_at": "2026-10-18T08:38:00Z",
                  "updated_at": "2026-10-18T08:43:00Z"
                },
                {
                  "id": "agentpulse-service",
                  "title": "service",
                  "state": "queued",
                  "kind": "ship",
                  "repo": "agentpulse",
                  "blocked_by": "agentpulse-design",
                  "blocked_by_ids": ["agentpulse-design"],
                  "created_at": "2026-10-18T08:38:00Z",
                  "updated_at": "2026-10-18T08:38:00Z"
                }
              ]
            }
        """.trimIndent()

        val tasks = json.decodeFromString(TasksResponse.serializer(), raw)

        assertEquals(2, tasks.total)
        assertEquals(2, tasks.tasks.size)
        val queued = tasks.tasks.first { it.id == "agentpulse-service" }
        assertEquals("agentpulse-design", queued.blockedBy)
        assertEquals(listOf("agentpulse-design"), queued.blockedByIds)
        assertTrue(queued.isQueued)
        assertTrue(tasks.tasks.first { it.id == "agentpulse-design" }.isActive)
    }

    @Test
    fun `terminal output decodes ansi lines`() {
        val raw = """
            {
              "task_id": "agentpulse-design",
              "pane_id": "w1D:p2",
              "format": "ansi",
              "line_count": 2,
              "lines": ["\u001b[38;5;244m───\u001b[0m", ">"],
              "truncated": false
            }
        """.trimIndent()

        val terminal = json.decodeFromString(TerminalOutput.serializer(), raw)

        assertEquals("agentpulse-design", terminal.taskId)
        assertEquals("w1D:p2", terminal.paneId)
        assertTrue(terminal.isAnsi)
        assertEquals(2, terminal.lineCount)
        assertEquals(2, terminal.lines.size)
    }

    @Test
    fun `events response decodes events with payload`() {
        val raw = """
            {
              "since_seq": 1040,
              "max_seq": 1042,
              "has_more": false,
              "events": [
                {
                  "event_id": "evt_01a14f",
                  "seq": 1042,
                  "task_id": "agentpulse-design",
                  "source": "firstmate",
                  "kind": "task_state_changed",
                  "severity": "info",
                  "timestamp": "2026-10-18T08:43:25Z",
                  "payload": { "state": "working", "note": "starting inventory" }
                }
              ]
            }
        """.trimIndent()

        val events = json.decodeFromString(EventsResponse.serializer(), raw)

        assertEquals(1040L, events.sinceSeq)
        assertEquals(1042L, events.maxSeq)
        assertEquals(1, events.events.size)
        val event = events.events[0]
        assertEquals(1042L, event.seq)
        assertEquals("working", event.payloadString("state"))
        assertEquals("starting inventory", event.payloadString("note"))
    }

    @Test
    fun `alert decodes category severity and actions`() {
        val raw = """
            {
              "alert_id": "al_01",
              "task_id": "agentpulse-design",
              "category": "decision_required",
              "severity": "critical",
              "title": "等待人工决策",
              "message": "是否引入动态颜色库？",
              "key": "color-lib",
              "suggested_actions": ["bin/fm-send.sh agentpulse-design '选择方案1'"],
              "created_at": "2026-10-18T08:40:00Z",
              "status": "active"
            }
        """.trimIndent()

        val alert = json.decodeFromString(PulseAlert.serializer(), raw)

        assertEquals(AlertCategory.DecisionRequired, alert.category)
        assertEquals(Severity.Critical, alert.severity)
        assertEquals(AlertStatus.Active, alert.status)
        assertEquals("color-lib", alert.key)
        assertEquals("bin/fm-send.sh agentpulse-design '选择方案1'", alert.primaryCommand)
        assertNotNull(alert.epochMillis)
    }

    @Test
    fun `websocket event_push envelope maps seq into event`() {
        val raw = """
            {
              "type": "event_push",
              "seq": 1043,
              "topic": "events",
              "data": {
                "event_id": "evt_01a150",
                "task_id": "agentpulse-design",
                "source": "pi",
                "kind": "tool_call",
                "severity": "info",
                "timestamp": "2026-10-18T08:50:10Z",
                "payload": { "toolName": "bash", "toolCallId": "call_99", "command": "git status" }
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelope.serializer(), raw)
        val event = envelope.toEvent()

        assertNotNull(event)
        assertEquals(1043L, event!!.seq)
        assertEquals("evt_01a150", event.eventId)
        assertEquals("git status", event.payloadString("command"))
    }

    @Test
    fun `time format tolerates utc offset and local date-time`() {
        assertNotNull(TimeFormat.parseToEpochMillis("2026-10-18T08:50:10Z"))
        assertNotNull(TimeFormat.parseToEpochMillis("2026-10-18T08:50:10.693Z"))
        assertNotNull(TimeFormat.parseToEpochMillis("2026-10-18T08:50:10+08:00"))
        assertNotNull(TimeFormat.parseToEpochMillis("2026-10-18T08:50:10"))
        assertEquals(null, TimeFormat.parseToEpochMillis(null))
        assertEquals(null, TimeFormat.parseToEpochMillis(""))
        assertEquals(null, TimeFormat.parseToEpochMillis("not-a-time"))
    }

    @Test
    fun `collector status ok detection is case insensitive`() {
        assertTrue(CollectorStatus(status = "ok").isOk)
        assertTrue(CollectorStatus(status = "HEALTHY").isOk)
        assertTrue(!CollectorStatus(status = "degraded").isOk)
    }
}

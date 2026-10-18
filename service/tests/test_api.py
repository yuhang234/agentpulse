"""REST + WebSocket contract (report ch. 3) and the live-push bus fan-out."""

import asyncio

from fastapi.testclient import TestClient

from agentpulse_service.api import create_app
from agentpulse_service.bus import EventBus
from agentpulse_service.models import Event
from agentpulse_service.monitor import build_tasks


def _fleet():
    return {
        "generated": "2026-10-18T09:00:00Z",
        "backlog": {
            "records": [
                {
                    "id": "t1",
                    "title": "Task one",
                    "state": "in_flight",
                    "kind": "ship",
                    "repo": "agentpulse",
                    "blocked_by": None,
                    "blocked_by_ids": [],
                    "since": "2026-10-18",
                },
                {
                    "id": "t2",
                    "title": "Task two",
                    "state": "queued",
                    "kind": "scout",
                    "repo": "agentpulse",
                    "blocked_by": "t1",
                    "blocked_by_ids": ["t1"],
                    "since": "2026-10-18",
                },
            ]
        },
        "tasks": [
            {
                "id": "t1",
                "harness": "opencode",
                "branch": "fm/t1",
                "paths": {"worktree": {"path": "/w/t1"}},
                "current_state": {"state": "working", "detail": "busy", "source": "pane"},
            }
        ],
    }


def _meta():
    return {
        "t1": {"herdr_pane_id": "w1:p1", "harness": "opencode", "model": "default"},
        "t2": {"herdr_pane_id": "", "harness": "pi", "model": "deepseek-v4.1"},
    }


def _populate(monitor, store, world):
    tasks = build_tasks(_fleet(), _meta(), now="2026-10-18T09:00:00Z")
    world.set_tasks(tasks)
    world.set_health("firstmate", "ok", last_sync_epoch=123)
    world.set_health("herdr", "ok", socket="/tmp/herdr.sock", protocol=22)
    world.set_health("pi", "ok", active_sessions_tracked=0)
    for i in range(3):
        store.append(
            Event(
                event_id=f"e{i}",
                seq=0,
                task_id="t1",
                pane_id="w1:p1",
                source="herdr",
                kind="agent_status_changed",
                severity="info",
                timestamp="2026-10-18T09:00:00Z",
                payload={"current_status": "working"},
            )
        )


def _client(make_monitor, settings):
    monitor, store, bus, world = make_monitor()
    _populate(monitor, store, world)
    app = create_app(monitor)
    return TestClient(app), monitor, store, bus, world


def test_health_shape(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        body = client.get("/api/v1/health").json()
    assert body["status"] == "healthy"
    assert body["version"] == "1.0.0"
    assert set(body["collectors"]) == {"firstmate", "herdr", "pi"}
    assert body["collectors"]["herdr"]["protocol"] == 22
    assert "uptime_seconds" in body
    assert "clients_connected" in body


def test_overview_shape(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        body = client.get("/api/v1/overview").json()
    metrics = body["metrics"]
    assert metrics == {
        "active_tasks_count": 1,
        "queued_tasks_count": 1,
        "completed_tasks_count": 0,
        "active_alerts_count": 0,
        "working_agents_count": 0,
    }
    assert len(body["active_tasks"]) == 1
    row = body["active_tasks"][0]
    assert row["id"] == "t1"
    assert set(row) == {
        "id", "title", "kind", "repo", "state", "agent", "agent_status",
        "since", "current_doing",
    }


def test_tasks_shape_and_filters(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        body = client.get("/api/v1/tasks").json()
        assert body["total"] == 2
        t1 = [t for t in body["tasks"] if t["id"] == "t1"][0]
        assert t1["state"] == "in_flight"
        assert t1["assigned_pane_id"] == "w1:p1"
        assert t1["active_agent_session_id"] is None  # non-Pi harness

        filtered = client.get("/api/v1/tasks", params={"state": "queued"}).json()
        assert filtered["total"] == 1
        assert filtered["tasks"][0]["id"] == "t2"

        by_repo = client.get("/api/v1/tasks", params={"repo": "agentpulse"}).json()
        assert by_repo["total"] == 2


def test_events_cursor(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        body = client.get("/api/v1/events", params={"since_seq": 1}).json()
    assert body["since_seq"] == 1
    assert body["max_seq"] == 3
    assert [e["seq"] for e in body["events"]] == [2, 3]
    assert body["has_more"] is False


def test_terminal_missing_task_404(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        resp = client.get("/api/v1/tasks/nope/terminal")
        assert resp.status_code == 404


def test_bus_fanout_live_push():
    async def scenario():
        bus = EventBus()
        bus.attach_loop(asyncio.get_running_loop())
        _sub_id, queue = bus.subscribe()
        event = Event(
            event_id="live1", seq=0, task_id="t1", pane_id=None,
            source="pi", kind="tool_call", severity="info",
            timestamp="2026-10-18T09:00:00Z", payload={},
        )
        bus.publish(event)
        got = await asyncio.wait_for(queue.get(), timeout=1)
        assert got.event_id == "live1"

    asyncio.run(scenario())


def test_websocket_replay_resume_and_ping_pong(make_monitor, settings):
    client, _m, _s, _b, _w = _client(make_monitor, settings)
    with client:
        with client.websocket_connect("/ws?since_seq=0") as ws:
            messages = []
            while True:
                msg = ws.receive_json()
                messages.append(msg)
                if msg["type"] == "live_ready":
                    break
            types = [m["type"] for m in messages]
            assert types[-1] == "live_ready"
            replay = [m for m in messages if m["type"] == "replay"]
            assert len(replay) == 3  # the three pre-seeded events

            ready = messages[-1]
            assert ready["since_seq"] == 3

            # ping -> pong
            ws.send_json({"type": "ping", "timestamp": 12345})
            pong = ws.receive_json()
            assert pong == {"type": "pong", "timestamp": 12345}

        # Resuming from the last seq yields no replay, then live_ready.
        with client.websocket_connect("/ws?since_seq=3") as ws:
            first = ws.receive_json()
            assert first["type"] == "live_ready"
            assert first["since_seq"] == 3

"""HTTP + WebSocket surface (report chapter 3).

All handlers are read-only: they return the aggregate state assembled by the
monitor and never touch the FirstMate home or issue Herdr mutations.
"""

from __future__ import annotations

import asyncio
from typing import Any

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from . import __version__
from .bus import EventBus
from .clock import iso_now
from .models import Event
from .monitor import Monitor

WS_TOPIC = "events"
TERMINAL_DEFAULT_LINES = 50
TERMINAL_MAX_LINES = 200
EVENTS_DEFAULT_LIMIT = 100
EVENTS_MAX_LIMIT = 500
REPLAY_BATCH = 500
REPLAY_HARD_CAP = 5000


def _service_status(collectors: dict[str, dict[str, Any]]) -> str:
    statuses = {c.get("status") for c in collectors.values()}
    if not statuses or statuses == {"starting"}:
        return "starting"
    if statuses & {"error", "degraded", "unavailable"}:
        return "degraded"
    return "healthy"


def _overview_metrics(world: Any) -> dict[str, int]:
    counts = world.counts()
    return {
        "active_tasks_count": counts["active_tasks"],
        "queued_tasks_count": counts["queued_tasks"],
        "completed_tasks_count": counts["completed_tasks"],
        "active_alerts_count": counts["active_alerts"],
        "working_agents_count": counts["working_agents"],
    }


def create_app(monitor: Monitor, lifespan: Any | None = None) -> FastAPI:
    app = FastAPI(title="AgentPulse", version=__version__, lifespan=lifespan)
    world = monitor.world
    store = monitor.store
    bus = monitor.bus

    # -- health ----------------------------------------------------------

    @app.get("/api/v1/health")
    async def health() -> dict[str, Any]:
        collectors = world.collectors()
        return {
            "status": _service_status(collectors),
            "version": __version__,
            "uptime_seconds": world.uptime_seconds(),
            "timestamp": iso_now(),
            "collectors": collectors,
            "clients_connected": bus.clients_connected,
        }

    # -- overview --------------------------------------------------------

    @app.get("/api/v1/overview")
    async def overview() -> dict[str, Any]:
        tasks = world.tasks()
        active = [t.to_overview() for t in tasks if t.state == "in_flight"]
        critical = [
            a.to_dict()
            for a in world.alerts(status="active")
            if a.severity == "critical" or a.tier == "critical"
        ]
        return {
            "generated_at": iso_now(),
            "metrics": _overview_metrics(world),
            "active_tasks": active,
            "pending_decisions": world.decisions(),
            "critical_alerts": critical,
        }

    # -- alerts ----------------------------------------------------------

    @app.get("/api/v1/alerts")
    async def alerts(
        status: str | None = Query(default="active"),
        tier: str | None = Query(default=None),
    ) -> dict[str, Any]:
        wanted_status = None if status == "all" else (status or "active")
        rows = world.alerts(status=wanted_status)
        if tier:
            wanted_tier = tier.strip().lower()
            rows = [a for a in rows if a.tier == wanted_tier]
        return {"total": len(rows), "alerts": [a.to_dict() for a in rows]}

    # -- tasks -----------------------------------------------------------

    @app.get("/api/v1/tasks")
    async def tasks(
        state: str | None = Query(default=None),
        repo: str | None = Query(default=None),
    ) -> dict[str, Any]:
        wanted_states = {s.strip() for s in (state or "").split(",") if s.strip()}
        wanted_repo = (repo or "").strip()
        rows = world.tasks()
        if wanted_states:
            rows = [t for t in rows if t.state in wanted_states]
        if wanted_repo:
            rows = [t for t in rows if t.repo == wanted_repo]
        return {"total": len(rows), "tasks": [t.to_dict() for t in rows]}

    # -- terminal --------------------------------------------------------

    @app.get("/api/v1/tasks/{task_id}/terminal")
    async def terminal(
        task_id: str,
        lines: int = Query(default=TERMINAL_DEFAULT_LINES),
        format: str = Query(default="ansi"),
    ) -> dict[str, Any]:
        task = world.task(task_id)
        if task is None:
            return JSONResponse(status_code=404, content={"detail": "task not found"})

        requested = max(1, min(lines, TERMINAL_MAX_LINES))
        fmt = "ansi" if format == "ansi" else "text"
        pane_id = task.assigned_pane_id

        if not pane_id:
            return {
                "task_id": task_id,
                "pane_id": None,
                "format": fmt,
                "line_count": 0,
                "lines": [],
                "truncated": False,
            }

        raw = await monitor.read_terminal(pane_id, lines=requested, fmt=fmt)
        split = raw.splitlines()
        truncated = len(split) > requested
        return {
            "task_id": task_id,
            "pane_id": pane_id,
            "format": fmt,
            "line_count": len(split[:requested]),
            "lines": split[:requested],
            "truncated": truncated,
        }

    # -- events ----------------------------------------------------------

    @app.get("/api/v1/events")
    async def events(
        since_seq: int = Query(default=0),
        limit: int = Query(default=EVENTS_DEFAULT_LIMIT),
        task_id: str | None = Query(default=None),
    ) -> dict[str, Any]:
        cursor = max(0, since_seq)
        wanted = max(1, min(limit, EVENTS_MAX_LIMIT))
        rows = store.events_since(cursor, limit=wanted + 1, task_id=task_id or None)
        has_more = len(rows) > wanted
        rows = rows[:wanted]
        return {
            "since_seq": cursor,
            "max_seq": store.max_seq(),
            "has_more": has_more,
            "events": [e.to_dict() for e in rows],
        }

    # -- websocket -------------------------------------------------------

    @app.websocket("/ws")
    async def ws_endpoint(websocket: WebSocket) -> None:
        await websocket.accept()
        sub_id, queue = bus.subscribe()
        try:
            await _ws_replay_and_ready(websocket, store, bus)
            forwarder = asyncio.create_task(_ws_forward(websocket, queue))
            try:
                await _ws_receive(websocket)
            finally:
                forwarder.cancel()
        finally:
            bus.unsubscribe(sub_id)

    return app


# -- websocket helpers ------------------------------------------------------


def _parse_since(websocket: WebSocket) -> int | None:
    raw = websocket.query_params.get("since_seq")
    if raw is None or raw == "":
        return None
    try:
        return max(0, int(raw))
    except ValueError:
        return None


async def _ws_replay_and_ready(websocket: WebSocket, store: Any, bus: EventBus) -> None:
    since_seq = _parse_since(websocket)
    max_seq = store.max_seq()
    if since_seq is not None:
        cursor = since_seq
        replayed = 0
        while replayed < REPLAY_HARD_CAP:
            batch = store.events_since(cursor, limit=REPLAY_BATCH)
            if not batch:
                break
            for event in batch:
                await websocket.send_json(_envelope("replay", event))
                replayed += 1
            cursor = batch[-1].seq
        max_seq = store.max_seq()
    await websocket.send_json(
        {
            "type": "live_ready",
            "since_seq": max_seq,
            "timestamp": iso_now(),
        }
    )


async def _ws_forward(websocket: WebSocket, queue: asyncio.Queue) -> None:
    while True:
        event: Event = await queue.get()
        try:
            await websocket.send_json(_envelope("event_push", event))
        except Exception:  # noqa: BLE001 - client disconnected
            return


async def _ws_receive(websocket: WebSocket) -> None:
    while True:
        try:
            message = await websocket.receive_json()
        except (WebSocketDisconnect, RuntimeError):
            return
        except Exception:  # noqa: BLE001 - non-JSON text frame
            continue
        if not isinstance(message, dict):
            continue
        if message.get("type") == "ping":
            await websocket.send_json(
                {"type": "pong", "timestamp": message.get("timestamp")}
            )


def _envelope(kind: str, event: Event) -> dict[str, Any]:
    return {
        "type": kind,
        "seq": event.seq,
        "topic": WS_TOPIC,
        "data": event.to_dict(),
    }

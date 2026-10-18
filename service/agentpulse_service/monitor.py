"""The monitor: async orchestrator wiring collectors -> store -> bus -> alerts.

This is the only place that mutates the :class:`~agentpulse_service.world.World`
and appends events to the :class:`~agentpulse_service.store.EventStore`. Every
collector is strictly read-only; the monitor never writes underneath the
FirstMate home.
"""

from __future__ import annotations

import asyncio
from typing import Any

from .alerts import AlertEngine
from .bus import EventBus
from .clock import epoch_now
from .collectors.firstmate import FirstMateCollector, StatusLine
from .collectors.herdr import HerdrCollector
from .collectors.pi import PiCollector
from .config import Settings
from .correlation import session_id_from_path
from .models import (
    KIND_AGENT_STATUS_CHANGED,
    KIND_DECISION_NEEDED,
    KIND_PANE_OUTPUT,
    KIND_TASK_COMPLETED,
    KIND_TASK_FAILED,
    KIND_TASK_STATE_CHANGED,
    Event,
    Task,
    TerminalPane,
)
from .severity import (
    digest_lines,
    firstmate_current_key,
    firstmate_decision_key,
    firstmate_status_key,
    herdr_lines_key,
    herdr_status_key,
    severity_for,
    severity_for_state,
)
from .store import EventStore
from .util import iso_from_any, iso_from_date, iso_utc, normalize_pane_id
from .world import World

DEFAULT_MODEL_PLACEHOLDERS = {"", "default", "none"}


# ---------------------------------------------------------------------------
# Pure task construction (unit tested)
# ---------------------------------------------------------------------------


def map_task_state(
    backlog_state: str | None,
    current_state: str | None,
    hold_kind: str | None = None,
) -> str:
    """Map FirstMate states onto the report 2.2 task ``state`` enum.

    ``backlog.state`` is authoritative for the queue lifecycle; a reconciled
    ``current_state`` of ``failed`` or ``needs-decision`` overrides it, as does
    a captain hold.
    """
    current = (current_state or "").strip()
    if current == "failed":
        return "failed"
    if current == "needs-decision" or hold_kind == "captain":
        return "held"
    if backlog_state in ("queued", "in_flight", "done", "blocked", "held"):
        return backlog_state
    if current == "blocked":
        return "blocked"
    if current in ("working", "done"):
        return "in_flight"
    return "queued"


def _model_value(value: str | None) -> str | None:
    if value is None:
        return None
    text = value.strip()
    if text in DEFAULT_MODEL_PLACEHOLDERS:
        return None
    return text


def build_tasks(
    fleet: dict[str, Any] | None,
    meta_records: dict[str, dict[str, str]],
    *,
    now: str | None = None,
) -> list[Task]:
    """Build the task universe from the fleet snapshot + ``state/*.meta``.

    ``fleet`` is the parsed ``fm-fleet-snapshot.sh --json`` payload; its
    ``backlog.records`` is the full task list and its ``tasks`` array carries
    live metadata for the in-flight subset. ``meta_records`` is authoritative
    for ``herdr_pane_id`` (the report 2.3 join key), ``model`` and ``harness``.
    """
    now = now or iso_utc()
    generated = None
    records: list[dict[str, Any]] = []
    live: dict[str, dict[str, Any]] = {}
    if isinstance(fleet, dict):
        generated = fleet.get("generated")
        backlog = fleet.get("backlog") or {}
        raw_records = backlog.get("records")
        if isinstance(raw_records, list):
            records = [r for r in raw_records if isinstance(r, dict)]
        raw_tasks = fleet.get("tasks")
        if isinstance(raw_tasks, list):
            for entry in raw_tasks:
                if isinstance(entry, dict) and entry.get("id"):
                    live[entry["id"]] = entry

    tasks: list[Task] = []
    for record in records:
        task_id = record.get("id") or ""
        if not task_id:
            continue
        live_entry = live.get(task_id, {})
        meta = meta_records.get(task_id, {})

        paths = live_entry.get("paths") or {}
        worktree = (paths.get("worktree") or {}).get("path")
        current = live_entry.get("current_state") or {}
        pr = live_entry.get("pr") or {}
        pr_url = pr.get("url") or record.get("pr_url")

        body_lines = record.get("body_lines")
        description = "\n".join(body_lines) if isinstance(body_lines, list) else record.get("body_excerpt")

        hold_kind = record.get("hold_kind")
        backlog_state = record.get("state")
        current_state = current.get("state")
        state = map_task_state(backlog_state, current_state, hold_kind)

        created_at = iso_from_date(record.get("since")) or generated or now
        updated_at = iso_from_any(current.get("observed_at")) or created_at

        meta_pane_id = normalize_pane_id(meta.get("herdr_pane_id"))
        model = _model_value(meta.get("model"))

        actions = live_entry.get("actions") or {}
        suggested_actions = [
            val for key in ("steer", "watch") if isinstance((val := actions.get(key)), str) and val
        ]

        tasks.append(
            Task(
                id=task_id,
                title=record.get("title") or task_id,
                description=description,
                state=state,
                kind=record.get("kind") or "custom",
                repo=record.get("repo") or "",
                worktree=worktree or meta.get("worktree"),
                branch=live_entry.get("branch") or meta.get("branch"),
                pr_url=pr_url,
                harness=meta.get("harness") or live_entry.get("harness"),
                model=model,
                blocked_by=record.get("blocked_by"),
                blocked_by_ids=list(record.get("blocked_by_ids") or []),
                hold_reason=record.get("hold_reason"),
                hold_kind=hold_kind,
                meta_pane_id=meta_pane_id,
                crew_state=current_state,
                current_doing=current.get("detail"),
                current_state_source=current.get("source"),
                since=record.get("since"),
                suggested_actions=suggested_actions,
                created_at=created_at,
                updated_at=updated_at,
            )
        )
    return tasks


def build_pane(data: dict[str, Any]) -> TerminalPane:
    """Map one Herdr ``api snapshot`` pane dict onto a TerminalPane."""
    foreground = data.get("foreground_processes")
    return TerminalPane(
        pane_id=data.get("pane_id") or "",
        workspace_id=data.get("workspace_id") or "",
        tab_id=data.get("tab_id"),
        terminal_title=data.get("terminal_title") or data.get("terminal_title_stripped") or "",
        agent=data.get("agent"),
        agent_status=data.get("agent_status") or "unknown",
        cwd=data.get("cwd") or "",
        foreground_cwd=data.get("foreground_cwd") or "",
        focused=bool(data.get("focused")),
        foreground_processes=list(foreground) if isinstance(foreground, list) else [],
    )


# ---------------------------------------------------------------------------
# The monitor
# ---------------------------------------------------------------------------


class Monitor:
    def __init__(self, settings: Settings, store: EventStore, bus: EventBus, world: World) -> None:
        self.settings = settings
        self.store = store
        self.bus = bus
        self.world = world
        self.alerts = AlertEngine(world)

        self.firstmate = FirstMateCollector(settings)
        self.herdr = HerdrCollector(settings)
        self.pi = PiCollector(settings)

        self._stopping = asyncio.Event()
        self._loops: list[asyncio.Task] = []
        self._fleet: dict[str, Any] | None = None
        self._last_fleet_at = 0.0
        self._last_pane_output_at = 0.0
        self._agent_status: dict[str, str] = {}
        self._crew_state: dict[str, str] = {}
        self._pane_digests: dict[str, str] = {}

    # -- lifecycle ---------------------------------------------------------

    async def start(self) -> "Monitor":
        self.bus.attach_loop(asyncio.get_running_loop())
        self._loops = [
            asyncio.create_task(self._run_firstmate(), name="firstmate"),
            asyncio.create_task(self._run_herdr(), name="herdr"),
            asyncio.create_task(self._run_herdr_stream(), name="herdr-stream"),
            asyncio.create_task(self._run_pi(), name="pi"),
            asyncio.create_task(self._run_retention(), name="retention"),
        ]
        return self

    async def stop(self) -> None:
        self._stopping.set()
        for loop in self._loops:
            loop.cancel()
        await asyncio.gather(*self._loops, return_exceptions=True)

    # -- FirstMate ---------------------------------------------------------

    async def _run_firstmate(self) -> None:
        while not self._stopping.is_set():
            try:
                await self._poll_firstmate()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - keep the loop alive
                self.world.set_health("firstmate", "error", detail=str(exc)[:300])
            await self._sleep(self.settings.interval_firstmate)

    async def _poll_firstmate(self) -> None:
        meta = self.firstmate.read_meta()

        for line in self.firstmate.read_status_tail():
            self._emit_status_line(line)

        summary = self.firstmate.read_home_summary()
        if summary is not None:
            self.world.set_home_summary(summary)
            self._refresh_decisions(summary)

        if epoch_now() - self._last_fleet_at >= self.settings.interval_fleet_snapshot:
            self._last_fleet_at = epoch_now()
            await self._refresh_fleet()

        tasks = build_tasks(self._fleet, meta)
        self._emit_crew_state_changes(tasks)
        self.world.set_tasks(tasks)
        self.firstmate.mark_synced()
        self.world.set_health("firstmate", "ok", last_sync_epoch=epoch_now())

    async def _refresh_fleet(self) -> None:
        data = await self.firstmate.read_fleet_snapshot()
        if isinstance(data, dict):
            self._fleet = data
            self.world.set_snapshot_generated(data.get("generated"))

    def _refresh_decisions(self, summary: dict[str, Any]) -> None:
        decisions = summary.get("decisions_open")
        self.world.set_decisions(decisions if isinstance(decisions, list) else [])

    def _emit_status_line(self, line: StatusLine) -> None:
        dedup = firstmate_status_key(line.task_id, line.state, line.at_epoch)
        severity = severity_for_state(line.state)
        payload: dict[str, Any] = {"state": line.state, "note": line.note}
        if line.key:
            payload["key"] = line.key
        if line.at_epoch is not None:
            payload["at_epoch"] = line.at_epoch
        event = Event(
            event_id=dedup,
            seq=0,
            task_id=line.task_id,
            pane_id=None,
            source="firstmate",
            kind=KIND_TASK_STATE_CHANGED,
            severity=severity,
            timestamp=iso_utc(line.at_epoch) if line.at_epoch is not None else iso_utc(),
            payload=payload,
        )
        self._emit(event, dedup)

        if line.state == "needs-decision":
            self._emit(
                Event(
                    event_id=firstmate_decision_key(line.task_id, line.key),
                    seq=0,
                    task_id=line.task_id,
                    pane_id=None,
                    source="firstmate",
                    kind=KIND_DECISION_NEEDED,
                    severity="critical",
                    timestamp=iso_utc(line.at_epoch) if line.at_epoch is not None else iso_utc(),
                    payload={"key": line.key, "note": line.note},
                ),
                firstmate_decision_key(line.task_id, line.key),
            )
        elif line.state == "failed":
            self._emit(
                Event(
                    event_id=dedup + ":failed",
                    seq=0,
                    task_id=line.task_id,
                    pane_id=None,
                    source="firstmate",
                    kind=KIND_TASK_FAILED,
                    severity="critical",
                    timestamp=iso_utc(line.at_epoch) if line.at_epoch is not None else iso_utc(),
                    payload={"state": line.state, "note": line.note},
                ),
                dedup + ":failed",
            )
        elif line.state == "done":
            self._emit(
                Event(
                    event_id=dedup + ":completed",
                    seq=0,
                    task_id=line.task_id,
                    pane_id=None,
                    source="firstmate",
                    kind=KIND_TASK_COMPLETED,
                    severity="info",
                    timestamp=iso_utc(line.at_epoch) if line.at_epoch is not None else iso_utc(),
                    payload={"state": line.state, "note": line.note},
                ),
                dedup + ":completed",
            )

    def _emit_crew_state_changes(self, tasks: list[Task]) -> None:
        for task in tasks:
            crew = task.crew_state
            previous = self._crew_state.get(task.id)
            if crew and crew != previous:
                self._emit(
                    Event(
                        event_id=firstmate_current_key(task.id, crew, task.updated_at),
                        seq=0,
                        task_id=task.id,
                        pane_id=None,
                        source="firstmate",
                        kind=KIND_TASK_STATE_CHANGED,
                        severity=severity_for_state(crew),
                        timestamp=task.updated_at,
                        payload={"state": crew, "note": task.current_doing or ""},
                    ),
                    firstmate_current_key(task.id, crew, task.updated_at),
                )
            if crew:
                self._crew_state[task.id] = crew

    # -- Herdr -------------------------------------------------------------

    async def _run_herdr(self) -> None:
        while not self._stopping.is_set():
            try:
                await self._poll_herdr()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                self.world.set_health("herdr", "degraded", detail=str(exc)[:300])
            await self._sleep(self.settings.interval_herdr)

    async def _poll_herdr(self) -> None:
        snapshot = await self.herdr.snapshot()
        if not isinstance(snapshot, dict):
            return
        self.world.set_health(
            "herdr",
            "ok",
            socket=str(self.settings.herdr_socket),
            protocol=self.herdr.protocol or 0,
        )
        self.herdr.mark_synced()

        panes: dict[str, TerminalPane] = {}
        raw_panes = snapshot.get("panes")
        if isinstance(raw_panes, list):
            for raw in raw_panes:
                if not isinstance(raw, dict):
                    continue
                pane = build_pane(raw)
                if not pane.pane_id:
                    continue
                panes[pane.pane_id] = pane
                previous = self._agent_status.get(pane.pane_id)
                if previous is not None and previous != pane.agent_status:
                    self._emit(
                        Event(
                            event_id=herdr_status_key(
                                pane.pane_id, pane.agent_status, raw.get("state_change_seq")
                            ),
                            seq=0,
                            task_id=self._task_for_pane(pane.pane_id),
                            pane_id=pane.pane_id,
                            source="herdr",
                            kind=KIND_AGENT_STATUS_CHANGED,
                            severity=severity_for(KIND_AGENT_STATUS_CHANGED, {"current_status": pane.agent_status}),
                            timestamp=iso_utc(),
                            payload={
                                "pane_id": pane.pane_id,
                                "previous_status": previous,
                                "current_status": pane.agent_status,
                            },
                        ),
                        herdr_status_key(pane.pane_id, pane.agent_status, raw.get("state_change_seq")),
                    )
                self._agent_status[pane.pane_id] = pane.agent_status
                self.world.upsert_pane(pane)

        self.world.drop_missing_panes(set(panes))
        self.herdr.note_known_panes(set(panes))

        if epoch_now() - self._last_pane_output_at >= self.settings.interval_pane_output:
            self._last_pane_output_at = epoch_now()
            await self._poll_pane_outputs(list(panes))

    async def _poll_pane_outputs(self, pane_ids: list[str]) -> None:
        for pane_id in pane_ids:
            try:
                text = await self.herdr.pane_read(pane_id, lines=40, fmt="ansi")
            except Exception:  # noqa: BLE001
                continue
            lines = [ln for ln in text.splitlines() if ln.strip()]
            if not lines:
                continue
            digest = digest_lines(lines)
            previous = self._pane_digests.get(pane_id)
            if previous == digest:
                continue
            self._pane_digests[pane_id] = digest
            self._emit(
                Event(
                    event_id=herdr_lines_key(pane_id, digest),
                    seq=0,
                    task_id=self._task_for_pane(pane_id),
                    pane_id=pane_id,
                    source="herdr",
                    kind=KIND_PANE_OUTPUT,
                    severity="debug",
                    timestamp=iso_utc(),
                    payload={"pane_id": pane_id, "lines": lines[-40:]},
                ),
                herdr_lines_key(pane_id, digest),
            )

    async def _run_herdr_stream(self) -> None:
        await self.herdr.event_stream_loop(self._on_herdr_stream_event)

    def _on_herdr_stream_event(self, envelope: dict[str, Any]) -> None:
        event_name = envelope.get("event")
        data = envelope.get("data") or {}
        pane_id = data.get("pane_id")
        if event_name == "pane.agent_status_changed":
            status = data.get("agent_status") or "unknown"
            self._emit(
                Event(
                    event_id=herdr_status_key(pane_id, status, data.get("state_change_seq")),
                    seq=0,
                    task_id=self._task_for_pane(pane_id),
                    pane_id=pane_id,
                    source="herdr",
                    kind=KIND_AGENT_STATUS_CHANGED,
                    severity=severity_for(KIND_AGENT_STATUS_CHANGED, {"current_status": status}),
                    timestamp=iso_utc(),
                    payload={"pane_id": pane_id, "current_status": status},
                ),
                herdr_status_key(pane_id, status, data.get("state_change_seq")),
            )
        elif event_name == "pane.output_matched":
            self._emit(
                Event(
                    event_id=f"herdr:output:{pane_id}:{data.get('line_index', '')}",
                    seq=0,
                    task_id=self._task_for_pane(pane_id),
                    pane_id=pane_id,
                    source="herdr",
                    kind=KIND_PANE_OUTPUT,
                    severity="debug",
                    timestamp=iso_utc(),
                    payload={"pane_id": pane_id, "matched": data.get("matched") or data},
                ),
                None,
            )

    # -- Pi ----------------------------------------------------------------

    async def _run_pi(self) -> None:
        while not self._stopping.is_set():
            try:
                emitted = self._poll_pi()
                self.world.set_health(
                    "pi", "ok", active_sessions_tracked=self.pi.active_session_count()
                )
                self.pi.mark_synced()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                self.world.set_health("pi", "error", detail=str(exc)[:300])
            await self._sleep(self.settings.interval_pi)

    def _poll_pi(self) -> int:
        emitted = 0
        tasks = self.world.tasks()
        for task in tasks:
            if (task.harness or "").strip().lower() != "pi":
                continue
            if not task.worktree:
                continue
            path = self.pi.newest_session_file(task.worktree)
            if path is None:
                continue
            session_id = session_id_from_path(path) or path.stem
            records = self.pi.read_new_records(path)
            if not records:
                continue
            for event, dedup in self.pi.ingest_records(session_id, records):
                event.task_id = task.id
                if self._emit(event, dedup):
                    emitted += 1
        return emitted

    # -- retention ---------------------------------------------------------

    async def _run_retention(self) -> None:
        while not self._stopping.is_set():
            await self._sleep(self.settings.interval_retention)
            try:
                self.store.sweep()
            except Exception:  # noqa: BLE001
                pass

    # -- helpers -----------------------------------------------------------

    def _emit(self, event: Event, dedup_key: str | None = None) -> bool:
        if not self.store.append(event, dedup_key):
            return False
        self.bus.publish(event)
        self.alerts.observe(event)
        return True

    def _task_for_pane(self, pane_id: str | None) -> str | None:
        if not pane_id:
            return None
        for task in self.world.tasks():
            if task.assigned_pane_id == pane_id or task.meta_pane_id == pane_id:
                return task.id
        return None

    async def _sleep(self, seconds: float) -> None:
        try:
            await asyncio.wait_for(self._stopping.wait(), timeout=seconds)
        except asyncio.TimeoutError:
            pass

    # -- on-demand terminal read (for /api/v1/tasks/{id}/terminal) ---------

    async def read_terminal(self, pane_id: str, lines: int = 50, fmt: str = "ansi") -> str:
        return await self.herdr.pane_read(pane_id, lines=lines, fmt=fmt)

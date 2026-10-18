"""Pi collector: tail the newest JSONL session per task worktree.

Read-only. Pi persists one JSONL per session under
``~/.pi/agent/sessions/--<encoded-cwd>--/<iso>_<uuid>.jsonl`` (report 1.3).
The collector tails only the newest file for each tracked worktree and parses
``session``, ``model_change``, ``message`` (thinking / text / toolCall),
``toolResult`` and ``custom_message`` records, tracking usage and cost.

For non-Pi harnesses there is no session file, and the world keeps
``active_agent_session_id`` as an explicit ``null`` (report 1.4) -- this
collector never fabricates a session.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from ..clock import epoch_now
from ..config import Settings
from ..correlation import newest_pi_session
from ..models import (
    KIND_THINKING_CHUNK,
    KIND_TOOL_CALL,
    KIND_TOOL_RESULT,
    Event,
)
from ..severity import severity_for
from ..util import iso_from_any, pi_session_id_from_filename
from .base import CollectorHealth

MAX_FILE_READ = 4 * 1024 * 1024  # per-poll read cap on a session file


def _content_items(message: dict[str, Any]) -> list[dict[str, Any]]:
    content = message.get("content")
    if isinstance(content, list):
        return [item for item in content if isinstance(item, dict)]
    return []


def _tool_command(arguments: Any) -> str | None:
    """Extract a human-readable command string for ``bash`` tool calls."""
    if not isinstance(arguments, dict):
        return None
    command = arguments.get("command")
    if isinstance(command, str):
        return command
    return None


def _first_text(message: dict[str, Any]) -> str | None:
    for item in _content_items(message):
        if item.get("type") == "text" and isinstance(item.get("text"), str):
            return item["text"]
    return None


def events_from_record(
    session_id: str,
    record: dict[str, Any],
    *,
    now: str | None = None,
) -> list[tuple[Event, str | None]]:
    """Turn one parsed JSONL record into unified events + dedup keys.

    Pure and side-effect free so it can be unit tested against fixture lines.
    Returns ``[(event, dedup_key), ...]``.
    """
    now = now or iso_from_any(epoch_now()) or ""
    rtype = record.get("type")
    ts = iso_from_any(record.get("timestamp")) or now

    if rtype != "message" or not isinstance(record.get("message"), dict):
        return []

    message = record["message"]
    role = message.get("role")
    message_id = record.get("id")
    model = message.get("model")
    usage = message.get("usage")
    cost = None
    if isinstance(usage, dict) and isinstance(usage.get("cost"), dict):
        cost = usage["cost"]

    events: list[tuple[Event, str | None]] = []

    def emit(kind: str, payload: dict[str, Any], dedup: str | None) -> None:
        payload.setdefault("session_id", session_id)
        if model:
            payload.setdefault("model", model)
        if usage is not None:
            payload.setdefault("usage", usage)
        if cost is not None:
            payload.setdefault("cost", cost)
        event = Event(
            event_id=dedup or f"pi:{session_id}:{message_id}",
            seq=0,  # assigned by the store
            source="pi",
            kind=kind,
            severity=severity_for(kind, payload),
            timestamp=ts,
            task_id=None,  # filled by the monitor via session -> task link
            pane_id=None,
            payload=payload,
        )
        events.append((event, dedup))

    if role == "assistant":
        for index, item in enumerate(_content_items(message)):
            ctype = item.get("type")
            if ctype == "thinking" and isinstance(item.get("thinking"), str):
                emit(
                    KIND_THINKING_CHUNK,
                    {"channel": "thinking", "text": item["thinking"]},
                    f"pi:message:{session_id}:{message_id}:thinking:{index}",
                )
            elif ctype == "text" and isinstance(item.get("text"), str):
                emit(
                    KIND_THINKING_CHUNK,
                    {"channel": "text", "text": item["text"]},
                    f"pi:message:{session_id}:{message_id}:text:{index}",
                )
            elif ctype == "toolCall" and isinstance(item, dict):
                tool_call_id = item.get("id") or f"{message_id}:{index}"
                name = item.get("name")
                arguments = item.get("arguments")
                payload: dict[str, Any] = {
                    "toolName": name,
                    "toolCallId": tool_call_id,
                    "arguments": arguments,
                }
                command = _tool_command(arguments)
                if command is not None:
                    payload["command"] = command
                emit(
                    KIND_TOOL_CALL,
                    payload,
                    f"pi:toolcall:{session_id}:{tool_call_id}",
                )

    elif role == "toolResult":
        tool_call_id = message.get("toolCallId")
        name = message.get("toolName")
        is_error = message.get("isError") is True
        text = _first_text(message)
        emit(
            KIND_TOOL_RESULT,
            {
                "toolName": name,
                "toolCallId": tool_call_id,
                "isError": is_error,
                "text": (text or "")[:2000],
            },
            f"pi:toolcall:{session_id}:{tool_call_id}:result",
        )

    return events


class PiCollector:
    name = "pi"

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.health = CollectorHealth()
        self._offsets: dict[str, int] = {}
        self._remainders: dict[str, str] = {}
        # session_id -> tracked usage/identity state
        self._sessions: dict[str, dict[str, Any]] = {}
        self._active_sessions: set[str] = set()

    # -- session discovery -------------------------------------------------

    def newest_session_file(self, worktree: str | None) -> Path | None:
        return newest_pi_session(self.settings.pi_sessions_root, worktree)

    # -- incremental read ---------------------------------------------------

    def read_new_records(self, path: Path) -> list[dict[str, Any]]:
        """New, complete JSON records appended to ``path`` since last call."""
        key = str(path)
        try:
            size = path.stat().st_size
        except OSError:
            return []
        offset = self._offsets.get(key, 0)
        if size < offset:
            offset = 0
            self._remainders.pop(key, None)
        if size - offset > MAX_FILE_READ:
            offset = max(0, size - MAX_FILE_READ)
            self._remainders.pop(key, None)
        try:
            with open(path, "rb") as handle:
                handle.seek(offset)
                data = handle.read()
        except OSError:
            return []
        self._offsets[key] = offset + len(data)
        text = self._remainders.get(key, "") + data.decode("utf-8", "replace")
        parts = text.split("\n")
        self._remainders[key] = parts.pop()
        records: list[dict[str, Any]] = []
        for line in parts:
            line = line.strip()
            if not line:
                continue
            try:
                parsed = json.loads(line)
            except ValueError:
                continue
            if isinstance(parsed, dict):
                records.append(parsed)
        return records

    def ingest_records(
        self, session_id: str, records: list[dict[str, Any]]
    ) -> list[tuple[Event, str | None]]:
        """Parse records into unified events + dedup keys, update tracked usage.

        The caller (the monitor) publishes the returned events and tags each
        with the task whose session produced it.
        """
        events: list[tuple[Event, str | None]] = []
        state = self._sessions.setdefault(session_id, {})
        self._active_sessions.add(session_id)
        for record in records:
            self._apply_state(state, record)
            events.extend(events_from_record(session_id, record))
        return events

    def _apply_state(self, state: dict[str, Any], record: dict[str, Any]) -> None:
        rtype = record.get("type")
        if rtype == "session":
            state.setdefault("cwd", record.get("cwd"))
            state.setdefault("started_at", record.get("timestamp"))
        elif rtype == "model_change":
            model_id = record.get("modelId")
            if model_id:
                state["model"] = model_id
        elif rtype == "message" and isinstance(record.get("message"), dict):
            message = record["message"]
            role = message.get("role")
            if role == "assistant":
                state["messages"] = int(state.get("messages", 0)) + 1
                for item in _content_items(message):
                    if item.get("type") == "toolCall":
                        state["tool_calls"] = int(state.get("tool_calls", 0)) + 1
            usage = message.get("usage")
            if isinstance(usage, dict):
                state["total_tokens"] = int(state.get("total_tokens", 0)) + int(
                    usage.get("totalTokens") or 0
                )
                cost = usage.get("cost")
                if isinstance(cost, dict):
                    state["total_cost"] = float(state.get("total_cost", 0.0)) + float(
                        cost.get("total") or 0.0
                    )

    # -- health / queries ----------------------------------------------------

    def sessions_tracked(self) -> dict[str, dict[str, Any]]:
        return {sid: dict(state) for sid, state in self._sessions.items()}

    def session_usage(self, session_id: str) -> dict[str, Any] | None:
        state = self._sessions.get(session_id)
        return dict(state) if state is not None else None

    def active_session_count(self) -> int:
        return len(self._active_sessions)

    def mark_synced(self) -> None:
        self.health.mark_ok(epoch_now())

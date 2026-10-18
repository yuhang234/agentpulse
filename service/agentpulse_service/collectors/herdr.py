"""Herdr collector: read-only CLI snapshots/reads plus the socket event stream.

Only read-only Herdr surface is used: ``api snapshot``, ``agent list``,
``agent get``, ``pane process-info`` and ``pane read``. Mutating and lifecycle
commands (pane input/send-text/send-keys/close/split/focus/resize/move, agent,
workspace, tab or session mutations) are never issued. The socket connection
subscribes to ``pane.agent_status_changed`` / pane lifecycle events only.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any, Awaitable, Callable

from ..clock import epoch_now
from ..config import Settings
from .base import CollectorHealth

SUBSCRIPTION_EVENT_KINDS = (
    "pane.agent_status_changed",
    "pane.output_matched",
    "pane.scroll_changed",
)

STREAM_RECONNECT_SECONDS = 5.0
STREAM_RESUBSCRIBE_SECONDS = 45.0


def find_subscription_envelope(message: Any, depth: int = 0) -> dict[str, Any] | None:
    """Locate the ``{"event": ..., "data": ...}`` envelope in a socket frame.

    The wire format is scanned tolerantly because the frame may nest the
    envelope under ``result`` (or carry it at the top level).
    """
    if depth > 4:
        return None
    if isinstance(message, dict):
        event = message.get("event")
        data = message.get("data")
        if isinstance(event, str) and event in SUBSCRIPTION_EVENT_KINDS and isinstance(data, dict):
            return {"event": event, "data": data}
        for value in message.values():
            found = find_subscription_envelope(value, depth + 1)
            if found is not None:
                return found
    elif isinstance(message, list):
        for value in message:
            found = find_subscription_envelope(value, depth + 1)
            if found is not None:
                return found
    return None


class HerdrCollector:
    name = "herdr"

    def __init__(
        self,
        settings: Settings,
        runner: Callable[[list[str]], Awaitable[str]] | None = None,
    ) -> None:
        self.settings = settings
        self.health = CollectorHealth()
        self.protocol: int | None = None
        self.version: str | None = None
        self.events_mode = "polling"  # "stream" once the socket is subscribed
        self._runner = runner
        self._known_panes: set[str] = set()

    # -- CLI ------------------------------------------------------------

    async def run_cli(self, args: list[str]) -> str:
        if self._runner is not None:
            return await self._runner(args)
        proc = await asyncio.create_subprocess_exec(
            self.settings.herdr_bin,
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=20)
        if proc.returncode != 0:
            raise RuntimeError(
                f"herdr {' '.join(args)} rc={proc.returncode}: "
                f"{stderr.decode('utf-8', 'replace').strip()[:300]}"
            )
        return stdout.decode("utf-8", "replace")

    @staticmethod
    def unwrap(payload: dict[str, Any], key: str | None = None) -> dict[str, Any]:
        result = payload.get("result")
        if isinstance(result, dict):
            if key and isinstance(result.get(key), dict):
                return result[key]
            return result
        return payload

    async def snapshot(self) -> dict[str, Any] | None:
        """``herdr api snapshot`` -> the session snapshot object."""
        raw = await self.run_cli(["api", "snapshot"])
        payload = json.loads(raw)
        snapshot = self.unwrap(payload, "snapshot")
        protocol = snapshot.get("protocol")
        if isinstance(protocol, int):
            self.protocol = protocol
        version = snapshot.get("version")
        if isinstance(version, str):
            self.version = version
        return snapshot

    async def agent_list(self) -> list[dict[str, Any]]:
        raw = await self.run_cli(["agent", "list"])
        payload = json.loads(raw)
        agents = self.unwrap(payload).get("agents")
        return agents if isinstance(agents, list) else []

    async def agent_get(self, pane_id: str) -> dict[str, Any] | None:
        raw = await self.run_cli(["agent", "get", pane_id])
        payload = json.loads(raw)
        agent = self.unwrap(payload, "agent")
        return agent if isinstance(agent, dict) else None

    async def process_info(self, pane_id: str) -> dict[str, Any] | None:
        raw = await self.run_cli(["pane", "process-info", "--pane", pane_id])
        payload = json.loads(raw)
        info = self.unwrap(payload, "process_info")
        return info if isinstance(info, dict) else None

    async def pane_read(self, pane_id: str, lines: int = 50, fmt: str = "text") -> str:
        args = ["pane", "read", pane_id, "--lines", str(int(lines))]
        if fmt == "ansi":
            args += ["--format", "ansi"]
        return await self.run_cli(args)

    # -- socket event stream --------------------------------------------

    def note_known_panes(self, pane_ids: set[str]) -> None:
        self._known_panes = set(pane_ids)

    async def event_stream_loop(self, on_event: Callable[[dict[str, Any]], None]) -> None:
        """Subscribe to Herdr socket events, reconnecting and resubscribing.

        Falls back to ``events_mode = "polling"`` whenever the socket is
        unavailable; the caller's polling loop then keeps state fresh at the
        documented interval.
        """
        socket_path = Path(self.settings.herdr_socket)
        while True:
            if not socket_path.exists():
                self.events_mode = "polling"
                await asyncio.sleep(STREAM_RECONNECT_SECONDS)
                continue
            try:
                await self._stream_once(socket_path, on_event)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - fall back to polling
                self.events_mode = "polling"
                self.health.detail = f"socket stream: {exc}"
                await asyncio.sleep(STREAM_RECONNECT_SECONDS)

    async def _stream_once(
        self,
        socket_path: Path,
        on_event: Callable[[dict[str, Any]], None],
    ) -> None:
        reader, writer = await asyncio.open_unix_connection(str(socket_path))
        try:
            self.events_mode = "stream"
            await self._subscribe(writer)
            request_id = 1
            while True:
                try:
                    line = await asyncio.wait_for(
                        reader.readline(), timeout=STREAM_RESUBSCRIBE_SECONDS
                    )
                except asyncio.TimeoutError:
                    # Refresh pane subscriptions periodically; panes come and go.
                    await self._subscribe(writer)
                    continue
                if not line:
                    raise ConnectionError("herdr socket closed")
                text = line.decode("utf-8", "replace").strip()
                if not text:
                    continue
                try:
                    message = json.loads(text)
                except ValueError:
                    continue
                envelope = find_subscription_envelope(message)
                if envelope is None:
                    request_id += 1
                    continue
                data = envelope["data"]
                pane_id = data.get("pane_id")
                if envelope["event"] == "pane.created" and isinstance(pane_id, str):
                    self._known_panes.add(pane_id)
                    await self._subscribe(writer)
                if isinstance(pane_id, str):
                    self._known_panes.add(pane_id)
                on_event(envelope)
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:  # noqa: BLE001 - best effort close
                pass

    async def _subscribe(self, writer: asyncio.StreamWriter) -> None:
        subscriptions: list[dict[str, Any]] = [
            {"type": "pane.agent_status_changed", "pane_id": pane_id}
            for pane_id in sorted(self._known_panes)
        ]
        subscriptions += [
            {"type": "pane.created"},
            {"type": "pane.closed"},
            {"type": "pane.exited"},
        ]
        request = {"id": "agentpulse", "method": "events.subscribe", "params": {"subscriptions": subscriptions}}
        writer.write((json.dumps(request) + "\n").encode())
        await writer.drain()

    def mark_synced(self) -> None:
        self.health.mark_ok(epoch_now())


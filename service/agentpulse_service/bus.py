"""Fan-out of stored events to WebSocket clients (thread-safe bridge).

Collector threads call :meth:`EventBus.publish`; clients live on the asyncio
event loop and receive events through their own bounded queue.
"""

from __future__ import annotations

import asyncio
import threading
from typing import Any

from .models import Event

CLIENT_QUEUE_SIZE = 2000


class EventBus:
    def __init__(self) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        self._subs: dict[int, asyncio.Queue[Event]] = {}
        self._lock = threading.Lock()
        self._counter = 0
        self.dropped_events = 0

    # -- lifecycle ------------------------------------------------------
    def attach_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    def subscribe(self, maxsize: int = CLIENT_QUEUE_SIZE) -> tuple[int, asyncio.Queue[Event]]:
        queue: asyncio.Queue[Event] = asyncio.Queue(maxsize=maxsize)
        with self._lock:
            self._counter += 1
            sub_id = self._counter
            self._subs[sub_id] = queue
        return sub_id, queue

    def unsubscribe(self, sub_id: int) -> None:
        with self._lock:
            self._subs.pop(sub_id, None)

    @property
    def clients_connected(self) -> int:
        with self._lock:
            return len(self._subs)

    # -- publishing -----------------------------------------------------
    def publish(self, event: Event) -> None:
        """Publish from any thread; never blocks the caller."""
        loop = self._loop
        if loop is None or loop.is_closed():
            return
        try:
            loop.call_soon_threadsafe(self._enqueue, event)
        except RuntimeError:  # loop shut down mid-publish
            return

    def _enqueue(self, event: Event) -> None:
        with self._lock:
            queues = list(self._subs.values())
        for queue in queues:
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                # Slow client: drop its oldest event instead of stalling the
                # service; the client recovers by resuming from its since_seq.
                try:
                    queue.get_nowait()
                    queue.put_nowait(event)
                except (asyncio.QueueEmpty, asyncio.QueueFull):  # pragma: no cover
                    pass
                self.dropped_events += 1

    def stats(self) -> dict[str, Any]:
        return {"clients_connected": self.clients_connected, "dropped_events": self.dropped_events}

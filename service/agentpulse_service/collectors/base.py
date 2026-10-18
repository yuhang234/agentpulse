"""Shared collector plumbing."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class CollectorHealth:
    """Health of a single collector, as surfaced by /api/v1/health."""

    status: str = "starting"  # starting | ok | degraded | error
    detail: str | None = None
    last_sync_epoch: int | None = None
    extra: dict = field(default_factory=dict)

    def mark_ok(self, epoch: int) -> None:
        self.status = "ok"
        self.detail = None
        self.last_sync_epoch = epoch

    def mark_error(self, detail: str) -> None:
        self.status = "error"
        self.detail = detail[:500]

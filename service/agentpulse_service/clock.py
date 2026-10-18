"""Small time helpers shared by the collectors and the store."""

from __future__ import annotations

import time
from datetime import datetime, timezone


def epoch_now() -> int:
    """Current Unix time in whole seconds."""
    return int(time.time())


def iso_from_epoch(epoch: float) -> str:
    """Format a Unix timestamp as the service's canonical ISO-8601 UTC string."""
    return datetime.fromtimestamp(float(epoch), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def iso_now() -> str:
    return iso_from_epoch(epoch_now())


def epoch_from_iso(value: str | None) -> float | None:
    """Best-effort parse of an ISO-8601 timestamp into a Unix timestamp."""
    if not value:
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.timestamp()

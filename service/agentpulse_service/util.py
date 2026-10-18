"""Small shared helpers (timestamps, hashing, tolerant file reads, pi path encoding)."""

from __future__ import annotations

import hashlib
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Time
# ---------------------------------------------------------------------------


def now_epoch() -> float:
    return time.time()


def iso_utc(epoch: float | None = None) -> str:
    """ISO-8601 UTC timestamp with a trailing ``Z`` (report examples use this)."""
    dt = datetime.fromtimestamp(epoch if epoch is not None else time.time(), tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def iso_from_any(value: Any) -> str | None:
    """Best-effort conversion of a timestamp-ish value to an ISO-8601 UTC string."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return iso_utc(float(value))
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        if text.endswith("Z"):
            try:
                datetime.fromisoformat(text[:-1]).replace(tzinfo=timezone.utc)
                return text
            except ValueError:
                pass
        try:
            dt = datetime.fromisoformat(text)
        except ValueError:
            return None
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return None


def iso_from_date(value: Any) -> str | None:
    """Convert a ``YYYY-MM-DD`` string to an ISO-8601 UTC midnight timestamp."""
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    try:
        dt = datetime.strptime(text, "%Y-%m-%d")
    except ValueError:
        return None
    return dt.replace(tzinfo=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def file_mtime_iso(path: Path) -> str | None:
    try:
        return iso_utc(path.stat().st_mtime)
    except OSError:
        return None


def parse_epoch_text(value: str) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------------------
# Hashing / ids
# ---------------------------------------------------------------------------


def sha256_hex(data: str | bytes) -> str:
    if isinstance(data, str):
        data = data.encode("utf-8", "replace")
    return hashlib.sha256(data).hexdigest()


def stable_id(prefix: str, *parts: Any, length: int = 12) -> str:
    joined = "\x1f".join("" if p is None else str(p) for p in parts)
    digest = hashlib.sha1(joined.encode("utf-8", "replace")).hexdigest()
    return f"{prefix}{digest[:length]}"


# ---------------------------------------------------------------------------
# Tolerant file reads
# ---------------------------------------------------------------------------


def read_text(path: Path, *, max_bytes: int | None = None) -> str | None:
    try:
        with open(path, "rb") as fh:
            data = fh.read() if max_bytes is None else fh.read(max_bytes)
    except OSError:
        return None
    return data.decode("utf-8", "replace")


def read_json(path: Path) -> Any | None:
    """Read a JSON document, returning ``None`` when it is missing or mid-swap.

    FirstMate replaces ``home-summary.json`` atomically; if we ever observe a
    partial document we simply skip it this cycle rather than guessing.
    """
    text = read_text(path)
    if text is None:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def write_json_atomic(path: Path, payload: Any) -> None:  # pragma: no cover - test helper
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def parse_kv_text(text: str) -> dict[str, str]:
    """Parse ``key=value`` lines (FirstMate ``state/*.meta`` files)."""
    result: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


# ---------------------------------------------------------------------------
# Pi session path encoding (report 1.3 A)
# ---------------------------------------------------------------------------


def encode_pi_cwd(cwd: str | Path) -> str:
    """``/home/u/proj`` -> ``--home-u-proj--`` (leading slash dropped)."""
    text = str(cwd)
    if not text.startswith("/"):
        text = "/" + text
    return "--" + text.lstrip("/").replace("/", "-") + "--"


def decode_pi_cwd(encoded: str) -> str:
    """Inverse of :func:`encode_pi_cwd` for the report's encoding rule."""
    text = encoded
    if text.startswith("--"):
        text = text[2:]
    if text.endswith("--"):
        text = text[:-2]
    return "/" + text.replace("-", "/")


def pi_session_id_from_filename(name: str) -> str | None:
    """``2026-10-18T08-01-04-248Z_<uuid>.jsonl`` -> ``<uuid>``."""
    stem = name
    if stem.endswith(".jsonl"):
        stem = stem[: -len(".jsonl")]
    if "_" not in stem:
        return None
    session_id = stem.split("_", 1)[1].strip()
    return session_id or None


# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------


def clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def tail_lines(text: str, count: int) -> list[str]:
    if count <= 0:
        return []
    lines = text.splitlines()
    return lines[-count:]


def normalize_pane_id(value: str | None) -> str | None:
    """``default:w1F:p2`` -> ``w1F:p2``; invalid values return ``None``.

    FirstMate records the bare pane id (``w1F:p2``) while the Herdr snapshot
    endpoint target is session-qualified (``default:w1F:p2``).  Both collapse
    to the same pane identifier.
    """
    if not value:
        return None
    text = str(value).strip()
    parts = text.split(":")
    for index in range(len(parts) - 1):
        candidate = ":".join(parts[index:])
        head = candidate.split(":", 1)[0]
        if head and head[0] == "w" and head[1:].isdigit():
            return candidate
    return None


def dedup_suffix(*parts: Any) -> str:
    return hashlib.sha256("\x1f".join(str(p) for p in parts).encode("utf-8", "replace")).hexdigest()[:16]


def hours_ago_epoch(hours: float) -> float:
    return (datetime.now(tz=timezone.utc) - timedelta(hours=hours)).timestamp()

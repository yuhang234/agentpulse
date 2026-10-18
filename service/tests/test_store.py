"""Dedup keys (report 2.4) and the SQLite/ring-buffer event store."""

import pytest

from agentpulse_service.models import Event
from agentpulse_service.severity import (
    digest_lines,
    firstmate_status_key,
    herdr_lines_key,
    herdr_status_key,
    pi_message_key,
    pi_toolcall_key,
)
from agentpulse_service.store import EventStore


def _event(event_id="e1", task_id="t1", source="pi", kind="tool_call"):
    return Event(
        event_id=event_id,
        seq=0,
        task_id=task_id,
        pane_id=None,
        source=source,
        kind=kind,
        severity="info",
        timestamp="2026-10-18T00:00:00Z",
        payload={},
    )


def test_firstmate_status_key_shape():
    assert firstmate_status_key("t1", "working", 123) == "firstmate:status:t1:working:123"


def test_pi_dedup_keys():
    assert pi_message_key("s1", "m1") == "pi:message:s1:m1"
    assert pi_toolcall_key("s1", "c1") == "pi:toolcall:s1:c1"


def test_herdr_keys():
    assert herdr_status_key("p1", "working", 5) == "herdr:status:p1:working:5"
    digest = digest_lines(["a", "b"])
    assert herdr_lines_key("p1", digest) == f"herdr:lines:p1:{digest}"


def test_digest_lines_is_stable_and_last_five():
    assert digest_lines(["1", "2", "3", "4", "5", "6"]) == digest_lines(["2", "3", "4", "5", "6"])
    assert digest_lines(["a", "b"]) != digest_lines(["a", "c"])


class TestEventStore:
    def test_append_assigns_increasing_seq(self, tmp_path):
        store = EventStore(tmp_path / "e.db")
        try:
            assert store.append(_event("a")) is True
            assert store.append(_event("b")) is True
            assert store.max_seq() == 2
            rows = store.events_since(0, limit=10)
            assert [e.seq for e in rows] == [1, 2]
        finally:
            store.close()

    def test_dedup_drops_duplicate_key(self, tmp_path):
        store = EventStore(tmp_path / "e.db")
        try:
            assert store.append(_event("a"), dedup_key="k") is True
            assert store.append(_event("b"), dedup_key="k") is False
            assert store.count() == 1
        finally:
            store.close()

    def test_since_seq_cursor(self, tmp_path):
        store = EventStore(tmp_path / "e.db")
        try:
            for i in range(5):
                store.append(_event(event_id=f"e{i}"))
            assert [e.seq for e in store.events_since(2, limit=10)] == [3, 4, 5]
            assert store.events_since(5, limit=10) == []
            # task filter
            store.append(_event(event_id="other", task_id="t2"))
            rows = store.events_since(0, limit=10, task_id="t2")
            assert [e.task_id for e in rows] == ["t2"]
        finally:
            store.close()

    def test_limit_and_has_more_contract(self, tmp_path):
        store = EventStore(tmp_path / "e.db")
        try:
            for i in range(10):
                store.append(_event(event_id=f"e{i}"))
            rows = store.events_since(0, limit=4)
            assert len(rows) == 4
        finally:
            store.close()

    def test_ring_buffer_caps_per_task(self, tmp_path):
        store = EventStore(tmp_path / "e.db", ring_size=3)
        try:
            for i in range(5):
                store.append(_event(event_id=f"e{i}", task_id="t1"))
            ring = store.ring("t1")
            assert [e.event_id for e in ring] == ["e2", "e3", "e4"]
        finally:
            store.close()

    def test_sweep_max_rows(self, tmp_path):
        store = EventStore(tmp_path / "e.db", retention_max_rows=5, retention_days=10000)
        try:
            for i in range(10):
                store.append(_event(event_id=f"e{i}"))
            removed = store.sweep()
            assert removed >= 5
            assert store.count() == 5
        finally:
            store.close()

    def test_sweep_age(self, tmp_path, monkeypatch):
        import agentpulse_service.store as store_mod

        store = EventStore(tmp_path / "e.db", retention_days=7)
        real = store_mod.epoch_now
        try:
            store_mod.epoch_now = lambda: 1_000
            store.append(_event("old"))
            store_mod.epoch_now = lambda: 1_000 + 8 * 86400
            removed = store.sweep()
            assert removed == 1
            assert store.count() == 0
        finally:
            store_mod.epoch_now = real
            store.close()

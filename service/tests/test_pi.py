"""Pi JSONL parsing (report 1.3) — pure functions against fixture records."""

from agentpulse_service.collectors.pi import events_from_record
from agentpulse_service.models import KIND_THINKING_CHUNK, KIND_TOOL_CALL, KIND_TOOL_RESULT


def _assistant(record_id="m1", content=None, usage=None):
    return {
        "id": record_id,
        "timestamp": "2026-10-18T08:37:26.693Z",
        "type": "message",
        "message": {
            "role": "assistant",
            "model": "deepseek-v4.1-flash",
            "usage": usage
            or {
                "input": 50848,
                "output": 530,
                "reasoning": 421,
                "totalTokens": 51378,
                "cost": {"input": 0, "output": 0, "total": 0},
            },
            "content": content or [],
        },
    }


def test_assistant_thinking_and_tool_call():
    record = _assistant(
        content=[
            {"type": "thinking", "thinking": "let me start"},
            {"type": "text", "text": "I will arm supervision"},
            {
                "type": "toolCall",
                "id": "call_1",
                "name": "bash",
                "arguments": {"command": "git status"},
            },
        ]
    )
    events = events_from_record("s1", record)
    kinds = [kind for _e, kind in events]
    by_kind = {}
    for event, dedup in events:
        by_kind.setdefault(event.kind, []).append((event, dedup))

    assert set(by_kind) == {KIND_THINKING_CHUNK, KIND_TOOL_CALL}

    thinking = by_kind[KIND_THINKING_CHUNK]
    assert [e.payload.get("channel") for e, _d in thinking] == ["thinking", "text"]

    tool_call = by_kind[KIND_TOOL_CALL][0][0]
    assert tool_call.payload["toolName"] == "bash"
    assert tool_call.payload["command"] == "git status"
    assert tool_call.severity == "info"
    assert tool_call.payload["usage"]["totalTokens"] == 51378
    assert tool_call.payload["cost"]["total"] == 0


def test_tool_result():
    record = {
        "id": "r1",
        "timestamp": "2026-10-18T08:37:26.800Z",
        "parentId": "m1",
        "type": "message",
        "message": {
            "role": "toolResult",
            "toolCallId": "call_1",
            "toolName": "bash",
            "isError": True,
            "content": [{"type": "text", "text": "boom"}],
        },
    }
    events = events_from_record("s1", record)
    assert len(events) == 1
    event, dedup = events[0]
    assert event.kind == KIND_TOOL_RESULT
    assert event.payload["isError"] is True
    assert event.severity == "error"
    assert dedup == "pi:toolcall:s1:call_1:result"


def test_non_message_records_ignored():
    assert events_from_record("s1", {"type": "session", "id": "s1"}) == []
    assert events_from_record("s1", {"type": "model_change", "modelId": "x"}) == []


def test_tool_call_dedup_keyed_by_tool_call_id():
    record = _assistant(
        content=[
            {"type": "toolCall", "id": "call_99", "name": "edit", "arguments": {}}
        ]
    )
    events = events_from_record("s1", record)
    event, dedup = events[0]
    assert event.kind == KIND_TOOL_CALL
    assert dedup == "pi:toolcall:s1:call_99"

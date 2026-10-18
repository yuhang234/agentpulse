//! Response models for the AgentPulse monitoring service
//! (service/agentpulse_service/api.py + models.py). Field names match the
//! service exactly; unknown fields are ignored so the client tolerates
//! service-side enrichment.

use serde::Deserialize;

// -- health ---------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct Health {
    pub status: String,
    pub version: String,
    pub uptime_seconds: u64,
    pub timestamp: String,
    pub collectors: std::collections::HashMap<String, CollectorHealth>,
    pub clients_connected: u32,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Deserialize)]
pub struct CollectorHealth {
    pub status: String,
    #[serde(default)]
    pub detail: Option<String>,
    #[serde(flatten)]
    pub extra: std::collections::HashMap<String, serde_json::Value>,
}

// -- overview --------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct Overview {
    pub generated_at: String,
    pub metrics: Metrics,
    pub active_tasks: Vec<ActiveTask>,
    pub pending_decisions: Vec<Decision>,
    pub critical_alerts: Vec<Alert>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct Metrics {
    pub active_tasks_count: u32,
    pub queued_tasks_count: u32,
    #[allow(dead_code)]
    pub completed_tasks_count: u32,
    pub active_alerts_count: u32,
    pub working_agents_count: u32,
}

/// Compact task row from /api/v1/overview (Task.to_overview). The Task
/// entity carries the same data for display; these fields stay for shape
/// stability of the parsed contract.
#[allow(dead_code)]
#[derive(Debug, Clone, Deserialize)]
pub struct ActiveTask {
    pub id: String,
    pub title: String,
    pub kind: String,
    pub repo: String,
    pub state: String,
    pub agent: Option<String>,
    pub agent_status: Option<String>,
    pub since: Option<String>,
    pub current_doing: Option<String>,
}

/// One entry of home-summary decisions_open, passed through by the service.
#[derive(Debug, Clone, Deserialize)]
pub struct Decision {
    pub id: Option<String>,
    pub key: Option<String>,
    pub verb: Option<String>,
    pub summary: Option<String>,
    pub owner: Option<String>,
    #[allow(dead_code)]
    #[serde(flatten)]
    pub extra: std::collections::HashMap<String, serde_json::Value>,
}

// -- alerts ----------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct Alert {
    #[allow(dead_code)]
    pub alert_id: String,
    pub task_id: Option<String>,
    pub category: String,
    pub severity: String,
    pub title: String,
    pub message: String,
    pub key: Option<String>,
    pub suggested_actions: Vec<String>,
    pub created_at: String,
    #[allow(dead_code)]
    pub status: String,
}

// -- tasks -----------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct TasksResponse {
    #[allow(dead_code)]
    pub total: usize,
    pub tasks: Vec<Task>,
}

/// Full Task entity from /api/v1/tasks (Task.to_dict).
#[derive(Debug, Clone, Deserialize)]
pub struct Task {
    pub id: String,
    pub title: String,
    pub description: Option<String>,
    pub state: String,
    pub kind: String,
    pub repo: String,
    pub worktree: Option<String>,
    pub branch: Option<String>,
    pub pr_url: Option<String>,
    pub harness: Option<String>,
    pub model: Option<String>,
    pub blocked_by: Option<String>,
    pub blocked_by_ids: Vec<String>,
    pub hold_reason: Option<String>,
    pub hold_kind: Option<String>,
    pub assigned_pane_id: Option<String>,
    pub active_agent_session_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

// -- events ----------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct EventsResponse {
    #[allow(dead_code)]
    pub since_seq: i64,
    #[allow(dead_code)]
    pub max_seq: i64,
    pub has_more: bool,
    pub events: Vec<Event>,
}

/// Unified event (report 2.2 C). Payload stays raw JSON; the UI formats it
/// per kind (see format::format_event).
#[derive(Debug, Clone, Deserialize)]
pub struct Event {
    pub event_id: String,
    pub seq: i64,
    pub task_id: Option<String>,
    pub pane_id: Option<String>,
    pub source: String,
    pub kind: String,
    pub severity: String,
    pub timestamp: String,
    #[serde(default)]
    pub payload: serde_json::Map<String, serde_json::Value>,
}

// -- terminal --------------------------------------------------------------

#[allow(dead_code)]
#[derive(Debug, Clone, Deserialize)]
pub struct TerminalResponse {
    pub task_id: String,
    pub pane_id: Option<String>,
    pub format: String,
    pub line_count: usize,
    pub lines: Vec<String>,
    pub truncated: bool,
}

// -- websocket -------------------------------------------------------------

/// Envelope on /ws: replay and event_push carry an event in data;
/// live_ready carries since_seq; pong replies to our pings.
#[derive(Debug, Clone, Deserialize)]
pub struct WsEnvelope {
    #[serde(rename = "type")]
    pub msg_type: String,
    #[allow(dead_code)]
    pub seq: Option<i64>,
    pub data: Option<Event>,
    pub since_seq: Option<i64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_health() {
        let body = r#"{"status":"healthy","version":"0.1.0","uptime_seconds":3600,
            "timestamp":"2026-10-18T08:37:26.693Z",
            "collectors":{
              "firstmate":{"status":"ok","last_sync_epoch":1792377446},
              "herdr":{"status":"ok","socket":"/home/Yuhang/.config/herdr/herdr.sock","protocol":22},
              "pi":{"status":"ok","active_sessions_tracked":0}},
            "clients_connected":2}"#;
        let h: Health = serde_json::from_str(body).unwrap();
        assert_eq!(h.status, "healthy");
        assert_eq!(h.uptime_seconds, 3600);
        assert_eq!(h.clients_connected, 2);
        assert_eq!(h.collectors.len(), 3);
        assert_eq!(h.collectors["herdr"].status, "ok");
        assert_eq!(
            h.collectors["herdr"].extra.get("protocol").unwrap().as_i64(),
            Some(22)
        );
    }

    #[test]
    fn parse_overview() {
        let body = r#"{"generated_at":"2026-10-18T08:37:26.693Z",
            "metrics":{"active_tasks_count":1,"queued_tasks_count":2,
                       "completed_tasks_count":0,"active_alerts_count":1,
                       "working_agents_count":1},
            "active_tasks":[{"id":"agentpulse-tui","title":"AgentPulse: Debian TUI",
                "kind":"ship","repo":"agentpulse","state":"in_flight",
                "agent":"agy","agent_status":"working",
                "since":"2026-10-18T10:36:11.000Z",
                "current_doing":"harness busy (herdr-native)"}],
            "pending_decisions":[{"id":"agentpulse-service","key":"api-shape",
                "verb":"captain-hold","summary":"Confirm terminal format","owner":"captain"}],
            "critical_alerts":[{"alert_id":"a1","task_id":"agentpulse-tui",
                "category":"decision_required","severity":"critical",
                "title":"Decision needed","message":"Awaiting captain",
                "key":"api-shape","suggested_actions":["reply via inbox"],
                "created_at":"2026-10-18T08:30:00.000Z","status":"active"}]}"#;
        let o: Overview = serde_json::from_str(body).unwrap();
        assert_eq!(o.metrics.active_tasks_count, 1);
        assert_eq!(o.metrics.queued_tasks_count, 2);
        assert_eq!(o.metrics.active_alerts_count, 1);
        assert_eq!(o.active_tasks[0].id, "agentpulse-tui");
        assert_eq!(o.active_tasks[0].agent_status.as_deref(), Some("working"));
        assert_eq!(o.active_tasks[0].current_doing.as_deref(), Some("harness busy (herdr-native)"));
        assert_eq!(o.pending_decisions[0].summary.as_deref(), Some("Confirm terminal format"));
        assert_eq!(o.critical_alerts[0].severity, "critical");
        assert_eq!(o.critical_alerts[0].suggested_actions, vec!["reply via inbox"]);
    }

    #[test]
    fn parse_tasks() {
        let body = r#"{"total":2,"tasks":[
            {"id":"agentpulse-tui","title":"Debian TUI","description":"build it",
             "state":"in_flight","kind":"ship","repo":"agentpulse",
             "worktree":"/home/Yuhang/.treehouse/agentpulse-b36e39/1/agentpulse",
             "branch":"fm/agentpulse-tui","pr_url":null,"harness":"codebuddy",
             "model":"step-5-preview","blocked_by":null,"blocked_by_ids":[],
             "hold_reason":null,"hold_kind":null,"assigned_pane_id":"w16:p0",
             "active_agent_session_id":null,
             "created_at":"2026-10-18T10:00:00.000Z","updated_at":"2026-10-18T10:36:11.000Z"},
            {"id":"agentpulse-android","title":"Android app","description":null,
             "state":"queued","kind":"ship","repo":"agentpulse","worktree":null,
             "branch":null,"pr_url":null,"harness":null,"model":null,
             "blocked_by":"agentpulse-tui","blocked_by_ids":["agentpulse-tui"],
             "hold_reason":null,"hold_kind":null,"assigned_pane_id":null,
             "active_agent_session_id":null,
             "created_at":"2026-10-18T09:00:00.000Z","updated_at":"2026-10-18T09:00:00.000Z"}]}"#;
        let t: TasksResponse = serde_json::from_str(body).unwrap();
        assert_eq!(t.total, 2);
        assert_eq!(t.tasks[0].assigned_pane_id.as_deref(), Some("w16:p0"));
        assert_eq!(t.tasks[1].blocked_by_ids, vec!["agentpulse-tui"]);
        assert_eq!(t.tasks[1].pr_url, None);
    }

    #[test]
    fn parse_events_response() {
        let body = r#"{"since_seq":0,"max_seq":2,"has_more":false,"events":[
            {"event_id":"fm:1","seq":1,"task_id":"agentpulse-tui","pane_id":null,
             "source":"firstmate","kind":"task_state_changed","severity":"info",
             "timestamp":"2026-10-18T08:37:26.693Z",
             "payload":{"state":"working","note":"starting"}},
            {"event_id":"herdr:1","seq":2,"task_id":null,"pane_id":"w16:p0",
             "source":"herdr","kind":"agent_status_changed","severity":"info",
             "timestamp":"2026-10-18T08:37:30.000Z",
             "payload":{"pane_id":"w16:p0","previous_status":"idle","current_status":"working"}}]}"#;
        let e: EventsResponse = serde_json::from_str(body).unwrap();
        assert_eq!(e.max_seq, 2);
        assert!(!e.has_more);
        assert_eq!(e.events[0].kind, "task_state_changed");
        assert_eq!(e.events[1].pane_id.as_deref(), Some("w16:p0"));
        assert_eq!(e.events[0].payload.get("state").unwrap().as_str(), Some("working"));
    }

    #[test]
    fn parse_terminal_response() {
        let body = r#"{"task_id":"agentpulse-tui","pane_id":"w16:p0","format":"text",
            "line_count":3,"lines":["> esc to cancel","","Gemini 3.8 Flash · high"],
            "truncated":false}"#;
        let t: TerminalResponse = serde_json::from_str(body).unwrap();
        assert_eq!(t.pane_id.as_deref(), Some("w16:p0"));
        assert_eq!(t.lines.len(), 3);
        assert!(!t.truncated);
    }

    #[test]
    fn parse_terminal_response_without_pane() {
        let body = r#"{"task_id":"agentpulse-android","pane_id":null,"format":"text",
            "line_count":0,"lines":[],"truncated":false}"#;
        let t: TerminalResponse = serde_json::from_str(body).unwrap();
        assert_eq!(t.pane_id, None);
        assert!(t.lines.is_empty());
    }

    #[test]
    fn parse_ws_envelopes() {
        let replay: WsEnvelope = serde_json::from_str(
            r#"{"type":"replay","seq":7,"topic":"events","data":{
                "event_id":"x","seq":7,"task_id":"t","pane_id":null,"source":"service",
                "kind":"pane_output","severity":"debug","timestamp":"2026-10-18T08:37:26.693Z",
                "payload":{}}}"#,
        )
        .unwrap();
        assert_eq!(replay.msg_type, "replay");
        assert_eq!(replay.seq, Some(7));
        assert!(replay.data.is_some());

        let push: WsEnvelope = serde_json::from_str(
            r#"{"type":"event_push","seq":8,"topic":"events","data":{
                "event_id":"y","seq":8,"task_id":null,"pane_id":null,"source":"service",
                "kind":"pane_output","severity":"debug","timestamp":"2026-10-18T08:37:27.693Z",
                "payload":{}}}"#,
        )
        .unwrap();
        assert_eq!(push.msg_type, "event_push");
        assert_eq!(push.data.unwrap().seq, 8);

        let ready: WsEnvelope = serde_json::from_str(
            r#"{"type":"live_ready","since_seq":42,"timestamp":"2026-10-18T08:37:26.693Z"}"#,
        )
        .unwrap();
        assert_eq!(ready.msg_type, "live_ready");
        assert_eq!(ready.since_seq, Some(42));
        assert!(ready.data.is_none());

        let pong: WsEnvelope =
            serde_json::from_str(r#"{"type":"pong","timestamp":"2026-10-18T08:37:26.693Z"}"#).unwrap();
        assert_eq!(pong.msg_type, "pong");
    }
}

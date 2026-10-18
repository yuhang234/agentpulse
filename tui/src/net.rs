//! Background network workers: REST poller, pane-terminal reader and the
//! WebSocket event stream. All three are plain OS threads talking to the
//! AgentPulse monitoring service; they never mutate anything they read.

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::thread;
use std::time::{Duration, Instant};

use tungstenite::error::Error as WsError;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::Message;

use crate::cli::BaseUrl;
use crate::http;
use crate::model::{
    Event, EventsResponse, Health, Overview, TerminalResponse, TasksResponse, WsEnvelope,
};

/// Terminal read parameters: the service caps lines at 200; 40 matches the
/// service's own pane-output digest window.
pub const TERMINAL_LINES: usize = 40;
/// HTTP fallback batch for the event feed while the WebSocket is down.
pub const EVENTS_POLL_LIMIT: usize = 100;
/// Bound on has_more drain pages per poll round.
const EVENTS_DRAIN_PAGES: usize = 5;
const WS_PING_EVERY: Duration = Duration::from_secs(20);
const WS_PONG_DEADLINE: Duration = Duration::from_secs(45);
const WS_READ_TICK: Duration = Duration::from_millis(500);

/// One data arrival from a worker thread.
pub enum NetEvent {
    Health(Box<Health>),
    Overview(Box<Overview>),
    Tasks(Vec<crate::model::Task>),
    /// One or more events (HTTP fallback batch, WS replay frame or live push).
    Events(Vec<Event>),
    WsReady { since_seq: i64 },
    Terminal {
        task_id: String,
        pane_id: Option<String>,
        lines: Vec<String>,
        truncated: bool,
    },
    /// A REST poll round completed; rtt_ms is the round-trip time.
    PollOk { rtt_ms: u64 },
    /// The WebSocket stream came up (or dropped).
    LinkUp,
    LinkDown,
    /// A human-readable failure, for the status line.
    Error(String),
}

/// Commands from the UI thread to the workers.
pub enum WorkerCmd {
    /// Force an immediate data resync.
    Resync,
    /// Follow (or stop following) a task's pane terminal.
    Follow(Option<String>),
    /// Shut the worker down.
    Quit,
}

/// Owns the worker threads and the command channels.
pub struct Workers {
    poller_tx: Sender<WorkerCmd>,
    pane_tx: Sender<WorkerCmd>,
    /// Held (not cloned) so the WS thread's recv sees Disconnected on drop.
    ws_tx: Sender<WorkerCmd>,
    handles: Vec<thread::JoinHandle<()>>,
}

impl Workers {
    pub fn resync(&self) {
        let _ = self.poller_tx.send(WorkerCmd::Resync);
    }

    pub fn follow(&self, task_id: Option<String>) {
        let _ = self.pane_tx.send(WorkerCmd::Follow(task_id));
    }

    pub fn shutdown(self) {
        let _ = self.poller_tx.send(WorkerCmd::Quit);
        let _ = self.pane_tx.send(WorkerCmd::Quit);
        // Dropping ws_tx makes the WS worker's recv fail with Disconnected.
        drop(self.ws_tx);
        drop(self.pane_tx);
        drop(self.poller_tx);
        for h in self.handles {
            let _ = h.join();
        }
    }
}

/// Bounded reconnect backoff: 1, 2, 4, 8, 16, then 30 seconds forever.
pub fn backoff_delay(attempt: u32) -> Duration {
    let secs = 1u64 << attempt.min(5);
    Duration::from_secs(secs.min(30))
}

/// Spawn the poller, pane reader and WebSocket workers.
pub fn spawn(base: BaseUrl, interval: u64, tx: Sender<NetEvent>) -> Workers {
    let ws_up = std::sync::Arc::new(AtomicBool::new(false));
    let cursor = std::sync::Arc::new(AtomicI64::new(0));

    let (poller_tx, poller_rx) = mpsc::channel();
    let (pane_tx, pane_rx) = mpsc::channel();
    let (ws_tx, ws_rx) = mpsc::channel();

    let mut handles = Vec::new();

    // -- REST poller ------------------------------------------------------
    {
        let base = base.clone();
        let tx = tx.clone();
        let ws_up = ws_up.clone();
        let cursor = cursor.clone();
        handles.push(thread::spawn(move || {
            let interval = Duration::from_secs(interval.max(1));
            loop {
                // Poll immediately, then wait for the next tick or a command.
                let t0 = Instant::now();
                let ok = poll_once(&base, &tx, ws_up.load(Ordering::Relaxed), cursor.load(Ordering::Relaxed));
                if ok {
                    let _ = tx.send(NetEvent::PollOk {
                        rtt_ms: t0.elapsed().as_millis() as u64,
                    });
                } else {
                    let _ = tx.send(NetEvent::Error("service unreachable".to_string()));
                }
                match poller_rx.recv_timeout(interval) {
                    Ok(WorkerCmd::Resync) => {
                        // Loop right away for an immediate refresh.
                    }
                    Ok(WorkerCmd::Quit) | Err(RecvTimeoutError::Disconnected) => return,
                    _ => {}
                }
            }
        }));
    }

    // -- pane terminal reader ----------------------------------------------
    {
        let base = base.clone();
        let tx = tx.clone();
        handles.push(thread::spawn(move || {
            let mut current: Option<String> = None;
            loop {
                match pane_rx.recv_timeout(Duration::from_secs(2)) {
                    Ok(WorkerCmd::Follow(target)) => {
                        current = target;
                    }
                    Ok(WorkerCmd::Quit) | Err(RecvTimeoutError::Disconnected) => return,
                    _ => {}
                }
                if let Some(task_id) = current.clone() {
                    if let Some(resp) = fetch_terminal(&base, &task_id) {
                        let _ = tx.send(NetEvent::Terminal {
                            task_id,
                            pane_id: resp.pane_id,
                            lines: resp.lines,
                            truncated: resp.truncated,
                        });
                    }
                }
            }
        }));
    }

    // -- WebSocket event stream --------------------------------------------
    {
        let tx = tx.clone();
        let cursor_for_ws = cursor.clone();
        handles.push(thread::spawn(move || {
            let mut attempt: u32 = 0;
            loop {
                let url = format!(
                    "{}?since_seq={}",
                    base.ws,
                    cursor_for_ws.load(Ordering::Relaxed)
                );
                match tungstenite::connect(url.as_str()) {
                    Ok((mut socket, _)) => {
                        attempt = 0;
                        if let MaybeTlsStream::Plain(stream) = socket.get_mut() {
                            let _ = stream.set_read_timeout(Some(WS_READ_TICK));
                        }
                        let _ = tx.send(NetEvent::LinkUp);
                        let mut last_ping = Instant::now();
                        let mut last_pong = Instant::now();
                        'read: loop {
                            match socket.read() {
                                Ok(Message::Text(text)) => {
                                    if let Ok(env) =
                                        serde_json::from_str::<WsEnvelope>(text.as_str())
                                    {
                                        match env.msg_type.as_str() {
                                            "replay" | "event_push" => {
                                                if let Some(event) = env.data {
                                                    let _ = tx.send(NetEvent::Events(vec![event]));
                                                }
                                            }
                                            "live_ready" => {
                                                let _ = tx.send(NetEvent::WsReady {
                                                    since_seq: env.since_seq.unwrap_or(0),
                                                });
                                            }
                                            "pong" => last_pong = Instant::now(),
                                            _ => {}
                                        }
                                    }
                                }
                                Ok(Message::Close(_)) => break 'read,
                                Ok(_) => {}
                                Err(WsError::Io(e))
                                    if matches!(
                                        e.kind(),
                                        std::io::ErrorKind::WouldBlock
                                            | std::io::ErrorKind::TimedOut
                                    ) =>
                                {
                                    // Idle tick: fall through to the keepalive check.
                                }
                                Err(_) => break 'read,
                            }
                            let now = Instant::now();
                            if now.duration_since(last_ping) >= WS_PING_EVERY {
                                let ping = format!(
                                    "{{\"type\":\"ping\",\"timestamp\":{}}}",
                                    now.elapsed().as_millis()
                                );
                                if socket.send(Message::Text(ping)).is_err() {
                                    break 'read;
                                }
                                last_ping = now;
                            }
                            if now.duration_since(last_pong) >= WS_PONG_DEADLINE {
                                break 'read; // peer stopped answering
                            }
                        }
                        let _ = socket.close(None);
                        let _ = tx.send(NetEvent::LinkDown);
                    }
                    Err(e) => {
                        let _ = tx.send(NetEvent::Error(format!("event stream: {e}")));
                    }
                }
                // Bounded backoff; a Resync command cuts the wait short.
                let wait = backoff_delay(attempt);
                attempt = attempt.saturating_add(1);
                let deadline = Instant::now() + wait;
                loop {
                    let remaining = deadline.saturating_duration_since(Instant::now());
                    if remaining.is_zero() {
                        break;
                    }
                    match ws_rx.recv_timeout(remaining) {
                        Ok(WorkerCmd::Resync) => break,
                        Ok(WorkerCmd::Quit) | Err(RecvTimeoutError::Disconnected) => return,
                        _ => {}
                    }
                }
            }
        }));
    }

    // Drop the sender clones held by this scope so the workers observe
    // Disconnected if they all exit; the per-worker senders stay alive in
    // Workers until shutdown.
    drop(tx);

    Workers {
        poller_tx,
        pane_tx,
        ws_tx,
        handles,
    }
}

/// One REST poll round. Returns true when the service answered.
fn poll_once(
    base: &BaseUrl,
    tx: &Sender<NetEvent>,
    ws_up: bool,
    cursor: i64,
) -> bool {
    let mut ok = false;
    if let Ok(resp) = http::get(&format!("{}/api/v1/health", base.http)) {
        if resp.status == 200 {
            match serde_json::from_str::<Health>(&resp.body) {
                Ok(h) => {
                    let _ = tx.send(NetEvent::Health(Box::new(h)));
                    ok = true;
                }
                Err(e) => {
                    let _ = tx.send(NetEvent::Error(format!("health parse: {e}")));
                }
            }
        } else {
            let _ = tx.send(NetEvent::Error(format!(
                "health: HTTP {} {}",
                resp.status,
                first_line(&resp.body)
            )));
        }
    } else {
        let _ = tx.send(NetEvent::Error("health: unreachable".to_string()));
    }

    if let Ok(resp) = http::get(&format!("{}/api/v1/overview", base.http)) {
        if resp.status == 200 {
            match serde_json::from_str::<Overview>(&resp.body) {
                Ok(o) => {
                    let _ = tx.send(NetEvent::Overview(Box::new(o)));
                    ok = true;
                }
                Err(e) => {
                    let _ = tx.send(NetEvent::Error(format!("overview parse: {e}")));
                }
            }
        }
    }

    if let Ok(resp) = http::get(&format!("{}/api/v1/tasks", base.http)) {
        if resp.status == 200 {
            match serde_json::from_str::<TasksResponse>(&resp.body) {
                Ok(t) => {
                    let _ = tx.send(NetEvent::Tasks(t.tasks));
                    ok = true;
                }
                Err(e) => {
                    let _ = tx.send(NetEvent::Error(format!("tasks parse: {e}")));
                }
            }
        }
    }

    // While the WebSocket is down, poll the feed so it stays live.
    // Drain has_more pages so a burst cannot outrun one batch.
    if !ws_up {
        let mut since = cursor;
        for _ in 0..EVENTS_DRAIN_PAGES {
            let url = format!(
                "{}/api/v1/events?since_seq={}&limit={}",
                base.http, since, EVENTS_POLL_LIMIT
            );
            let Ok(resp) = http::get(&url) else { break };
            if resp.status != 200 {
                break;
            }
            let Ok(page) = serde_json::from_str::<EventsResponse>(&resp.body) else {
                break
            };
            ok = true;
            match page.events.last() {
                Some(last) => {
                    let _ = tx.send(NetEvent::Events(page.events));
                    since = last.seq;
                    if !page.has_more {
                        break;
                    }
                }
                // Nothing newer than the cursor; jump to the service's max.
                None => break,
            }
        }
    }
    ok
}

fn fetch_terminal(base: &BaseUrl, task_id: &str) -> Option<TerminalResponse> {
    let url = format!(
        "{}/api/v1/tasks/{}/terminal?lines={}&format=text",
        base.http,
        crate::cli::path_encode(task_id),
        TERMINAL_LINES
    );
    match http::get(&url) {
        Ok(resp) if resp.status == 200 => match serde_json::from_str::<TerminalResponse>(&resp.body) {
            Ok(t) => Some(t),
            Err(_) => None,
        },
        // 404 means the task is gone; anything else is transient and simply
        // leaves the previous lines on screen.
        _ => None,
    }
}

fn first_line(s: &str) -> String {
    s.lines().next().unwrap_or("").chars().take(80).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_is_bounded() {
        assert_eq!(backoff_delay(0), Duration::from_secs(1));
        assert_eq!(backoff_delay(1), Duration::from_secs(2));
        assert_eq!(backoff_delay(2), Duration::from_secs(4));
        assert_eq!(backoff_delay(3), Duration::from_secs(8));
        assert_eq!(backoff_delay(4), Duration::from_secs(16));
        assert_eq!(backoff_delay(5), Duration::from_secs(30));
        assert_eq!(backoff_delay(50), Duration::from_secs(30));
        assert_eq!(backoff_delay(u32::MAX), Duration::from_secs(30));
    }

    #[test]
    fn ws_envelope_from_service_shape() {
        // Mirrors _envelope() in service/agentpulse_service/api.py.
        let text = r#"{"type":"event_push","seq":12,"topic":"events","data":{
            "event_id":"herdr:1","seq":12,"task_id":null,"pane_id":"w16:p0",
            "source":"herdr","kind":"pane_output","severity":"debug",
            "timestamp":"2026-10-18T08:37:26.693Z",
            "payload":{"pane_id":"w16:p0","lines":["> esc to cancel"]}}}"#;
        let env: WsEnvelope = serde_json::from_str(text).unwrap();
        assert_eq!(env.msg_type, "event_push");
        assert_eq!(env.data.unwrap().seq, 12);
    }
}

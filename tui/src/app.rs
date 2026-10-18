//! Application state: data cache, view/focus/selection model, connection
//! honesty and key handling. The client is strictly read-only: every method
//! here only reshapes what is displayed or asks a worker to fetch data.

use std::collections::VecDeque;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

use crate::cli::{BaseUrl, Cli};
use crate::layout::LayoutKind;
use crate::model::{Alert, Decision, Event, Health, Overview, Task};
use crate::net::{NetEvent, Workers};
use crate::palette::Palette;
use crate::strings;

/// Maximum events kept in the in-memory feed.
pub const FEED_CAP: usize = 500;
/// Maximum composed alert items.
const ALERT_CAP: usize = 50;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum View {
    Overview,
    Tasks,
    Feed,
    Alerts,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Overlay {
    None,
    Help,
    Alerts,
    TaskDetail(String),
    EventDetail(usize),
    Follow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnState {
    Connected,
    Stale,
    Disconnected,
}

/// Panels that can take focus, per view and layout.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Panel {
    /// In-flight tasks (compact overview, top-left).
    Active,
    /// Queued backlog (compact overview, bottom-left).
    Queued,
    /// Full task pipeline (tasks view, wide overview).
    All,
    /// Pane terminal output.
    Pane,
    /// Event feed.
    Feed,
    /// Composed alerts and decisions.
    AlertsPanel,
}

#[derive(Debug, Clone)]
pub struct PaneView {
    pub task_id: String,
    pub pane_id: Option<String>,
    pub lines: Vec<String>,
    pub truncated: bool,
}

#[derive(Debug, Default)]
pub struct Data {
    pub health: Option<Health>,
    pub overview: Option<Overview>,
    pub tasks: Vec<Task>,
    pub pane: Option<PaneView>,
    pub max_seq: i64,
    pub last_ok: Option<Instant>,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct Conn {
    pub ws_up: bool,
    /// Did the most recent transport attempt succeed?
    pub attempt_ok: bool,
    pub rtt_ms: Option<u64>,
}

/// One row of the composed alerts view.
#[derive(Debug, Clone)]
pub struct AlertItem {
    pub severity: String,
    pub title: String,
    pub detail: String,
    pub task_id: Option<String>,
}

pub struct App {
    pub base: BaseUrl,
    pub cli: Cli,
    pub palette: Palette,
    pub view: View,
    pub focus: usize,
    pub task_sel: usize,
    pub feed: VecDeque<Event>,
    pub feed_sel: usize,
    pub alerts_sel: usize,
    pub pane_sel: usize,
    pub detail_scroll: u16,
    pub overlay: Overlay,
    pub data: Data,
    pub conn: Conn,
    pub alerts: Vec<AlertItem>,
    pub size: (u16, u16),
    pub follow_target: Option<String>,
    pub quit: bool,
    /// Bumped whenever the task list changes, so the UI can re-clamp.
    pub tasks_generation: u64,
}

impl App {
    pub fn new(cli: Cli) -> Self {
        let base = crate::cli::parse_base_url(&cli.url).unwrap_or_else(|_| BaseUrl {
            http: cli.url.clone(),
            ws: format!("{}/ws", cli.url.trim_end_matches('/')),
            host: cli.url.clone(),
            tls: false,
        });
        let palette = Palette::new(crate::palette::detect_color_level(
            std::env::var("COLORTERM").ok().as_deref(),
            std::env::var("TERM").ok().as_deref(),
        ));
        Self {
            base,
            cli,
            palette,
            view: View::Overview,
            focus: 0,
            task_sel: 0,
            feed: VecDeque::new(),
            feed_sel: 0,
            alerts_sel: 0,
            pane_sel: 0,
            detail_scroll: 0,
            overlay: Overlay::None,
            data: Data::default(),
            conn: Conn::default(),
            alerts: Vec::new(),
            size: (80, 24),
            follow_target: None,
            quit: false,
            tasks_generation: 0,
        }
    }

    // -- layout helpers ----------------------------------------------------

    pub fn layout(&self) -> LayoutKind {
        crate::layout::select_layout(self.size.0, self.size.1)
    }

    /// Focusable panels for the current view and layout.
    pub fn panels(&self) -> Vec<Panel> {
        match self.view {
            View::Overview => match self.layout() {
                LayoutKind::Wide => vec![Panel::All, Panel::Pane, Panel::Feed],
                LayoutKind::Compact => {
                    vec![Panel::Active, Panel::Pane, Panel::Queued, Panel::AlertsPanel]
                }
                LayoutKind::Fallback => vec![
                    Panel::Active,
                    Panel::All,
                    Panel::Pane,
                    Panel::AlertsPanel,
                ],
            },
            View::Tasks => vec![Panel::All],
            View::Feed => vec![Panel::Feed],
            View::Alerts => vec![Panel::AlertsPanel],
        }
    }

    pub fn focused_panel(&self) -> Panel {
        let panels = self.panels();
        panels[self.focus.min(panels.len().saturating_sub(1))]
    }

    /// Tasks visible in a panel, in service order.
    pub fn panel_tasks(&self, panel: Panel) -> Vec<&Task> {
        match panel {
            Panel::Active => self
                .data
                .tasks
                .iter()
                .filter(|t| t.state == "in_flight")
                .collect(),
            Panel::Queued => self
                .data
                .tasks
                .iter()
                .filter(|t| t.state == "queued")
                .collect(),
            Panel::All => self.data.tasks.iter().collect(),
            _ => Vec::new(),
        }
    }

    pub fn selected_task(&self) -> Option<&Task> {
        self.data.tasks.get(self.task_sel)
    }

    /// The task the pane panel follows: the globally selected task.
    pub fn follow_task_id(&self) -> Option<String> {
        self.selected_task().map(|t| t.id.clone())
    }

    pub fn clamp_selection(&mut self) {
        if self.data.tasks.is_empty() {
            self.task_sel = 0;
        } else if self.task_sel >= self.data.tasks.len() {
            self.task_sel = self.data.tasks.len() - 1;
        }
        let panels = self.panels();
        if self.focus >= panels.len() {
            self.focus = panels.len().saturating_sub(1);
        }
        if self.alerts_sel >= self.alerts.len() && !self.alerts.is_empty() {
            self.alerts_sel = self.alerts.len() - 1;
        }
        let pane_len = self.data.pane.as_ref().map_or(0, |p| p.lines.len());
        if self.pane_sel >= pane_len && pane_len > 0 {
            self.pane_sel = pane_len - 1;
        }
    }

    // -- connection honesty -------------------------------------------------

    pub fn conn_state(&self, now: Instant) -> ConnState {
        conn_state(
            now,
            self.data.last_ok,
            self.conn.attempt_ok,
            Duration::from_secs(self.cli.stale_after.max(1)),
        )
    }

    /// Seconds since the last successful data arrival, if any.
    pub fn last_ok_age(&self, now: Instant) -> Option<u64> {
        self.data.last_ok.map(|t| now.duration_since(t).as_secs())
    }

    // -- network events -----------------------------------------------------

    pub fn on_net(&mut self, ev: NetEvent, workers: &Workers) {
        let now = Instant::now();
        match ev {
            NetEvent::Health(h) => {
                self.data.health = Some(*h);
                self.data.last_ok = Some(now);
                self.conn.attempt_ok = true;
            }
            NetEvent::Overview(o) => {
                self.data.overview = Some(*o);
                self.data.last_ok = Some(now);
                self.conn.attempt_ok = true;
                self.rebuild_alerts();
            }
            NetEvent::Tasks(tasks) => {
                self.data.tasks = tasks;
                self.tasks_generation = self.tasks_generation.wrapping_add(1);
                self.data.last_ok = Some(now);
                self.conn.attempt_ok = true;
                self.clamp_selection();
                self.sync_follow(workers);
                self.rebuild_alerts();
            }
            NetEvent::Events(events) => {
                self.ingest(events);
                self.data.last_ok = Some(now);
                self.conn.attempt_ok = true;
                self.rebuild_alerts();
            }
            NetEvent::WsReady { since_seq } => {
                self.data.max_seq = self.data.max_seq.max(since_seq);
            }
            NetEvent::Terminal {
                task_id,
                pane_id,
                lines,
                truncated,
            } => {
                self.data.pane = Some(PaneView {
                    task_id,
                    pane_id,
                    lines,
                    truncated,
                });
                self.pane_sel = 0;
                self.clamp_selection();
            }
            NetEvent::PollOk { rtt_ms } => {
                self.data.last_ok = Some(now);
                self.conn.attempt_ok = true;
                self.conn.rtt_ms = Some(rtt_ms);
            }
            NetEvent::LinkUp => {
                self.conn.ws_up = true;
                self.conn.attempt_ok = true;
            }
            NetEvent::LinkDown => {
                self.conn.ws_up = false;
                self.conn.attempt_ok = false;
            }
            NetEvent::Error(msg) => {
                self.data.last_error = Some(msg);
                self.conn.attempt_ok = false;
            }
        }
    }

    /// Ingest events with seq-based dedup; the feed follows its tail unless
    /// the user has scrolled up.
    fn ingest(&mut self, events: Vec<Event>) {
        let was_tail = self.feed_sel + 1 >= self.feed.len();
        for e in events {
            if e.seq <= self.data.max_seq {
                continue;
            }
            self.data.max_seq = e.seq;
            if self.feed.len() >= FEED_CAP {
                self.feed.pop_front();
                self.feed_sel = self.feed_sel.saturating_sub(1);
            }
            self.feed.push_back(e);
        }
        if was_tail && !self.feed.is_empty() {
            self.feed_sel = self.feed.len() - 1;
        }
    }

    /// Compose the alerts view from everything the service exposes: active
    /// critical alerts, open decisions and recent high-severity events.
    /// (The service has no /api/v1/alerts history endpoint, so this is the
    /// honest union of what it does serve.)
    fn rebuild_alerts(&mut self) {
        let mut items: Vec<AlertItem> = Vec::new();
        let mut seen: std::collections::HashSet<(String, Option<String>)> =
            std::collections::HashSet::new();

        if let Some(o) = &self.data.overview {
            for a in &o.critical_alerts {
                let key = (a.title.clone(), a.task_id.clone());
                if seen.insert(key) {
                    items.push(alert_item(a));
                }
            }
            for d in &o.pending_decisions {
                let item = decision_item(d);
                let key = (item.title.clone(), item.task_id.clone());
                if seen.insert(key) {
                    items.push(item);
                }
            }
        }
        // Recent warning/error/critical events, newest first.
        for e in self.feed.iter().rev() {
            if !matches!(e.severity.as_str(), "warning" | "error" | "critical") {
                continue;
            }
            let title = crate::format::format_event_line(e);
            let key = (title.clone(), e.task_id.clone());
            if seen.insert(key) {
                items.push(AlertItem {
                    severity: e.severity.clone(),
                    title,
                    detail: format!("seq {} · {}", e.seq, e.event_id),
                    task_id: e.task_id.clone(),
                });
            }
            if items.len() >= ALERT_CAP {
                break;
            }
        }
        // Critical first, then error, then warning; stable within a level.
        let rank = |s: &str| match s {
            "critical" => 0,
            "error" => 1,
            _ => 2,
        };
        items.sort_by_key(|i| rank(&i.severity));
        self.alerts = items;
        self.clamp_selection();
    }

    // -- follow target ------------------------------------------------------

    fn sync_follow(&mut self, workers: &Workers) {
        let target = self.follow_task_id();
        if target != self.follow_target {
            self.follow_target = target.clone();
            workers.follow(target);
        }
    }

    // -- keys ---------------------------------------------------------------

    pub fn on_key(&mut self, key: KeyEvent, workers: &Workers) {
        if key.modifiers.contains(KeyModifiers::CONTROL)
            && matches!(key.code, KeyCode::Char('c') | KeyCode::Char('q'))
        {
            self.quit = true;
            return;
        }
        match self.overlay.clone() {
            Overlay::None => self.on_key_main(key, workers),
            Overlay::Help => match key.code {
                KeyCode::Esc | KeyCode::Enter | KeyCode::Char('?' | 'q' | 'h') => {
                    self.overlay = Overlay::None
                }
                _ => {}
            },
            Overlay::Alerts => match key.code {
                KeyCode::Esc | KeyCode::Enter | KeyCode::Char('a' | 'q') => {
                    self.overlay = Overlay::None
                }
                KeyCode::Char('j') | KeyCode::Down => self.move_alerts(1),
                KeyCode::Char('k') | KeyCode::Up => self.move_alerts(-1),
                KeyCode::Char('r') => workers.resync(),
                _ => {}
            },
            Overlay::TaskDetail(_) => match key.code {
                KeyCode::Esc | KeyCode::Enter | KeyCode::Char('q') => self.overlay = Overlay::None,
                KeyCode::Char('j') | KeyCode::Down => {
                    self.detail_scroll = self.detail_scroll.saturating_add(1)
                }
                KeyCode::Char('k') | KeyCode::Up => {
                    self.detail_scroll = self.detail_scroll.saturating_sub(1)
                }
                KeyCode::Char('r') => workers.resync(),
                _ => {}
            },
            Overlay::EventDetail(_) => match key.code {
                KeyCode::Esc | KeyCode::Enter | KeyCode::Char('q') => self.overlay = Overlay::None,
                _ => {}
            },
            Overlay::Follow => match key.code {
                KeyCode::Esc | KeyCode::Enter | KeyCode::Char('t') => self.overlay = Overlay::None,
                KeyCode::Char('q') => self.quit = true,
                KeyCode::Char('j') | KeyCode::Down => self.move_pane(1),
                KeyCode::Char('k') | KeyCode::Up => self.move_pane(-1),
                KeyCode::Char('r') => workers.resync(),
                _ => {}
            },
        }
    }

    fn on_key_main(&mut self, key: KeyEvent, workers: &Workers) {
        match key.code {
            KeyCode::Char('q') => self.quit = true,
            KeyCode::Char('1') => self.switch_view(View::Overview),
            KeyCode::Char('2') => self.switch_view(View::Tasks),
            KeyCode::Char('3') => self.switch_view(View::Feed),
            KeyCode::Char('4') => self.switch_view(View::Alerts),
            KeyCode::Char('a') => self.overlay = Overlay::Alerts,
            KeyCode::Tab => {
                let n = self.panels().len();
                self.focus = (self.focus + 1) % n.max(1);
            }
            KeyCode::BackTab => {
                let n = self.panels().len();
                self.focus = (self.focus + n.saturating_sub(1)) % n.max(1);
            }
            KeyCode::Char('j') | KeyCode::Down => self.move_focus(1, workers),
            KeyCode::Char('k') | KeyCode::Up => self.move_focus(-1, workers),
            KeyCode::Enter => self.open_detail(),
            KeyCode::Char('t') => self.open_follow(),
            KeyCode::Char('r') => workers.resync(),
            KeyCode::Char('?') => self.overlay = Overlay::Help,
            _ => {}
        }
    }

    fn switch_view(&mut self, view: View) {
        self.view = view;
        self.focus = 0;
        self.clamp_selection();
    }

    /// j/k inside the focused panel.
    fn move_focus(&mut self, delta: i32, workers: &Workers) {
        match self.focused_panel() {
            Panel::Feed => {
                let len = self.feed.len();
                if len > 0 {
                    let cur = self.feed_sel as i32;
                    let next = (cur + delta).clamp(0, len as i32 - 1) as usize;
                    self.feed_sel = next;
                }
            }
            Panel::AlertsPanel => self.move_alerts(delta),
            Panel::Pane => self.move_pane(delta),
            panel => {
                let ids: Vec<String> = self
                    .panel_tasks(panel)
                    .into_iter()
                    .map(|t| t.id.clone())
                    .collect();
                if ids.is_empty() {
                    return;
                }
                let cur = self
                    .data
                    .tasks
                    .get(self.task_sel)
                    .and_then(|t| ids.iter().position(|id| id == &t.id))
                    .unwrap_or(0);
                let next = (cur as i32 + delta).clamp(0, ids.len() as i32 - 1) as usize;
                self.task_sel = self
                    .data
                    .tasks
                    .iter()
                    .position(|t| t.id == ids[next])
                    .unwrap_or(self.task_sel);
                self.sync_follow(workers);
            }
        }
    }

    fn move_alerts(&mut self, delta: i32) {
        if self.alerts.is_empty() {
            return;
        }
        let cur = self.alerts_sel as i32;
        self.alerts_sel = (cur + delta).clamp(0, self.alerts.len() as i32 - 1) as usize;
    }

    fn move_pane(&mut self, delta: i32) {
        let len = self.data.pane.as_ref().map_or(0, |p| p.lines.len());
        if len == 0 {
            return;
        }
        let cur = self.pane_sel as i32;
        self.pane_sel = (cur + delta).clamp(0, len as i32 - 1) as usize;
    }

    fn open_detail(&mut self) {
        match self.focused_panel() {
            Panel::Active | Panel::Queued | Panel::All => {
                if let Some(t) = self.selected_task() {
                    self.overlay = Overlay::TaskDetail(t.id.clone());
                    self.detail_scroll = 0;
                }
            }
            Panel::Feed => {
                if !self.feed.is_empty() {
                    self.overlay = Overlay::EventDetail(self.feed_sel);
                }
            }
            _ => {}
        }
    }

    fn open_follow(&mut self) {
        if let Some(t) = self.selected_task() {
            if t.assigned_pane_id.is_some() {
                self.overlay = Overlay::Follow;
                self.pane_sel = 0;
            }
        }
    }

    // -- derived display data ------------------------------------------------

    pub fn counts(&self) -> (usize, usize, usize) {
        // (in flight, queued, alerts) - overview metrics when available,
        // otherwise counted from the task list itself.
        if let Some(o) = &self.data.overview {
            let alerts = if o.metrics.active_alerts_count > 0 {
                o.metrics.active_alerts_count as usize
            } else {
                self.alerts.len()
            };
            return (
                o.metrics.active_tasks_count as usize,
                o.metrics.queued_tasks_count as usize,
                alerts,
            );
        }
        let active = self
            .data
            .tasks
            .iter()
            .filter(|t| t.state == "in_flight")
            .count();
        let queued = self
            .data
            .tasks
            .iter()
            .filter(|t| t.state == "queued")
            .count();
        (active, queued, self.alerts.len())
    }

    /// Epoch seconds for a task's start (overview since, else created_at).
    pub fn task_since_epoch(&self, task: &Task) -> Option<i64> {
        if let Some(o) = &self.data.overview {
            for a in &o.active_tasks {
                if a.id == task.id {
                    if let Some(since) = a.since.as_deref().and_then(crate::format::iso_epoch) {
                        return Some(since);
                    }
                }
            }
        }
        crate::format::iso_epoch(&task.created_at)
    }

    pub fn now_epoch() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    }
}

/// Pure connection-state rule, shared with tests.
///
/// - Connected: data arrived within the stale threshold and the transport
///   is answering.
/// - Stale: exactly one of the two signals is good (transport just dropped,
///   or link up but data older than the threshold).
/// - Disconnected: no data within the threshold and the transport is down.
pub fn conn_state(
    now: Instant,
    last_ok: Option<Instant>,
    attempt_ok: bool,
    stale_after: Duration,
) -> ConnState {
    match last_ok {
        None => ConnState::Disconnected,
        Some(t) => {
            let fresh = now.duration_since(t) <= stale_after;
            match (fresh, attempt_ok) {
                (true, true) => ConnState::Connected,
                (true, false) | (false, true) => ConnState::Stale,
                (false, false) => ConnState::Disconnected,
            }
        }
    }
}

fn alert_item(a: &Alert) -> AlertItem {
    let mut detail = a.message.clone();
    let created: String = a.created_at.chars().take(19).collect();
    detail.push_str(&strings::alert_meta(&a.category, &created));
    if let Some(key) = &a.key {
        detail.push_str(&strings::alert_key(key));
    }
    if !a.suggested_actions.is_empty() {
        detail.push_str(&strings::alert_suggestion(&a.suggested_actions.join("; ")));
    }
    AlertItem {
        severity: a.severity.clone(),
        title: a.title.clone(),
        detail,
        task_id: a.task_id.clone(),
    }
}

fn decision_item(d: &Decision) -> AlertItem {
    let who = d.id.as_deref().or(d.key.as_deref()).unwrap_or("decision");
    let verb = d.verb.as_deref().unwrap_or("hold");
    let summary = d.summary.as_deref().unwrap_or("");
    let mut detail = if summary.is_empty() {
        strings::decision_detail_bare(verb)
    } else {
        strings::decision_detail(summary, verb)
    };
    if let Some(owner) = d.owner.as_deref() {
        detail.push_str(&strings::decision_owner(owner));
    }
    AlertItem {
        severity: "critical".to_string(),
        title: strings::decision_title(who),
        detail,
        task_id: d.id.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Metrics;
    use serde_json::json;

    fn test_app() -> App {
        App::new(Cli::default())
    }

    fn task(id: &str, state: &str) -> Task {
        Task {
            id: id.into(),
            title: format!("title {id}"),
            description: None,
            state: state.into(),
            kind: "ship".into(),
            repo: "agentpulse".into(),
            worktree: None,
            branch: None,
            pr_url: None,
            harness: None,
            model: None,
            blocked_by: None,
            blocked_by_ids: vec![],
            hold_reason: None,
            hold_kind: None,
            assigned_pane_id: Some("w16:p0".into()),
            active_agent_session_id: None,
            created_at: "2026-10-18T10:00:00.000Z".into(),
            updated_at: "2026-10-18T10:36:11.000Z".into(),
        }
    }

    fn overview(tasks: Vec<Task>) -> Overview {
        let active = tasks
            .iter()
            .filter(|t| t.state == "in_flight")
            .map(|t| crate::model::ActiveTask {
                id: t.id.clone(),
                title: t.title.clone(),
                kind: t.kind.clone(),
                repo: t.repo.clone(),
                state: t.state.clone(),
                agent: Some("agy".into()),
                agent_status: Some("working".into()),
                since: Some("2026-10-18T10:30:00.000Z".into()),
                current_doing: Some("busy".into()),
            })
            .collect();
        Overview {
            generated_at: "2026-10-18T10:36:11.000Z".into(),
            metrics: Metrics {
                active_tasks_count: tasks.iter().filter(|t| t.state == "in_flight").count() as u32,
                queued_tasks_count: tasks.iter().filter(|t| t.state == "queued").count() as u32,
                completed_tasks_count: 0,
                active_alerts_count: 0,
                working_agents_count: 1,
            },
            active_tasks: active,
            pending_decisions: vec![],
            critical_alerts: vec![],
        }
    }

    fn event(seq: i64, severity: &str, kind: &str) -> Event {
        Event {
            event_id: format!("e{seq}"),
            seq,
            task_id: Some("agentpulse-tui".into()),
            pane_id: None,
            source: "firstmate".into(),
            kind: kind.into(),
            severity: severity.into(),
            timestamp: "2026-10-18T10:36:11.000Z".into(),
            payload: json!({"state": "working", "note": "n"})
                .as_object()
                .unwrap()
                .clone(),
        }
    }

    #[test]
    fn conn_state_matrix() {
        let now = Instant::now();
        let stale = Duration::from_secs(10);
        // Never connected.
        assert_eq!(conn_state(now, None, false, stale), ConnState::Disconnected);
        assert_eq!(conn_state(now, None, true, stale), ConnState::Disconnected);
        // Fresh + transport ok.
        assert_eq!(conn_state(now, Some(now), true, stale), ConnState::Connected);
        // Fresh but transport just dropped.
        assert_eq!(conn_state(now, Some(now), false, stale), ConnState::Stale);
        // Old data, transport ok again.
        let old = now - Duration::from_secs(30);
        assert_eq!(conn_state(now, Some(old), true, stale), ConnState::Stale);
        // Old data and transport down.
        assert_eq!(conn_state(now, Some(old), false, stale), ConnState::Disconnected);
    }

    #[test]
    fn view_switching() {
        let mut app = test_app();
        assert_eq!(app.view, View::Overview);
        app.switch_view(View::Tasks);
        assert_eq!(app.view, View::Tasks);
        assert_eq!(app.panels(), vec![Panel::All]);
        app.switch_view(View::Feed);
        assert_eq!(app.focused_panel(), Panel::Feed);
        app.switch_view(View::Alerts);
        assert_eq!(app.focused_panel(), Panel::AlertsPanel);
    }

    #[test]
    fn panels_per_layout() {
        let mut app = test_app();
        app.size = (80, 24);
        assert_eq!(
            app.panels(),
            vec![Panel::Active, Panel::Pane, Panel::Queued, Panel::AlertsPanel]
        );
        app.size = (160, 48);
        assert_eq!(app.panels(), vec![Panel::All, Panel::Pane, Panel::Feed]);
        app.size = (40, 10);
        assert_eq!(
            app.panels(),
            vec![Panel::Active, Panel::All, Panel::Pane, Panel::AlertsPanel]
        );
    }

    #[test]
    fn panel_task_filters() {
        let mut app = test_app();
        app.data.tasks = vec![
            task("a", "in_flight"),
            task("b", "queued"),
            task("c", "queued"),
        ];
        app.clamp_selection();
        assert_eq!(app.panel_tasks(Panel::Active).len(), 1);
        assert_eq!(app.panel_tasks(Panel::Queued).len(), 2);
        assert_eq!(app.panel_tasks(Panel::All).len(), 3);
    }

    #[test]
    fn ingest_dedups_and_follows_tail() {
        let mut app = test_app();
        app.ingest(vec![event(1, "info", "task_state_changed")]);
        assert_eq!(app.feed.len(), 1);
        assert_eq!(app.data.max_seq, 1);
        assert_eq!(app.feed_sel, 0); // tail
        // Duplicate/older seqs are dropped.
        app.ingest(vec![
            event(1, "info", "task_state_changed"),
            event(0, "info", "x"),
        ]);
        assert_eq!(app.feed.len(), 1);
        // Newer events arrive; selection stays on the tail.
        app.ingest(vec![event(2, "info", "task_state_changed")]);
        assert_eq!(app.feed.len(), 2);
        assert_eq!(app.feed_sel, 1);
        // User scrolls up; new events must not yank the selection.
        app.feed_sel = 0;
        app.ingest(vec![event(3, "info", "task_state_changed")]);
        assert_eq!(app.feed_sel, 0);
        assert_eq!(app.feed.len(), 3);
    }

    #[test]
    fn feed_cap_keeps_selection_sane() {
        let mut app = test_app();
        let batch: Vec<Event> = (1..=(FEED_CAP as i64 + 10))
            .map(|s| event(s, "info", "x"))
            .collect();
        app.ingest(batch);
        assert_eq!(app.feed.len(), FEED_CAP);
        assert_eq!(app.feed_sel, FEED_CAP - 1);
        assert_eq!(app.data.max_seq, FEED_CAP as i64 + 10);
    }

    #[test]
    fn alerts_compose_from_overview_and_events() {
        let mut app = test_app();
        app.data.overview = Some(Overview {
            generated_at: "2026-10-18T10:36:11.000Z".into(),
            metrics: Metrics {
                active_tasks_count: 1,
                queued_tasks_count: 0,
                completed_tasks_count: 0,
                active_alerts_count: 1,
                working_agents_count: 1,
            },
            active_tasks: vec![],
            pending_decisions: vec![Decision {
                id: Some("agentpulse-service".into()),
                key: Some("api-shape".into()),
                verb: Some("captain-hold".into()),
                summary: Some("Confirm terminal format".into()),
                owner: Some("captain".into()),
                extra: Default::default(),
            }],
            critical_alerts: vec![Alert {
                alert_id: "a1".into(),
                task_id: Some("agentpulse-tui".into()),
                category: "decision_required".into(),
                severity: "critical".into(),
                title: "Decision needed".into(),
                message: "Awaiting captain".into(),
                key: Some("api-shape".into()),
                suggested_actions: vec!["reply".into()],
                created_at: "2026-10-18T10:00:00.000Z".into(),
                status: "active".into(),
            }],
        });
        app.ingest(vec![event(5, "warning", "task_state_changed")]);
        app.rebuild_alerts();
        assert_eq!(app.alerts.len(), 3);
        // Critical items sort first.
        assert_eq!(app.alerts[0].severity, "critical");
        assert_eq!(app.alerts[0].title, "Decision needed");
        assert_eq!(app.alerts[1].title, "agentpulse-service 需要决策");
        assert_eq!(app.alerts[2].severity, "warning");
        assert!(app.alerts[0].detail.contains("建议: reply"));
    }

    #[test]
    fn counts_prefer_overview_metrics() {
        let mut app = test_app();
        app.data.overview = Some(overview(vec![task("a", "in_flight")]));
        app.data.tasks = vec![task("a", "in_flight"), task("b", "queued")];
        assert_eq!(app.counts(), (1, 0, 0));
    }

    #[test]
    fn task_duration_from_since() {
        let mut app = test_app();
        let t = task("a", "in_flight");
        app.data.overview = Some(overview(vec![t.clone()]));
        let since = app.task_since_epoch(&t).unwrap();
        assert_eq!(
            since,
            crate::format::iso_epoch("2026-10-18T10:30:00.000Z").unwrap()
        );
    }

    #[test]
    fn open_follow_requires_pane() {
        let mut app = test_app();
        let mut t = task("a", "in_flight");
        t.assigned_pane_id = None;
        app.data.tasks = vec![t];
        app.open_follow();
        assert_eq!(app.overlay, Overlay::None);
        app.data.tasks = vec![task("a", "in_flight")];
        app.open_follow();
        assert_eq!(app.overlay, Overlay::Follow);
    }

    #[test]
    fn clamp_selection_on_shrink() {
        let mut app = test_app();
        app.data.tasks = vec![task("a", "in_flight"), task("b", "queued")];
        app.task_sel = 5;
        app.clamp_selection();
        assert_eq!(app.task_sel, 1);
    }
}

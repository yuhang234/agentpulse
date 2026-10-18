//! Rendering: the 80x24 compact layout, the 160x48 wide layout, the
//! single-column fallback and the overlays (design report section 5.2).
//! Every line is pre-truncated to its panel width; nothing ever wraps.

use std::sync::OnceLock;
use std::time::Instant;

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::Modifier;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph};
use ratatui::Frame;

use crate::app::{App, ConnState, Overlay, Panel, View};
use crate::format::{
    fmt_duration, fmt_utc_datetime, sanitize_line, truncate_width,
};
use crate::layout::LayoutKind;
use crate::model::Task;
use crate::palette::Role;

const BRAND: &str = "AgentPulse · FirstMate+Herdr+Pi";
const WIDE_TITLE: &str = "AgentPulse 监控中心 (Debian TUI v1.0)";

pub fn render(f: &mut Frame, app: &App) {
    let area = f.area();
    let now = Instant::now();
    let kind = app.layout();

    let title = match kind {
        LayoutKind::Wide => WIDE_TITLE.to_string(),
        _ => format!("{BRAND} ({})", service_status(app)),
    };
    let block = Block::default()
        .borders(Borders::ALL)
        .border_style(app.palette.style(Role::Muted))
        .title(Span::styled(
            format!(" {title} "),
            app.palette.style(Role::Working),
        ));
    let inner = block.inner(area);
    f.render_widget(block, area);

    if inner.width < 6 || inner.height < 3 {
        // Degenerate terminal: render one honest status line, never panic.
        if inner.height >= 1 {
            let msg = truncate_width(
                &format!("{BRAND} · 窗口过小"),
                inner.width as usize,
            );
            f.render_widget(Paragraph::new(msg), inner);
        }
        return;
    }

    match app.view {
        View::Overview => match kind {
            LayoutKind::Wide => render_wide(f, app, inner, now),
            LayoutKind::Compact => render_compact(f, app, inner, now),
            LayoutKind::Fallback => render_fallback(f, app, inner, now),
        },
        View::Tasks | View::Feed | View::Alerts => {
            render_single_panel(f, app, inner, now)
        }
    }

    render_overlay(f, app, now);
}

// -- shared pieces ----------------------------------------------------------

fn span_width(s: &Span<'_>) -> usize {
    s.width()
}

/// Join a left group and a right-aligned group on one line.
fn line_with_right(mut left: Vec<Span<'static>>, right: Vec<Span<'static>>, width: usize) -> Line<'static> {
    let lw: usize = left.iter().map(span_width).sum();
    let rw: usize = right.iter().map(span_width).sum();
    let pad = width.saturating_sub(lw + rw);
    if pad > 0 {
        left.push(Span::raw(" ".repeat(pad)));
    }
    left.extend(right);
    Line::from(left)
}

/// Scroll offset that keeps the selected row visible (selection sits on the
/// last visible row once scrolled). Pure, so the UI is stateless.
pub fn scroll_for(sel: usize, len: usize, height: usize) -> usize {
    if height == 0 || len <= height {
        return 0;
    }
    let sel = sel.min(len - 1);
    if sel + 1 <= height {
        0
    } else {
        sel + 1 - height
    }
}

fn panel_block<'a>(title: String, focused: bool, app: &App) -> Block<'a> {
    let border = if focused {
        app.palette.style(Role::Focus)
    } else {
        app.palette.style(Role::Muted)
    };
    Block::default()
        .borders(Borders::ALL)
        .border_style(border)
        .title(Span::styled(title, app.palette.style(Role::Muted)))
}

fn hostname() -> &'static str {
    static HOST: OnceLock<String> = OnceLock::new();
    HOST.get_or_init(|| {
        if let Ok(h) = std::env::var("HOSTNAME") {
            let h = h.trim();
            if !h.is_empty() {
                return h.to_string();
            }
        }
        if let Ok(h) = std::fs::read_to_string("/proc/sys/kernel/hostname") {
            let h = h.trim();
            if !h.is_empty() {
                return h.to_string();
            }
        }
        "localhost".to_string()
    })
    .as_str()
}

/// The connection chip: state, transport mode, data age and latency.
fn conn_spans(app: &App, now: Instant) -> Vec<Span<'static>> {
    let state = app.conn_state(now);
    let (text, role) = match state {
        ConnState::Connected => ("● 已连接", Role::Success),
        ConnState::Stale => ("● 数据陈旧", Role::Warning),
        ConnState::Disconnected => ("○ 已断开", Role::Error),
    };
    let mode = if app.conn.ws_up {
        "实时流"
    } else if state == ConnState::Connected {
        "轮询"
    } else {
        "重连中"
    };
    let mut spans = vec![
        Span::styled(text.to_string(), app.palette.bold(role)),
        Span::raw(format!(" ({mode})")),
    ];
    if let Some(age) = app.last_ok_age(now) {
        spans.push(Span::styled(
            format!(" 数据 {age} 秒前"),
            app.palette.style(Role::Muted),
        ));
    }
    if let Some(rtt) = app.conn.rtt_ms {
        spans.push(Span::styled(
            format!(" 延迟 {rtt}ms"),
            app.palette.style(Role::Muted),
        ));
    }
    spans
}

fn service_status(app: &App) -> &'static str {
    match app.data.health.as_ref().map(|h| h.status.as_str()) {
        Some("healthy") => "Healthy",
        Some("starting") => "Starting",
        Some("degraded") => "Degraded",
        _ => "--",
    }
}

fn clock_spans(app: &App, wide: bool) -> Vec<Span<'static>> {
    let now = App::now_epoch();
    let text = if wide {
        format!("当前时间: {} UTC", fmt_utc_datetime(now))
    } else {
        fmt_utc_datetime(now)[11..19].to_string()
    };
    vec![Span::styled(text, app.palette.style(Role::Muted))]
}

fn view_tab_spans(app: &App) -> Vec<Span<'static>> {
    let counts = app.counts();
    let tabs = [
        ('1', "总览".to_string(), View::Overview),
        ('2', format!("任务({})", app.data.tasks.len()), View::Tasks),
        ('3', format!("事件流({})", app.feed.len()), View::Feed),
        ('4', format!("告警({})", counts.2), View::Alerts),
    ];
    let mut spans = Vec::new();
    for (key, label, view) in tabs {
        let active = app.view == view;
        let style = if active {
            app.palette.bold(Role::Working)
        } else {
            app.palette.style(Role::Muted)
        };
        spans.push(Span::styled(format!("[{key}]{label}"), style));
        spans.push(Span::raw("  "));
    }
    spans.pop(); // trailing separator
    spans
}

// -- compact layout (80x24) --------------------------------------------------

fn render_compact(f: &mut Frame, app: &App, inner: Rect, now: Instant) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),
            Constraint::Min(0),
            Constraint::Length(1),
        ])
        .split(inner);
    // Tab bar: views left, connection + clock right.
    let left = view_tab_spans(app);
    let mut right = conn_spans(app, now);
    right.push(Span::raw("  "));
    right.extend(clock_spans(app, false));
    let tabs = line_with_right(left, right, rows[0].width as usize);
    f.render_widget(Paragraph::new(tabs), rows[0]);

    let main = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Percentage(58), Constraint::Percentage(42)])
        .split(rows[1]);
    let top = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(main[0]);
    let bottom = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(main[1]);

    render_panel(f, app, top[0], Panel::Active, "◆ 活跃任务");
    render_panel(f, app, top[1], Panel::Pane, "◆ 最近日志 / 窗格输出");
    render_panel(f, app, bottom[0], Panel::Queued, "◆ 排队 Backlog");
    render_panel(f, app, bottom[1], Panel::AlertsPanel, "◆ 最新告警与决策");

    let footer = Paragraph::new(truncate_width(
        "[Tab]切换面板  [j/k]上下选择  [Enter]展开详情  [t]终端跟随  [a]告警  [r]重对齐  [?]帮助  [q]退出",
        rows[2].width as usize,
    ))
    .style(app.palette.style(Role::Muted));
    f.render_widget(footer, rows[2]);
}

// -- wide layout (160x48) ----------------------------------------------------

fn render_wide(f: &mut Frame, app: &App, inner: Rect, now: Instant) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),
            Constraint::Min(0),
            Constraint::Length(1),
        ])
        .split(inner);

    let counts = app.counts();
    let mut left = vec![
        Span::styled(format!("主机: {}", hostname()), app.palette.style(Role::Muted)),
        Span::raw(" │ "),
        Span::styled(format!("连接: {}", app.base.host), app.palette.style(Role::Muted)),
    ];
    left.extend(conn_spans(app, now));
    left.push(Span::raw(" │ "));
    let working = app
        .data
        .overview
        .as_ref()
        .map(|o| o.metrics.working_agents_count)
        .unwrap_or(0);
    left.push(Span::styled(
        format!(
            "在飞任务: {} │ 排队: {} │ 告警: {} │ 工作中 Agent: {}",
            counts.0, counts.1, counts.2, working
        ),
        app.palette.style(Role::Muted),
    ));
    let info = line_with_right(left, clock_spans(app, true), rows[0].width as usize);
    f.render_widget(Paragraph::new(info), rows[0]);

    let cols = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(26),
            Constraint::Percentage(48),
            Constraint::Percentage(26),
        ])
        .split(rows[1]);
    render_panel(f, app, cols[0], Panel::All, "任务流水线 (Tasks Pipeline)");
    render_panel(f, app, cols[1], Panel::Pane, "终端窗格实时映像 (Herdr Pane)");
    render_panel(f, app, cols[2], Panel::Feed, "实时事件动态 (Activity Feed)");

    let footer = Paragraph::new(truncate_width(
        "[1-4]视图切换  [Tab]遍历窗格  [j/k/↑/↓]导航  [Enter]打开详情浮层  [t]进入全屏终端跟随  [a]告警中心  [r]强制数据重对齐  [?]帮助  [q]退出",
        rows[2].width as usize,
    ))
    .style(app.palette.style(Role::Muted));
    f.render_widget(footer, rows[2]);
}

// -- fallback layout (< 80x24) ------------------------------------------------

fn render_fallback(f: &mut Frame, app: &App, inner: Rect, now: Instant) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),
            Constraint::Min(0),
            Constraint::Length(1),
        ])
        .split(inner);
    let left = view_tab_spans(app);
    let mut right = conn_spans(app, now);
    right.push(Span::raw("  "));
    right.extend(clock_spans(app, false));
    f.render_widget(
        Paragraph::new(line_with_right(left, right, rows[0].width as usize)),
        rows[0],
    );

    let body_h = rows[1].height as usize;
    let mut constraints = vec![Constraint::Length(3)];
    if body_h >= 10 {
        constraints.push(Constraint::Min(3)); // task pipeline
    }
    if body_h >= 14 {
        constraints.push(Constraint::Length(4)); // pane output
    }
    if body_h >= 18 {
        constraints.push(Constraint::Length(3)); // alerts
    }
    let sections = Layout::default()
        .direction(Direction::Vertical)
        .constraints(&constraints)
        .split(rows[1]);

    let mut idx = 0;
    render_panel(f, app, sections[idx], Panel::Active, "活跃任务");
    idx += 1;
    if body_h >= 10 {
        render_panel(f, app, sections[idx], Panel::All, "任务流水线");
        idx += 1;
    }
    if body_h >= 14 {
        render_panel(f, app, sections[idx], Panel::Pane, "窗格输出");
        idx += 1;
    }
    if body_h >= 18 {
        render_panel(f, app, sections[idx], Panel::AlertsPanel, "告警与决策");
    }

    let footer = Paragraph::new(truncate_width(
        "[1-4]视图 [Tab]面板 [j/k]导航 [Enter]详情 [a]告警 [?]帮助 [q]退出",
        rows[2].width as usize,
    ))
    .style(app.palette.style(Role::Muted));
    f.render_widget(footer, rows[2]);
}

// -- single-panel views (Tasks / Feed / Alerts) -------------------------------

fn render_single_panel(f: &mut Frame, app: &App, inner: Rect, now: Instant) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),
            Constraint::Min(0),
            Constraint::Length(1),
        ])
        .split(inner);
    let left = view_tab_spans(app);
    let mut right = conn_spans(app, now);
    right.push(Span::raw("  "));
    right.extend(clock_spans(app, false));
    f.render_widget(
        Paragraph::new(line_with_right(left, right, rows[0].width as usize)),
        rows[0],
    );

    let (panel, title) = match app.view {
        View::Tasks => (Panel::All, format!("任务流水线 ({})", app.data.tasks.len())),
        View::Feed => (Panel::Feed, format!("实时事件动态 ({})", app.feed.len())),
        View::Alerts => (Panel::AlertsPanel, format!("告警中心 ({})", app.alerts.len())),
        View::Overview => unreachable!(),
    };
    render_panel(f, app, rows[1], panel, &title);

    let footer = Paragraph::new(truncate_width(
        "[1-4]视图 [Tab]面板 [j/k]导航 [Enter]详情 [a]告警 [r]重对齐 [?]帮助 [q]退出",
        rows[2].width as usize,
    ))
    .style(app.palette.style(Role::Muted));
    f.render_widget(footer, rows[2]);
}

// -- panels -------------------------------------------------------------------

fn render_panel(f: &mut Frame, app: &App, area: Rect, panel: Panel, title: &str) {
    let focused = app.focused_panel() == panel;
    let block = panel_block(title.to_string(), focused, app);
    let inner = block.inner(area);
    f.render_widget(block, area);
    if inner.width == 0 || inner.height == 0 {
        return;
    }
    let width = inner.width as usize;
    let height = inner.height as usize;
    match panel {
        Panel::Active => {
            let lines = active_task_lines(app, width);
            f.render_widget(Paragraph::new(lines), inner);
        }
        Panel::Queued => {
            let lines = backlog_lines(app, width);
            f.render_widget(Paragraph::new(lines), inner);
        }
        Panel::All => {
            let lines = task_lines(app, width);
            let scroll = scroll_for(app.task_sel, lines.len().max(1), height);
            f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), inner);
        }
        Panel::Pane => {
            let lines = pane_lines(app, width);
            let scroll = scroll_for(app.pane_sel, lines.len().max(1), height);
            f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), inner);
        }
        Panel::Feed => {
            let lines = feed_lines(app, width);
            let scroll = scroll_for(app.feed_sel, lines.len().max(1), height);
            f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), inner);
        }
        Panel::AlertsPanel => {
            let lines = alerts_lines(app, width, Some(app.alerts_sel));
            let scroll = scroll_for(app.alerts_sel, lines.len().max(1), height);
            f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), inner);
        }
    }
}

/// The compact top-left panel: the active (selected in-flight) task.
fn active_task_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let task = app
        .selected_task()
        .filter(|t| t.state == "in_flight")
        .or_else(|| app.data.tasks.iter().find(|t| t.state == "in_flight"));
    let Some(task) = task else {
        return vec![muted_line(app, "（无在飞任务）")];
    };
    let active = app
        .data
        .overview
        .as_ref()
        .and_then(|o| o.active_tasks.iter().find(|a| a.id == task.id));
    let status = active
        .and_then(|a| a.agent_status.clone())
        .unwrap_or_else(|| "-".to_string());
    let status_role = crate::palette::Palette::agent_status_role(&status);
    let doing = active
        .and_then(|a| a.current_doing.clone())
        .unwrap_or_else(|| "-".to_string());
    let duration = app
        .task_since_epoch(task)
        .map(|since| fmt_duration(App::now_epoch() - since))
        .unwrap_or_else(|| "--:--:--".to_string());
    let kind = capitalize(&task.kind);
    let mut lines = vec![
        kv_line(app, "ID", &format!("{} [{kind}]", task.id), Role::Working),
        kv_line(
            app,
            "Repo",
            &format!(
                "{}   Harness: {}",
                task.repo,
                task.harness.as_deref().unwrap_or("-")
            ),
            Role::Muted,
        ),
        Line::from(vec![
            Span::styled("Status: ".to_string(), app.palette.style(Role::Muted)),
            Span::styled(status.to_uppercase(), app.palette.bold(status_role)),
            Span::raw(" ●"),
        ]),
        kv_line(app, "Doing", &doing, Role::Muted),
        kv_line(app, "Time", &duration, Role::Muted),
    ];
    for line in lines.iter_mut() {
        // Pre-truncate: no line may wrap across the layout.
        let text: String = line.spans.iter().map(|s| s.content.as_ref()).collect();
        if crate::format::display_width(&text) > width {
            let cut = truncate_width(&text, width);
            let style = line.spans[0].style;
            *line = Line::from(Span::styled(cut, style));
        }
    }
    lines
}

fn kv_line(app: &App, key: &str, value: &str, role: Role) -> Line<'static> {
    Line::from(vec![
        Span::styled(format!("{key}: "), app.palette.style(Role::Muted)),
        Span::styled(value.to_string(), app.palette.style(role)),
    ])
}

fn muted_line(app: &App, text: &str) -> Line<'static> {
    Line::from(Span::styled(text.to_string(), app.palette.style(Role::Muted)))
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// The queued backlog panel (compact bottom-left).
fn backlog_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let tasks: Vec<&Task> = app
        .data
        .tasks
        .iter()
        .filter(|t| t.state == "queued")
        .collect();
    if tasks.is_empty() {
        return vec![muted_line(app, "（无排队任务）")];
    }
    tasks
        .iter()
        .map(|t| {
            let blocked = t
                .blocked_by
                .as_deref()
                .map(|b| format!(" (blocked by {b})"))
                .unwrap_or_default();
            let text = truncate_width(&format!("• {}{blocked}", t.id), width);
            Line::from(Span::styled(text, app.palette.style(Role::Warning)))
        })
        .collect()
}

/// Task pipeline rows, with section headers when states are mixed.
fn task_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let tasks = app.panel_tasks(Panel::All);
    if tasks.is_empty() {
        return vec![muted_line(app, "（暂无任务）")];
    }
    let mut lines: Vec<Line<'static>> = Vec::new();
    let mut last_state: Option<&str> = None;
    for t in &tasks {
        if last_state != Some(t.state.as_str()) {
            if let Some(header) = state_header(app, &t.state) {
                lines.push(header);
            }
            last_state = Some(t.state.as_str());
        }
        let selected = app.selected_task().map(|s| s.id == t.id).unwrap_or(false);
        lines.extend(task_row(app, t, width, selected));
    }
    lines
}

fn state_header(app: &App, state: &str) -> Option<Line<'static>> {
    let (marker, label, role) = match state {
        "in_flight" => ("▶", "进行中 In Flight", Role::Working),
        "queued" => ("⏸", "排队 Queued", Role::Muted),
        "done" => ("✓", "已完成 Done", Role::Success),
        "blocked" => ("✗", "阻塞 Blocked", Role::Warning),
        "failed" => ("✗", "失败 Failed", Role::Error),
        "held" => ("⏸", "挂起 Held", Role::Warning),
        _ => return None,
    };
    Some(Line::from(Span::styled(
        format!("{marker} {label}"),
        app.palette.bold(role),
    )))
}

fn state_bullet(state: &str) -> &'static str {
    match state {
        "in_flight" => "●",
        "queued" => "•",
        "done" => "✓",
        "blocked" | "failed" => "✗",
        "held" => "⏸",
        _ => "·",
    }
}

fn task_row(app: &App, t: &Task, width: usize, selected: bool) -> Vec<Line<'static>> {
    let role = crate::palette::Palette::task_state_role(&t.state);
    let mut style = app.palette.style(role);
    if selected {
        style = style.add_modifier(Modifier::REVERSED);
    }
    let mut lines = vec![Line::from(Span::styled(
        truncate_width(&format!("{} {}", state_bullet(&t.state), t.id), width),
        style,
    ))];
    let mut detail = format!("  Kind: {}  Repo: {}", t.kind, t.repo);
    if t.state == "in_flight" {
        if let Some(since) = app.task_since_epoch(t) {
            detail.push_str(&format!("  Duration: {}", fmt_duration(App::now_epoch() - since)));
        }
        if let Some(h) = &t.harness {
            detail.push_str(&format!("  Harness: {h}"));
        }
    }
    if t.state == "queued" || t.state == "blocked" {
        if let Some(b) = &t.blocked_by {
            detail.push_str(&format!("  Blocked by: {b}"));
        }
    }
    let mut dstyle = app.palette.style(Role::Muted);
    if selected {
        dstyle = dstyle.add_modifier(Modifier::REVERSED);
    }
    lines.push(Line::from(Span::styled(
        truncate_width(&detail, width),
        dstyle,
    )));
    lines
}

/// Pane terminal output panel.
fn pane_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let Some(pane) = &app.data.pane else {
        return vec![muted_line(app, "（等待窗格输出…）")];
    };
    let mut lines: Vec<Line<'static>> = Vec::new();
    // Header: which task/pane these lines belong to.
    let followed = app.selected_task();
    if followed.map(|t| t.id != pane.task_id).unwrap_or(false) {
        lines.push(muted_line(app, &format!("（正在加载 {} 的窗格输出…）", pane.task_id)));
    }
    let agent = followed
        .and_then(|t| {
            app.data
                .overview
                .as_ref()
                .and_then(|o| o.active_tasks.iter().find(|a| a.id == t.id))
        })
        .and_then(|a| a.agent.clone())
        .or_else(|| followed.and_then(|t| t.harness.clone()))
        .unwrap_or_else(|| "-".to_string());
    let status = followed
        .and_then(|t| {
            app.data
                .overview
                .as_ref()
                .and_then(|o| o.active_tasks.iter().find(|a| a.id == t.id))
        })
        .and_then(|a| a.agent_status.clone())
        .unwrap_or_else(|| "unknown".to_string());
    let status_role = crate::palette::Palette::agent_status_role(&status);
    let pane_id = pane.pane_id.as_deref().unwrap_or("-");
    let cwd = followed
        .and_then(|t| t.worktree.clone())
        .unwrap_or_else(|| "-".to_string());
    lines.push(Line::from(vec![
        Span::styled("[CWD] ", app.palette.style(Role::Muted)),
        Span::styled(truncate_width(&cwd, width.saturating_sub(6)), app.palette.style(Role::Muted)),
    ]));
    lines.push(Line::from(vec![
        Span::styled("[Agent] ", app.palette.style(Role::Muted)),
        Span::raw(agent),
        Span::raw("  "),
        Span::styled("[Status] ", app.palette.style(Role::Muted)),
        Span::styled(status.to_uppercase(), app.palette.bold(status_role)),
        Span::raw("  "),
        Span::styled(format!("[Pane] {pane_id}"), app.palette.style(Role::Muted)),
    ]));
    lines.push(Line::from(Span::styled(
        "─".repeat(width.min(200)),
        app.palette.style(Role::Muted),
    )));
    if pane.lines.is_empty() {
        lines.push(muted_line(app, "（该任务无关联窗格或无输出）"));
        return lines;
    }
    for raw in &pane.lines {
        let clean = sanitize_line(raw);
        lines.push(Line::from(Span::raw(truncate_width(&clean, width))));
    }
    if pane.truncated {
        lines.push(muted_line(app, "（服务端输出已截断）"));
    }
    lines
}

/// Event feed rows, newest at the bottom, selection highlighted.
fn feed_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    if app.feed.is_empty() {
        return vec![muted_line(app, "（暂无事件）")];
    }
    app.feed
        .iter()
        .enumerate()
        .map(|(i, e)| {
            let role = crate::palette::Palette::severity_role(&e.severity);
            let mut style = app.palette.style(role);
            if i == app.feed_sel {
                style = style.add_modifier(Modifier::REVERSED);
            }
            let text = truncate_width(&crate::format::format_event_line(e), width);
            Line::from(Span::styled(text, style))
        })
        .collect()
}

/// Composed alerts and decisions rows.
fn alerts_lines(app: &App, width: usize, selected: Option<usize>) -> Vec<Line<'static>> {
    if app.alerts.is_empty() {
        return vec![muted_line(app, "（暂无 · 工作流运行正常）")];
    }
    app.alerts
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let role = crate::palette::Palette::severity_role(&item.severity);
            let mut style = app.palette.style(role);
            if Some(i) == selected {
                style = style.add_modifier(Modifier::REVERSED);
            }
            let text = truncate_width(&format!("{} {}", severity_mark(&item.severity), item.title), width);
            Line::from(Span::styled(text, style))
        })
        .collect()
}

fn severity_mark(severity: &str) -> &'static str {
    match severity {
        "critical" => "‼",
        "error" => "✗",
        "warning" => "!",
        _ => "·",
    }
}

// -- overlays ------------------------------------------------------------------

fn centered_rect(percent_x: u16, percent_y: u16, area: Rect) -> Rect {
    let v = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y + 1) / 2),
        ])
        .split(area);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x + 1) / 2),
        ])
        .split(v[1])[1]
}

fn render_overlay(f: &mut Frame, app: &App, now: Instant) {
    let area = f.area();
    match app.overlay.clone() {
        Overlay::None => {}
        Overlay::Help => {
            let rect = centered_rect(64, 80, area);
            f.render_widget(Clear, rect);
            let block = panel_block("帮助 (?)".to_string(), false, app);
            let inner = block.inner(rect);
            f.render_widget(block, rect);
            let lines = help_lines(app, inner.width as usize);
            f.render_widget(Paragraph::new(lines), inner);
        }
        Overlay::Alerts => {
            let rect = centered_rect(80, 80, area);
            f.render_widget(Clear, rect);
            let block = panel_block(
                format!("告警中心 ({})", app.alerts.len()),
                false,
                app,
            );
            let inner = block.inner(rect);
            f.render_widget(block, rect);
            let height = inner.height as usize;
            let mut lines = alerts_lines(app, inner.width as usize, Some(app.alerts_sel));
            // Add the detail line under each title.
            let mut with_detail: Vec<Line<'static>> = Vec::new();
            for (i, line) in lines.drain(..).enumerate() {
                with_detail.push(line);
                if let Some(item) = app.alerts.get(i) {
                    let mut style = app.palette.style(Role::Muted);
                    if Some(i) == Some(app.alerts_sel) {
                        style = style.add_modifier(Modifier::REVERSED);
                    }
                    with_detail.push(Line::from(Span::styled(
                        truncate_width(&format!("  {}", item.detail), inner.width as usize),
                        style,
                    )));
                }
            }
            let scroll = scroll_for(app.alerts_sel * 2, with_detail.len().max(1), height);
            let footer = Line::from(Span::styled(
                "[j/k]选择 [r]重对齐 [Esc]关闭",
                app.palette.style(Role::Muted),
            ));
            with_detail.push(footer);
            f.render_widget(
                Paragraph::new(with_detail).scroll((scroll as u16, 0)),
                inner,
            );
        }
        Overlay::TaskDetail(id) => {
            let rect = centered_rect(76, 84, area);
            f.render_widget(Clear, rect);
            let block = panel_block(format!("任务详情 · {id}"), false, app);
            let inner = block.inner(rect);
            f.render_widget(block, rect);
            let task = app.data.tasks.iter().find(|t| t.id == id);
            let lines = task
                .map(|t| detail_lines(app, t, inner.width as usize))
                .unwrap_or_else(|| vec![muted_line(app, "（任务已不存在）")]);
            let scroll = app.detail_scroll as usize;
            f.render_widget(Paragraph::new(lines).scroll((scroll as u16, 0)), inner);
        }
        Overlay::EventDetail(idx) => {
            let rect = centered_rect(76, 84, area);
            f.render_widget(Clear, rect);
            let block = panel_block("事件详情".to_string(), false, app);
            let inner = block.inner(rect);
            f.render_widget(block, rect);
            let lines = match app.feed.get(idx) {
                Some(e) => event_detail_lines(app, e, inner.width as usize),
                None => vec![muted_line(app, "（事件已滚出 Feed）")],
            };
            f.render_widget(Paragraph::new(lines), inner);
        }
        Overlay::Follow => {
            let rect = centered_rect(96, 94, area);
            f.render_widget(Clear, rect);
            let followed = app.selected_task();
            let pane_id = app
                .data
                .pane
                .as_ref()
                .and_then(|p| p.pane_id.clone())
                .unwrap_or_else(|| "-".to_string());
            let title = match followed {
                Some(t) => format!("终端跟随 · {} · {pane_id}", t.id),
                None => "终端跟随".to_string(),
            };
            let block = panel_block(title, false, app);
            let inner = block.inner(rect);
            f.render_widget(block, rect);
            let lines = pane_lines(app, inner.width as usize);
            let height = inner.height as usize;
            let scroll = scroll_for(app.pane_sel, lines.len().max(1), height);
            let mut all = lines;
            all.push(Line::from(Span::styled(
                "[t/Esc]退出跟随  [j/k]滚动  [r]立即刷新  （每 2 秒自动刷新）",
                app.palette.style(Role::Muted),
            )));
            f.render_widget(Paragraph::new(all).scroll((scroll as u16, 0)), inner);
        }
    }
    let _ = now;
}

fn help_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let rows = [
        ("1 / 2 / 3 / 4", "切换视图：总览 / 任务 / 事件流 / 告警"),
        ("Tab / Shift+Tab", "遍历面板焦点"),
        ("j / k / ↑ / ↓", "在焦点面板内移动选择"),
        ("Enter", "打开详情浮层（任务或事件）"),
        ("t", "进入全屏终端跟随（需任务关联 Herdr 窗格）"),
        ("a", "打开告警中心"),
        ("r", "强制数据重对齐（立即刷新 + 重连事件流）"),
        ("?", "显示本帮助"),
        ("q / Ctrl-C", "退出"),
    ];
    let mut lines: Vec<Line<'static>> = vec![Line::from(Span::styled(
        "按键说明",
        app.palette.bold(Role::Working),
    ))];
    for (key, desc) in rows {
        lines.push(Line::from(vec![
            Span::styled(format!("{key:<16}"), app.palette.style(Role::Focus)),
            Span::raw(truncate_width(desc, width.saturating_sub(18))),
        ]));
    }
    lines.push(Line::from(Span::raw("")));
    lines.push(Line::from(Span::styled(
        "连接状态",
        app.palette.bold(Role::Working),
    )));
    lines.push(muted_line(
        app,
        "● 已连接：数据新鲜且传输正常（实时流或轮询）",
    ));
    lines.push(muted_line(
        app,
        "● 数据陈旧：链路或数据超期，屏幕保留最后一次已知数据",
    ));
    lines.push(muted_line(
        app,
        "○ 已断开：服务不可达，按 1/2/4/8/16/30 秒退避重连",
    ));
    lines.push(Line::from(Span::raw("")));
    if let Some(h) = &app.data.health {
        lines.push(Line::from(Span::styled(
            format!(
                "服务: v{} · 运行 {} 秒 · 采集器: {} · 已连客户端: {}",
                h.version,
                h.uptime_seconds,
                h.collectors
                    .iter()
                    .map(|(name, c)| match c.detail.as_deref() {
                        Some(d) if c.status != "ok" => format!("{name} {} ({})", c.status, d),
                        _ => format!("{name} {}", c.status),
                    })
                    .collect::<Vec<_>>()
                    .join(" · "),
                h.clients_connected
            ),
            app.palette.style(Role::Muted),
        )));
    }
    if let Some(o) = &app.data.overview {
        lines.push(muted_line(app, &format!("Overview 生成于 {}", o.generated_at)));
    }
    if let Some(h) = &app.data.health {
        lines.push(muted_line(app, &format!("服务时钟: {}", h.timestamp)));
    }
    lines.push(Line::from(Span::raw("")));
    lines.push(muted_line(
        app,
        "本客户端只读：仅观察 FirstMate / Herdr / Pi，不做任何控制。",
    ));
    lines
}

fn or_dash(v: &Option<String>) -> &str {
    v.as_deref().unwrap_or("-")
}

fn detail_lines(app: &App, t: &Task, width: usize) -> Vec<Line<'static>> {
    let state_role = crate::palette::Palette::task_state_role(&t.state);
    let mut lines = vec![
        kv_line(app, "ID", &t.id, Role::Working),
        kv_line(app, "标题", &t.title, Role::Muted),
        Line::from(vec![
            Span::styled("状态: ", app.palette.style(Role::Muted)),
            Span::styled(t.state.clone(), app.palette.bold(state_role)),
            Span::raw(format!("    类型: {}    仓库: {}", t.kind, t.repo)),
        ]),
        kv_line(
            app,
            "Harness",
            &format!("{}    模型: {}", or_dash(&t.harness), or_dash(&t.model)),
            Role::Muted,
        ),
        kv_line(app, "分支", or_dash(&t.branch), Role::Muted),
        kv_line(app, "PR", or_dash(&t.pr_url), Role::Success),
        kv_line(app, "工作树", or_dash(&t.worktree), Role::Muted),
        kv_line(app, "关联窗格", or_dash(&t.assigned_pane_id), Role::Working),
        kv_line(
            app,
            "Pi 会话",
            or_dash(&t.active_agent_session_id),
            Role::Muted,
        ),
        kv_line(
            app,
            strings::D_BLOCKED,
            &{
                let mut s = t.blocked_by.clone().unwrap_or_else(|| strings::DASH.to_string());
                if !t.blocked_by_ids.is_empty() {
                    s.push_str(&strings::d_blocked_all(&t.blocked_by_ids.join(", ")));
                }
                s
            },
            Role::Warning,
        ),
        kv_line(
            app,
            "挂起",
            &match (&t.hold_reason, &t.hold_kind) {
                (Some(r), Some(k)) => format!("{r} ({k})"),
                (Some(r), None) => r.clone(),
                _ => "-".to_string(),
            },
            Role::Warning,
        ),
        kv_line(app, "创建", &t.created_at, Role::Muted),
        kv_line(app, "更新", &t.updated_at, Role::Muted),
    ];
    if let Some(desc) = &t.description {
        lines.push(kv_line(app, "描述", desc, Role::Muted));
    }
    if let Some(since) = app.task_since_epoch(t) {
        lines.push(kv_line(
            app,
            "已运行",
            &fmt_duration(App::now_epoch() - since),
            Role::Working,
        ));
    }
    lines
        .into_iter()
        .map(|l| {
            let text: String = l.spans.iter().map(|s| s.content.as_ref()).collect();
            if crate::format::display_width(&text) > width {
                let style = l.spans[0].style;
                Line::from(Span::styled(truncate_width(&text, width), style))
            } else {
                l
            }
        })
        .collect()
}

fn event_detail_lines(app: &App, e: &crate::model::Event, width: usize) -> Vec<Line<'static>> {
    let severity_role = crate::palette::Palette::severity_role(&e.severity);
    let mut lines = vec![
        kv_line(app, "事件ID", &e.event_id, Role::Muted),
        kv_line(app, "序号", &e.seq.to_string(), Role::Muted),
        kv_line(app, "时间", &e.timestamp, Role::Muted),
        Line::from(vec![
            Span::styled("来源: ", app.palette.style(Role::Muted)),
            Span::raw(e.source.clone()),
            Span::raw("    类型: "),
            Span::raw(e.kind.clone()),
        ]),
        Line::from(vec![
            Span::styled("严重度: ", app.palette.style(Role::Muted)),
            Span::styled(e.severity.clone(), app.palette.bold(severity_role)),
        ]),
        kv_line(app, "任务", e.task_id.as_deref().unwrap_or("-"), Role::Muted),
        kv_line(app, "窗格", e.pane_id.as_deref().unwrap_or("-"), Role::Muted),
        Line::from(Span::styled("载荷:", app.palette.style(Role::Muted))),
    ];
    let pretty = serde_json::to_string_pretty(&e.payload).unwrap_or_default();
    for line in pretty.lines() {
        lines.push(Line::from(Span::styled(
            line.to_string(),
            app.palette.style(Role::Muted),
        )));
    }
    lines
        .into_iter()
        .map(|l| {
            let text: String = l.spans.iter().map(|s| s.content.as_ref()).collect();
            if crate::format::display_width(&text) > width {
                let style = l.spans[0].style;
                Line::from(Span::styled(truncate_width(&text, width), style))
            } else {
                l
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::App;
    use crate::cli::Cli;
    use crate::model::{Event, Task};
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;
    use serde_json::json;
    use std::collections::VecDeque;

    fn app_at(w: u16, h: u16) -> App {
        let mut app = App::new(Cli::default());
        app.size = (w, h);
        app
    }

    fn sample_task(id: &str, state: &str) -> Task {
        Task {
            id: id.into(),
            title: format!("title {id}"),
            description: Some("do the thing".into()),
            state: state.into(),
            kind: "ship".into(),
            repo: "agentpulse".into(),
            worktree: Some("/home/Yuhang/.treehouse/agentpulse/1/agentpulse".into()),
            branch: Some("fm/agentpulse-tui".into()),
            pr_url: None,
            harness: Some("codebuddy".into()),
            model: Some("step-5-preview".into()),
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

    fn render_to_string(app: &App, w: u16, h: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(w, h)).unwrap();
        terminal.draw(|f| render(f, app)).unwrap();
        let buffer = terminal.backend().buffer().clone();
        let mut out = String::new();
        for y in 0..h {
            for x in 0..w {
                out.push_str(buffer.get(x, y).symbol());
            }
            out.push('\n');
        }
        out
    }

    fn sample_app() -> App {
        let mut app = app_at(160, 48);
        app.data.tasks = vec![
            sample_task("agentpulse-tui", "in_flight"),
            sample_task("agentpulse-android", "queued"),
        ];
        app.data.pane = Some(crate::app::PaneView {
            task_id: "agentpulse-tui".into(),
            pane_id: Some("w16:p0".into()),
            lines: vec![
                "> esc to cancel".into(),
                "Gemini 3.8 Flash · high".into(),
            ],
            truncated: false,
        });
        let mut feed = VecDeque::new();
        feed.push_back(Event {
            event_id: "e1".into(),
            seq: 1,
            task_id: Some("agentpulse-tui".into()),
            pane_id: None,
            source: "firstmate".into(),
            kind: "task_state_changed".into(),
            severity: "info".into(),
            timestamp: "2026-10-18T10:36:11.000Z".into(),
            payload: json!({"state": "working", "note": "starting"}).as_object().unwrap().clone(),
        });
        app.feed = feed;
        app
    }

    #[test]
    fn scroll_for_keeps_selection_visible() {
        assert_eq!(scroll_for(0, 10, 5), 0);
        assert_eq!(scroll_for(4, 10, 5), 0);
        assert_eq!(scroll_for(5, 10, 5), 1);
        assert_eq!(scroll_for(9, 10, 5), 5);
        assert_eq!(scroll_for(0, 3, 5), 0);
        assert_eq!(scroll_for(2, 3, 0), 0);
    }

    #[test]
    fn no_panic_at_odd_sizes() {
        for (w, h) in [(1u16, 1u16), (2, 2), (5, 3), (10, 4), (40, 10), (79, 23), (80, 24), (120, 40), (159, 47), (160, 48), (200, 60)] {
            let mut app = sample_app();
            app.size = (w, h);
            let _ = render_to_string(&app, w, h);
        }
    }

    #[test]
    fn compact_layout_shows_brand_and_panels() {
        let app = sample_app();
        app_at(80, 24);
        let text = render_to_string(&app, 80, 24);
        assert!(text.contains("AgentPulse"));
        assert!(text.contains("活跃任务"));
        assert!(text.contains("排队"));
        assert!(text.contains("agentpulse-tui"));
        assert!(text.contains("切换面板"));
    }

    #[test]
    fn wide_layout_shows_three_columns() {
        let app = sample_app();
        let text = render_to_string(&app, 160, 48);
        assert!(text.contains("监控中心"));
        assert!(text.contains("任务流水线"));
        assert!(text.contains("终端窗格"));
        assert!(text.contains("实时事件"));
        assert!(text.contains("w16:p0"));
        assert!(text.contains("[FM]"));
    }

    #[test]
    fn empty_app_renders_placeholders() {
        let app = app_at(80, 24);
        let text = render_to_string(&app, 80, 24);
        assert!(text.contains("无在飞任务") || text.contains("暂无"));
    }

    #[test]
    fn overlays_render() {
        let mut app = sample_app();
        app.overlay = Overlay::Help;
        let text = render_to_string(&app, 80, 24);
        assert!(text.contains("帮助"));
        app.overlay = Overlay::TaskDetail("agentpulse-tui".into());
        let text = render_to_string(&app, 80, 24);
        assert!(text.contains("任务详情"));
        assert!(text.contains("fm/agentpulse-tui"));
        app.overlay = Overlay::Follow;
        let text = render_to_string(&app, 80, 24);
        assert!(text.contains("终端跟随"));
    }

    #[test]
    fn long_lines_never_wrap() {
        let mut app = sample_app();
        app.data.pane = Some(crate::app::PaneView {
            task_id: "agentpulse-tui".into(),
            pane_id: Some("w16:p0".into()),
            lines: vec!["x".repeat(500)],
            truncated: false,
        });
        let text = render_to_string(&app, 80, 24);
        // Every rendered row must be exactly 80 cells wide.
        for line in text.lines() {
            assert_eq!(line.chars().count(), 80, "row not truncated: {line:?}");
        }
    }
}

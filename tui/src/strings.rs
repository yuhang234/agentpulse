//! Every user-visible string in one place (steering message 001).
//!
//! Interface copy is Simplified Chinese; paths, log lines, identifiers and
//! terminal output stay English. Theme decisions live in palette.rs and
//! layout selection in layout.rs, so a design-doc wording change lands here
//! without touching the render layer. Dynamic text is a function because
//! format! requires a literal template.

#![allow(dead_code)]

// -- brand and window ---------------------------------------------------------

pub const BRAND: &str = "AgentPulse · FirstMate+Herdr+Pi";
pub const WIDE_TITLE: &str = "AgentPulse 监控中心 (Debian TUI v1.0)";

/// Compact title bar, e.g. AgentPulse · FirstMate+Herdr+Pi (Healthy).
pub fn brand_title(service_status: &str) -> String {
    format!("{BRAND} ({service_status})")
}

pub const WINDOW_TOO_SMALL: &str = "AgentPulse · 窗口过小";

// -- service status -----------------------------------------------------------

pub const SVC_HEALTHY: &str = "Healthy";
pub const SVC_STARTING: &str = "Starting";
pub const SVC_DEGRADED: &str = "Degraded";
pub const SVC_UNKNOWN: &str = "--";

// -- connection chip ----------------------------------------------------------

pub const CONN_CONNECTED: &str = "● 已连接";
pub const CONN_STALE: &str = "● 数据陈旧";
pub const CONN_DISCONNECTED: &str = "○ 已断开";
pub const MODE_LIVE: &str = "实时流";
pub const MODE_POLL: &str = "轮询";
pub const MODE_RECONNECT: &str = "重连中";
pub fn data_age(age: u64) -> String {
    format!(" 数据 {age} 秒前")
}
pub fn rtt(ms: u64) -> String {
    format!(" 延迟 {ms}ms")
}

// -- tab bar ------------------------------------------------------------------

pub const TAB_OVERVIEW: &str = "总览";
pub const TAB_TASKS: &str = "任务";
pub const TAB_FEED: &str = "事件流";
pub const TAB_ALERTS: &str = "告警";

// -- panel titles -------------------------------------------------------------

pub const PANEL_ACTIVE: &str = "◆ 活跃任务";
pub const PANEL_LOG: &str = "◆ 最近日志 / 窗格输出";
pub const PANEL_BACKLOG: &str = "◆ 排队 Backlog";
pub const PANEL_ALERTS: &str = "◆ 最新告警与决策";
pub const PANEL_TASKS: &str = "任务流水线 (Tasks Pipeline)";
pub const PANEL_PANE: &str = "终端窗格实时映像 (Herdr Pane)";
pub const PANEL_FEED: &str = "实时事件动态 (Activity Feed)";
pub const PANEL_TASKS_VIEW: &str = "任务流水线";
pub const PANEL_PANE_FALLBACK: &str = "窗格输出";
pub const PANEL_ALERTS_FALLBACK: &str = "告警与决策";
pub const PANEL_ACTIVE_FALLBACK: &str = "活跃任务";

pub fn tasks_view_title(total: usize) -> String {
    format!("任务流水线 ({total})")
}
pub fn feed_view_title(total: usize) -> String {
    format!("实时事件动态 ({total})")
}
pub fn alerts_view_title(total: usize) -> String {
    format!("告警中心 ({total})")
}

// -- footers ------------------------------------------------------------------

pub const FOOTER_COMPACT: &str = "[Tab]切换面板  [j/k]上下选择  [Enter]展开详情  [t]终端跟随  [a]告警  [r]重对齐  [?]帮助  [q]退出";
pub const FOOTER_WIDE: &str = "[1-4]视图切换  [Tab]遍历窗格  [j/k/↑/↓]导航  [Enter]打开详情浮层  [t]进入全屏终端跟随  [a]告警中心  [r]强制数据重对齐  [?]帮助  [q]退出";
pub const FOOTER_FALLBACK: &str = "[1-4]视图 [Tab]面板 [j/k]导航 [Enter]详情 [a]告警 [?]帮助 [q]退出";
pub const FOOTER_SINGLE: &str = "[1-4]视图 [Tab]面板 [j/k]导航 [Enter]详情 [a]告警 [r]重对齐 [?]帮助 [q]退出";
pub const FOOTER_ALERTS_OVERLAY: &str = "[j/k]选择 [r]重对齐 [Esc]关闭";
pub const FOOTER_FOLLOW: &str = "[t/Esc]退出跟随  [j/k]滚动  [r]立即刷新  （每 2 秒自动刷新）";

// -- info bar -----------------------------------------------------------------

pub const INFO_HOST: &str = "主机: ";
pub const INFO_CONN: &str = "连接: ";
pub fn info_counts(active: usize, queued: usize, alerts: usize, working: u32) -> String {
    format!("在飞任务: {active} │ 排队: {queued} │ 告警: {alerts} │ 工作中 Agent: {working}")
}
pub fn info_time(time: &str) -> String {
    format!("当前时间: {time} UTC")
}
pub const PANE_LABEL_CWD: &str = "[CWD] ";
pub const PANE_LABEL_AGENT: &str = "[Agent] ";
pub const PANE_LABEL_STATUS: &str = "[Status] ";
pub const PANE_LABEL_PANE: &str = "[Pane] ";

// -- empty states -------------------------------------------------------------

pub const NO_ACTIVE_TASK: &str = "（无在飞任务）";
pub const NO_QUEUED_TASK: &str = "（无排队任务）";
pub const NO_TASKS: &str = "（暂无任务）";
pub const NO_EVENTS: &str = "（暂无事件）";
pub const NO_ALERTS: &str = "（暂无 · 工作流运行正常）";
pub const PANE_WAITING: &str = "（等待窗格输出…）";
pub const PANE_EMPTY: &str = "（该任务无关联窗格或无输出）";
pub fn pane_loading(task: &str) -> String {
    format!("（正在加载 {task} 的窗格输出…）")
}
pub const PANE_TRUNCATED: &str = "（服务端输出已截断）";
pub const TASK_GONE: &str = "（任务已不存在）";
pub const EVENT_GONE: &str = "（事件已滚出 Feed）";

// -- task rows ----------------------------------------------------------------

pub const SECTION_IN_FLIGHT: &str = "▶ 进行中 In Flight";
pub const SECTION_QUEUED: &str = "⏸ 排队 Queued";
pub const SECTION_DONE: &str = "✓ 已完成 Done";
pub const SECTION_BLOCKED: &str = "✗ 阻塞 Blocked";
pub const SECTION_FAILED: &str = "✗ 失败 Failed";
pub const SECTION_HELD: &str = "⏸ 挂起 Held";
pub fn row_detail(kind: &str, repo: &str) -> String {
    format!("  Kind: {kind}  Repo: {repo}")
}
pub fn row_duration(duration: &str) -> String {
    format!("  Duration: {duration}")
}
pub fn row_harness(harness: &str) -> String {
    format!("  Harness: {harness}")
}
pub fn row_blocked_by(blocked_by: &str) -> String {
    format!("  Blocked by: {blocked_by}")
}
pub const ACTIVE_ID: &str = "ID";
pub const ACTIVE_REPO: &str = "Repo";
pub const ACTIVE_STATUS: &str = "Status";
pub const ACTIVE_DOING: &str = "Doing";
pub const ACTIVE_TIME: &str = "Time";
pub fn active_harness_inline(harness: &str) -> String {
    format!("   Harness: {harness}")
}

// -- alerts -------------------------------------------------------------------

pub const SEV_CRITICAL: &str = "‼";
pub const SEV_ERROR: &str = "✗";
pub const SEV_WARNING: &str = "!";
pub const SEV_INFO: &str = "·";

// -- overlays -----------------------------------------------------------------

pub const HELP_TITLE: &str = "帮助 (?)";
pub const HELP_KEYS_HEADING: &str = "按键说明";
pub const HELP_CONN_HEADING: &str = "连接状态";
pub const HELP_CONN_CONNECTED: &str = "● 已连接：数据新鲜且传输正常（实时流或轮询）";
pub const HELP_CONN_STALE: &str = "● 数据陈旧：链路或数据超期，屏幕保留最后一次已知数据";
pub const HELP_CONN_DISCONNECTED: &str = "○ 已断开：服务不可达，按 1/2/4/8/16/30 秒退避重连";
pub const HELP_READONLY: &str = "本客户端只读：仅观察 FirstMate / Herdr / Pi，不做任何控制。";
pub fn help_service(version: &str, uptime: u64, collectors: &str, clients: u32) -> String {
    format!("服务: v{version} · 运行 {uptime} 秒 · 采集器: {collectors} · 已连客户端: {clients}")
}
pub fn help_overview_at(at: &str) -> String {
    format!("Overview 生成于 {at}")
}
pub fn help_service_clock(clock: &str) -> String {
    format!("服务时钟: {clock}")
}
pub fn alerts_overlay_title(count: usize) -> String {
    format!("告警中心 ({count})")
}
pub fn task_detail_title(id: &str) -> String {
    format!("任务详情 · {id}")
}
pub const EVENT_DETAIL_TITLE: &str = "事件详情";
pub fn follow_title(task: &str, pane: &str) -> String {
    format!("终端跟随 · {task} · {pane}")
}
pub const FOLLOW_TITLE_BARE: &str = "终端跟随";

// -- detail overlays ----------------------------------------------------------

pub const D_ID: &str = "ID";
pub const D_TITLE: &str = "标题";
pub const D_STATE: &str = "状态";
pub fn d_kind_repo(kind: &str, repo: &str) -> String {
    format!("    类型: {kind}    仓库: {repo}")
}
pub fn d_harness_model(harness: &str, model: &str) -> String {
    format!("{harness}    模型: {model}")
}
pub const D_BRANCH: &str = "分支";
pub const D_PR: &str = "PR";
pub const D_WORKTREE: &str = "工作树";
pub const D_PANE: &str = "关联窗格";
pub const D_SESSION: &str = "Pi 会话";
pub const D_BLOCKED: &str = "阻塞";
pub fn d_blocked_all(ids: &str) -> String {
    format!(" (全部: {ids})")
}
pub const D_HOLD: &str = "挂起";
pub const D_CREATED: &str = "创建";
pub const D_UPDATED: &str = "更新";
pub const D_DESC: &str = "描述";
pub const D_RUNNING: &str = "已运行";
pub const E_ID: &str = "事件ID";
pub const E_SEQ: &str = "序号";
pub const E_TIME: &str = "时间";
pub fn e_source_kind(kind: &str) -> String {
    format!("    类型: {kind}")
}
pub const E_SEVERITY: &str = "严重度";
pub const E_TASK: &str = "任务";
pub const E_PANE: &str = "窗格";
pub const E_PAYLOAD: &str = "载荷:";

// -- composed alert details ---------------------------------------------------

pub fn alert_suggestion(actions: &str) -> String {
    format!(" · 建议: {actions}")
}
pub fn alert_meta(category: &str, created: &str) -> String {
    format!(" · {category} · {created}")
}
pub fn alert_key(key: &str) -> String {
    format!(" · key: {key}")
}
pub fn decision_title(who: &str) -> String {
    format!("{who} 需要决策")
}
pub fn decision_detail(summary: &str, verb: &str) -> String {
    format!("{summary} (verb: {verb})")
}
pub fn decision_detail_bare(verb: &str) -> String {
    format!("verb: {verb}")
}
pub fn decision_owner(owner: &str) -> String {
    format!(", owner: {owner}")
}

// -- event feed text (format.rs) ----------------------------------------------
// These mirror the payload shapes the service really emits.

pub fn evt_state(task: &str, state: &str) -> String {
    format!("{task} -> {state}")
}
pub fn evt_state_note(task: &str, state: &str, note: &str) -> String {
    format!("{task} -> {state} · {note}")
}
pub fn evt_decision(task: &str) -> String {
    format!("{task} 需要决策")
}
pub fn evt_decision_note(task: &str, note: &str) -> String {
    format!("{task} 需要决策 · {note}")
}
pub fn evt_completed(task: &str) -> String {
    format!("{task} 完成")
}
pub fn evt_failed(task: &str) -> String {
    format!("{task} 失败")
}
pub fn evt_failed_note(task: &str, note: &str) -> String {
    format!("{task} 失败 · {note}")
}
pub fn evt_status_prev(pane: &str, prev: &str, cur: &str) -> String {
    format!("{pane} {prev}->{cur}")
}
pub fn evt_status_cur(pane: &str, cur: &str) -> String {
    format!("{pane} status: {cur}")
}
pub fn evt_pane_lines(pane: &str, count: usize) -> String {
    format!("{pane} 输出 +{count} 行")
}
pub fn evt_pane_matched(pane: &str, matched: &str) -> String {
    format!("{pane} {matched}")
}
pub fn evt_pane_plain(pane: &str) -> String {
    format!("{pane} 输出")
}
pub fn evt_tool_call(tool: &str, command: &str) -> String {
    format!("{tool} {command}")
}
pub fn evt_tool_call_bare(tool: &str) -> String {
    format!("{tool} 调用")
}
pub fn evt_tool_err(tool: &str, first: &str) -> String {
    format!("{tool} ERR {first}")
}
pub fn evt_tool_ok(tool: &str, first: &str) -> String {
    format!("{tool} ok {first}")
}
pub fn evt_thinking(channel: &str, first: &str) -> String {
    format!("{channel}: {first}")
}
pub const DASH: &str = "-";

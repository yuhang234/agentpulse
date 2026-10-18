/* ============================================================================
   AgentPulse 原型 · 模拟数据层
   ---------------------------------------------------------------
   全部内容均为模拟：用于呈现 design/spec.md 中定义的界面状态与排版，
   不连接任何真实服务，也不派生自任何真实运行中的任务。
   字段取值刻意沿用 spec 第 2/3 章的示例数据（任务 ID、窗格 ID、路径等），
   以便与报告线框图逐项比对。
   ========================================================================= */

window.APData = (function () {
  'use strict';

  /* ── 连接状态（spec §3.2 C 三态连接模型） ─────────────────────────────── */
  var CONNECTION = {
    healthy: {
      key: 'healthy',
      label: 'Healthy',
      cn: '连接正常',
      tone: 'success',
      delay: '0.8ms',
      banner: null
    },
    stale: {
      key: 'stale',
      label: 'Stale',
      cn: '连接不稳定',
      tone: 'warning',
      delay: '12.4ms',
      banner: {
        tone: 'warning',
        label: 'STALE',
        text: '连接不稳定，正在保持同步…',
        detail: 'last_packet 16.2s ago'
      }
    },
    disconnected: {
      key: 'disconnected',
      label: 'Disconnected',
      cn: '连接断开',
      tone: 'error',
      delay: '—',
      banner: {
        tone: 'error',
        label: 'OFFLINE',
        text: '与 AgentPulse 监控服务断开连接',
        detail: 'reconnect backoff 1s→2s→4s→15s'
      }
    }
  };

  var DATA_STATES = {
    populated: { key: 'populated', label: '有数据' },
    empty: { key: 'empty', label: '空' },
    error: { key: 'error', label: '错误' }
  };

  var SCENARIOS = {
    steady: { key: 'steady', label: '稳态', hint: '无阻断决策，工作流顺畅流转' },
    blocked: { key: 'blocked', label: '决策卡点', hint: '1 项 needs-decision 等待人工决策' }
  };

  /* ── 任务（受 §2.2 A Task 实体约束） ──────────────────────────────────── */
  var TASKS = [
    {
      id: 'agentpulse-design',
      title: '数据源清单与 Android/TUI 界面设计规范',
      state: 'in_flight',
      kind: 'scout',
      repo: 'agentpulse',
      worktree: '/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse',
      branch: null,
      pr_url: null,
      harness: 'agy',
      model: 'claude-opus-4-6-thinking',
      assigned_pane_id: 'w1D:p2',
      pane_ref: 'default:w1D:p2',
      agent: 'agy',
      agent_status: 'working',
      agent_pid: 308453,
      state_source: 'pane · harness busy (herdr-native)',
      blocked_by: null,
      blocked_by_ids: [],
      session_id: null,
      session_note: 'harness=agy：未发现 Pi 会话文件，会话级工具调用已隐藏（spec §1.4 显式置空降级）',
      created_at: '2026-10-18 16:30:25',
      updated_at: '2026-10-18 16:45:10',
      duration: '00:14:22',
      doing: 'harness busy (herdr-native)',
      progress: '正在分析 Pi 会话结构…',
      recent_output: [
        '> checking herdr agent list',
        '{"id":"cli:agent:list","result":{"agents":[{"agent":"agy","pane_id":"w1D:p2"}]}}',
        '> extracting JSON schemas and event sequences...'
      ]
    },
    {
      id: 'agentpulse-service',
      title: '监控服务 + Debian TUI 实现',
      state: 'queued',
      kind: 'ship',
      repo: 'agentpulse',
      worktree: null,
      branch: null,
      pr_url: null,
      harness: 'pi',
      model: '—',
      assigned_pane_id: null,
      pane_ref: null,
      agent: null,
      agent_status: 'unknown',
      agent_pid: null,
      state_source: 'backlog · 阻塞源未完成',
      blocked_by: 'agentpulse-design',
      blocked_by_ids: ['agentpulse-design'],
      session_id: null,
      session_note: '任务尚未开工，无会话记录',
      created_at: '2026-10-18 16:38:00',
      updated_at: '2026-10-18 16:38:00',
      duration: '—',
      doing: '阻塞于 agentpulse-design',
      progress: null,
      recent_output: []
    },
    {
      id: 'agentpulse-android',
      title: '安卓原生看板（Kotlin + Compose）',
      state: 'queued',
      kind: 'ship',
      repo: 'agentpulse',
      worktree: null,
      branch: null,
      pr_url: null,
      harness: 'pi',
      model: '—',
      assigned_pane_id: null,
      pane_ref: null,
      agent: null,
      agent_status: 'unknown',
      agent_pid: null,
      state_source: 'backlog · 阻塞源未完成',
      blocked_by: 'agentpulse-design',
      blocked_by_ids: ['agentpulse-design'],
      session_id: null,
      session_note: '任务尚未开工，无会话记录',
      created_at: '2026-10-18 16:38:00',
      updated_at: '2026-10-18 16:38:00',
      duration: '—',
      doing: '阻塞于 agentpulse-design',
      progress: null,
      recent_output: []
    }
  ];

  /* ── 告警（受 §2.2 D Alert 实体约束） ─────────────────────────────────── */
  var ALERT_DECISION = {
    alert_id: 'alt_01a14d',
    category: 'decision_required',
    severity: 'critical',
    title: '等待人工决策',
    message: '是否同意引入 Material 3 扩展动态颜色库？',
    key: 'material3-dynamic-color',
    task_id: 'agentpulse-design',
    at: '16:30:15',
    options: ['[1] 同意', '[2] 拒绝并保留经典深色'],
    command: "bin/fm-send.sh agentpulse-design '选择方案1'",
    status: 'active'
  };

  var ALERT_DISCONNECTED = {
    alert_id: 'alt_01a150',
    category: 'service_disconnected',
    severity: 'critical',
    title: '与监控服务断开连接',
    message: 'WebSocket 心跳超时 > 30s，TCP 连接已断开。',
    key: 'ws-disconnected',
    task_id: null,
    at: 'now',
    options: null,
    command: 'curl -sS http://127.0.0.1:7890/api/v1/health',
    status: 'active'
  };

  var ALERT_STALE = {
    alert_id: 'alt_01a151',
    category: 'service_disconnected',
    severity: 'warning',
    title: '连接降级（Stale）',
    message: '超过 15 秒未收到数据包，客户端正在保持同步。',
    key: null,
    task_id: null,
    at: 'now',
    options: null,
    command: null,
    status: 'active'
  };

  var ALERT_COLLECTOR = {
    alert_id: 'alt_01a152',
    category: 'pipeline_blocked',
    severity: 'error',
    title: '数据源读取失败',
    message: 'GET /api/v1/overview 返回 500，聚合数据不可用。',
    key: 'collector-firstmate',
    task_id: null,
    at: 'now',
    options: null,
    command: 'tail -n 40 ~/.local/share/agentpulse/service.log',
    detail: "collector firstmate: ENOENT: no such file or directory, open 'state/home-summary.json' (重试 3/5)",
    status: 'active'
  };

  var ALERT_RECOVERED = {
    alert_id: 'alt_01a14e',
    category: 'service_disconnected',
    severity: 'error',
    title: '服务断开连接',
    message: '与 AgentPulse 监控服务断开连接，已自动重连。',
    key: null,
    task_id: null,
    at: '15:20:00',
    options: null,
    command: null,
    status: 'resolved',
    resolution: '已恢复重连（发生于 15:20:00 · 断线 6.4s）'
  };

  var ALERT_RETRY = {
    alert_id: 'alt_01a14f',
    category: 'agent_unresponsive',
    severity: 'warning',
    title: '工具调用超时重试',
    message: 'herdr pane read w1D:p2 --lines 40 超时，第 2 次重试成功。',
    key: null,
    task_id: 'agentpulse-design',
    at: '16:41:37',
    options: null,
    command: null,
    status: 'resolved',
    resolution: '已恢复（重试 2 次 · 用时 1.8s）'
  };

  /* ── 实时动态流（spec §4.3 屏 3 取样） ───────────────────────────────── */
  var FEED = [
    { t: '16:43:10', source: 'firstmate', kind: 'task_state_changed', severity: 'info', text: 'status -> in_flight', sub: 'working [at=1792312916]: starting inventory of FirstMate, Herdr, and Pi data sources', task_id: 'agentpulse-design' },
    { t: '16:43:20', source: 'herdr', kind: 'agent_status_changed', severity: 'info', text: '窗格 w1D:p2 状态流转 idle -> working', task_id: 'agentpulse-design' },
    { t: '16:44:00', source: 'pi', kind: 'session_scan', severity: 'warning', text: '会话扫描降级：harness=agy，未启用 Pi 会话', sub: '~/.pi/agent/sessions/--home-Yuhang-.treehouse-agentpulse-0ede77-1-agentpulse--/ · 0 个会话文件', task_id: 'agentpulse-design' },
    { t: '16:44:20', source: 'herdr', kind: 'pane_output', severity: 'info', text: 'herdr agent list', sub: '↳ 退出码 0 · agents=1', task_id: 'agentpulse-design' },
    { t: '16:44:50', source: 'herdr', kind: 'pane_output', severity: 'info', text: '$ cd /home/Yuhang/agentpulse && ls -la', sub: '↳ 退出码 0 · 12 项文件', task_id: 'agentpulse-design' },
    { t: '16:45:00', source: 'service', kind: 'sync', severity: 'info', text: 'events sync ok · seq=1042 · rtt 0.8ms' },
    { t: '16:45:08', source: 'herdr', kind: 'pane_output', severity: 'info', text: 'read pane w1D:p2 --lines 40', sub: '> extracting JSON schemas and event sequences...', task_id: 'agentpulse-design' },
    { t: '16:45:10', source: 'firstmate', kind: 'task_state_changed', severity: 'info', text: 'working [at=1792312916]', sub: 'starting inventory of FirstMate, Herdr, and Pi data sources', task_id: 'agentpulse-design' }
  ];

  /* ── 模拟推流事件池（节拍器驱动，非真实事件） ─────────────────────────── */
  var EVENT_POOL = [
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: 'herdr pane read w1D:p2 --lines 40', sub: '↳ 40 行 · 环形缓冲区已增量转储（spec §1.4）', task_id: 'agentpulse-design' },
    { source: 'herdr', kind: 'agent_status_changed', severity: 'info', text: '窗格 w1D:p2 状态流转 working -> working', sub: 'revision 13 · state_change_seq 147', task_id: 'agentpulse-design' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: '> extracting JSON schemas and event sequences...' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: 'esc to cancel        Gemini 3.8 Flash · high', sub: '窗格尾部原始行 · format=ansi' },
    { source: 'herdr', kind: 'process_info', severity: 'info', text: '{"pane_id":"w1D:p2","shell_pid":306612}', sub: 'foreground: agy (PID 308453) · cwd=/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse' },
    { source: 'firstmate', kind: 'task_state_changed', severity: 'info', text: 'working [at=1792314484]', sub: 'reconciled: state working · source pane · harness busy (herdr-native)', task_id: 'agentpulse-design' },
    { source: 'service', kind: 'sync', severity: 'info', text: 'events sync ok · seq=1043 → 1044 · rtt 0.7ms' },
    { source: 'service', kind: 'collector', severity: 'info', text: 'collector firstmate: home-summary.json 原子替换', sub: 'state/home-summary.json · schema fm-secondmate-home-summary.v1' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: '$ bin/fm-fleet-snapshot.sh --json | jq .tasks[0].current_state' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: '"working"', sub: '↳ 退出码 0' },
    { source: 'firstmate', kind: 'backlog', severity: 'debug', text: 'tasks-axi list --fields blocked,blocked_by,body,created,links', sub: 'count: 3 · in_flight 1 · queued 2' },
    { source: 'herdr', kind: 'pane_output', severity: 'warning', text: 'warn: pane read 超时，重试 1/3', sub: 'herdr pane read w1D:p2 --lines 200 · 1.9s' },
    { source: 'service', kind: 'retention', severity: 'debug', text: 'ring buffer trim: 每任务保留最新 1000 条事件', sub: 'sqlite 保留 7 天 / 50,000 条 · VACUUM 已调度' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: '$ git -C /home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse status --porcelain' },
    { source: 'herdr', kind: 'pane_output', severity: 'info', text: 'M design/spec.md', sub: '↳ 1 项变更' }
  ];

  /* ── 内部工具 ─────────────────────────────────────────────────────────── */
  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function activeAlerts(alerts) {
    return alerts.filter(function (a) { return a.status === 'active'; });
  }

  function connectionAlerts(key) {
    if (key === 'disconnected') return [clone(ALERT_DISCONNECTED)];
    if (key === 'stale') return [clone(ALERT_STALE)];
    return [];
  }

  function baseAlerts() {
    return [clone(ALERT_RETRY), clone(ALERT_RECOVERED)];
  }

  /* ── 数据状态：有数据 ─────────────────────────────────────────────────── */
  function populated(state) {
    var tasks = clone(TASKS);
    var alerts = baseAlerts();
    var feed = clone(FEED);
    var decisions = [];

    if (state.scenario === 'blocked') {
      var decision = clone(ALERT_DECISION);
      tasks[0].state = 'needs-decision';
      tasks[0].agent_status = 'blocked';
      tasks[0].doing = '等待人工决策：Material 3 动态颜色库';
      tasks[0].progress = '已暂停执行，等待 captain 决策（key=material3-dynamic-color）';
      alerts.unshift(decision);
      decisions = [decision];
      feed.push({
        t: 'now', source: 'firstmate', kind: 'decision_needed', severity: 'critical',
        text: 'needs-decision [key=material3-dynamic-color]',
        sub: '是否同意引入 Material 3 扩展动态颜色库？ 选项: [1] 同意  [2] 拒绝并保留经典深色',
        task_id: 'agentpulse-design'
      });
    }

    var connAlerts = connectionAlerts(state.connection);
    alerts = connAlerts.concat(alerts);

    if (state.connection === 'disconnected') {
      feed.push({
        t: 'now', source: 'service', kind: 'connection_lost', severity: 'critical',
        text: '与 AgentPulse 监控服务断开连接 · reconnect backoff 1s→2s→4s→15s',
        sub: 'WS /ws · last ping 16:45:10 · 事件流已暂停'
      });
    } else if (state.connection === 'stale') {
      feed.push({
        t: 'now', source: 'service', kind: 'connection_degraded', severity: 'warning',
        text: '连接不稳定，正在保持同步 · last_packet 16.2s ago',
        sub: 'WS /ws · since_seq=1042 待续传'
      });
    }

    return {
      ok: true,
      tasks: tasks,
      alerts: alerts,
      decisions: decisions,
      activeAlerts: activeAlerts(alerts),
      feed: feed,
      metrics: {
        inFlight: tasks.filter(function (t) { return t.state === 'in_flight' || t.state === 'needs-decision'; }).length,
        queued: tasks.filter(function (t) { return t.state === 'queued'; }).length,
        done: tasks.filter(function (t) { return t.state === 'done'; }).length,
        alerts: activeAlerts(alerts).length,
        workingAgents: tasks.filter(function (t) { return t.agent_status === 'working'; }).length,
        seq: 1042
      },
      error: null
    };
  }

  /* ── 数据状态：空 ─────────────────────────────────────────────────────── */
  function empty(state) {
    var alerts = connectionAlerts(state.connection);
    return {
      ok: true,
      tasks: [],
      alerts: alerts,
      decisions: [],
      activeAlerts: activeAlerts(alerts),
      feed: [],
      metrics: { inFlight: 0, queued: 0, done: 0, alerts: activeAlerts(alerts).length, workingAgents: 0, seq: 0 },
      error: null
    };
  }

  /* ── 数据状态：错误 ───────────────────────────────────────────────────── */
  function error(state) {
    var alerts = connectionAlerts(state.connection).concat([clone(ALERT_COLLECTOR)]);
    return {
      ok: false,
      tasks: [],
      alerts: alerts,
      decisions: [],
      activeAlerts: activeAlerts(alerts),
      feed: [],
      metrics: { inFlight: 0, queued: 0, done: 0, alerts: activeAlerts(alerts).length, workingAgents: 0, seq: null },
      error: {
        status: 500,
        endpoint: 'GET /api/v1/overview',
        detail: "collector firstmate: ENOENT: no such file or directory, open 'state/home-summary.json'",
        attempts: '重试 3/5 · 下一次退避 2s',
        hint: '服务本身存活（/api/v1/health = degraded），仅 FirstMate 聚合数据不可用。',
        log: [
          '[agentpulse] collector=firstmate tick=1 ok=false dur=2ms',
          "[agentpulse] ENOENT: no such file or directory, open 'state/home-summary.json'",
          '[agentpulse] collector=herdr tick=1 ok=true panes=3 dur=4ms',
          '[agentpulse] GET /api/v1/overview -> 500 (aggregate failed)'
        ]
      }
    };
  }

  /* ── 对外接口 ─────────────────────────────────────────────────────────── */
  return {
    CONNECTION: CONNECTION,
    DATA_STATES: DATA_STATES,
    SCENARIOS: SCENARIOS,

    build: function (state) {
      if (state.data === 'error') return error(state);
      if (state.data === 'empty') return empty(state);
      return populated(state);
    },

    /* 节拍器：从事件池取一条并补齐时间/序号，明确标记为模拟推流 */
    nextEvent: function (tick, seq) {
      var tpl = EVENT_POOL[tick % EVENT_POOL.length];
      var now = new Date();
      var pad = function (n) { return n < 10 ? '0' + n : String(n); };
      var evt = clone(tpl);
      evt.t = pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
      evt.seq = seq + 1;
      evt.simulated = true;
      return evt;
    },

    eventPoolSize: EVENT_POOL.length
  };
})();

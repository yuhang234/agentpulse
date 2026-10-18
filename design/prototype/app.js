/* ============================================================================
   AgentPulse 原型 · 走查外壳与视图渲染
   ---------------------------------------------------------------
   视图：手机竖屏（四屏 + 详情浮层）/ 手机横屏 / 平板三栏 / Debian TUI
   状态：连接三态（Healthy / Stale / Disconnected）
         数据三态（有数据 / 空 / 错误）
         工作流场景（稳态 / 决策卡点）
   所有数据来自 data.js 的模拟模型，实时动态流由定时器合成。
   ========================================================================= */

window.APApp = (function () {
  'use strict';

  /* ── 状态 ─────────────────────────────────────────────────────────────── */
  var state = {
    view: 'phone',
    screen: 'overview',
    connection: 'healthy',
    data: 'populated',
    scenario: 'steady',
    tuiSize: '80',
    fit: true,
    autoScroll: true,
    sheetTaskId: null,
    filter: 'all',
    selectedTaskId: 'agentpulse-design',
    clearedAck: false,
    copied: null,
    feed: [],
    tick: 0,
    reconnect: { attempt: 1, next: 1 },
    clock: new Date()
  };

  var lastEventSeq = 1042;

  /* ── 基础工具 ─────────────────────────────────────────────────────────── */
  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  function clock() {
    return pad2(state.clock.getHours()) + ':' + pad2(state.clock.getMinutes()) + ':' + pad2(state.clock.getSeconds());
  }

  function model() { return window.APData.build(state); }

  function feedAll() { return model().feed.concat(state.feed); }

  function byId(id) { return document.getElementById(id); }

  function dataStateLabel() {
    return { populated: '有数据', empty: '空', error: '错误' }[state.data];
  }

  /* ── 小组件 ───────────────────────────────────────────────────────────── */
  function dot(tone, breath) {
    return '<span class="status-dot' + (breath ? ' status-dot--breath' : '') + '" data-tone="' + tone + '"></span>';
  }

  function chip(text, tone, mono) {
    return '<span class="chip' + (mono ? ' chip--mono' : '') + '"' +
      (tone ? ' data-tone="' + tone + '"' : '') + '>' + esc(text) + '</span>';
  }

  function stateToTone(taskState, agentStatus) {
    if (taskState === 'failed') return 'error';
    if (taskState === 'needs-decision' || taskState === 'blocked') return 'warning';
    if (taskState === 'done') return 'success';
    if (agentStatus === 'working') return 'primary';
    return 'muted';
  }

  function stateWord(task) {
    if (task.state === 'needs-decision') return 'NEEDS-DECISION';
    if (task.state === 'in_flight') return String(task.agent_status).toUpperCase();
    return String(task.state).toUpperCase();
  }

  function kindLabel(kind) {
    return { scout: 'Scout', ship: 'Ship', secondmate: 'SecondMate', custom: 'Custom' }[kind] || kind;
  }

  function severityChip(severity) {
    var tone = severity === 'critical' ? 'error' : (severity === 'error' ? 'error' : (severity === 'warning' ? 'warning' : 'muted'));
    return '<span class="chip chip--mono" data-tone="' + tone + '">' + esc(severity.toUpperCase()) + '</span>';
  }

  function termLines(lines, cls) {
    if (!lines || !lines.length) return '';
    return '<pre class="term ' + (cls || '') + '">' + lines.map(function (line, i) {
      return '<span class="term__line">' + esc(line) + '</span>';
    }).join('') + '</pre>';
  }

  function feedKindLabel(ev) {
    var src = { firstmate: 'FirstMate', herdr: 'Herdr', pi: 'Pi', service: 'Service' }[ev.source] || ev.source;
    var tone = ev.severity === 'critical' ? 'error'
      : ev.severity === 'error' ? 'error'
        : ev.severity === 'warning' ? 'warning'
          : ev.severity === 'debug' ? 'muted' : 'primary';
    return '<span class="chip chip--mono" data-tone="' + tone + '">' + esc(src) + '</span>';
  }

  function feedItemHTML(ev) {
    var cls = ev.severity === 'warning' ? 'feed__text--warn'
      : (ev.severity === 'error' || ev.severity === 'critical') ? 'feed__text--err'
        : (ev.severity === 'debug' ? 'feed__text--soft' : '');
    return '<article class="feed__item" data-severity="' + esc(ev.severity) + '">' +
      '<div class="feed__time">' + esc(ev.t) + '</div>' +
      '<div class="feed__body">' +
        '<div class="feed__head">' + feedKindLabel(ev) +
          '<span class="feed__note">' + esc(ev.kind) + (ev.seq ? ' · seq=' + ev.seq : '') + '</span>' +
          (ev.task_id ? '<span class="feed__note mono-sm">' + esc(ev.task_id) + '</span>' : '') +
        '</div>' +
        '<div class="feed__text ' + cls + '">' + esc(ev.text) + '</div>' +
        (ev.sub ? '<div class="feed__sub">' + esc(ev.sub) + '</div>' : '') +
      '</div>' +
    '</article>';
  }

  function feedListHTML(events, emptyCopy) {
    if (!events.length) {
      return '<div class="empty-state" style="margin:12px;">' +
        '<span class="empty-state__mark">∅</span>' +
        '<div class="empty-state__title">' + esc(emptyCopy) + '</div>' +
        '<div class="empty-state__body">事件流为空。FirstMate 状态行、Herdr 窗格输出与 Pi 会话事件都会在这里按时间顺序出现。</div>' +
      '</div>';
    }
    return events.map(feedItemHTML).join('');
  }

  /* ── 告警卡片 ─────────────────────────────────────────────────────────── */
  function alertCardHTML(alert) {
    var tone = alert.severity === 'critical' || alert.severity === 'error' ? 'error'
      : (alert.severity === 'warning' ? 'warning' : 'muted');
    var cardCls = 'card' + (alert.status === 'active' ? (tone === 'error' ? ' card--error' : ' card--warning') : '');
    var rows = [];
    rows.push(['类别', alert.category]);
    if (alert.task_id) rows.push(['任务', alert.task_id]);
    rows.push(['时间', alert.at]);
    if (alert.key) rows.push(['闭环键', 'key=' + alert.key]);
    if (alert.detail) rows.push(['详情', alert.detail]);

    var html = '<article class="' + cardCls + '">';
    html += '<div class="row row--between">' +
      '<div class="row">' + dot(tone, alert.status === 'active' && tone === 'error') +
        '<strong>' + esc(alert.title) + '</strong></div>' +
      '<div class="row">' + severityChip(alert.severity) +
        chip(alert.status === 'active' ? '待处理' : '已解决', alert.status === 'active' ? tone : 'muted') +
      '</div></div>';
    html += '<dl class="meta-grid">' + rows.map(function (r) {
      return '<dt>' + esc(r[0]) + '</dt><dd class="mono-sm">' + esc(r[1]) + '</dd>';
    }).join('') + '</dl>';
    html += '<p style="margin:0;color:var(--color-text-secondary);font-size:var(--type-body-sm-size);line-height:var(--type-body-sm-line);">' +
      esc(alert.message) + '</p>';

    if (alert.options) {
      html += '<div class="row row--wrap" style="gap:8px;">' +
        alert.options.map(function (opt) { return chip(opt, 'focus'); }).join('') + '</div>';
    }
    if (alert.command) {
      html += '<div class="stack">' +
        '<div class="meta-label">电脑处理指令</div>' +
        '<pre class="term">' + esc(alert.command) + '</pre>' +
        '<div class="row">' +
          '<button class="btn btn--quiet" data-action="copy" data-copy="' + esc(alert.command) + '" data-key="copy-' + esc(alert.alert_id) + '">复制指令</button>' +
          (state.copied === alert.alert_id ? '<span class="chip" data-tone="success">已复制到剪贴板</span>' : '') +
        '</div>' +
      '</div>';
    }
    if (alert.resolution) {
      html += '<div class="status-line" style="color:var(--color-success);">' + dot('success') +
        '<span class="mono-sm">' + esc(alert.resolution) + '</span></div>';
    }
    html += '</article>';
    return html;
  }

  /* ── 任务卡片 ─────────────────────────────────────────────────────────── */
  function taskMetaHTML(task) {
    var rows = [
      ['仓库', task.repo],
      ['Harness', task.harness]
    ];
    if (task.worktree) rows.push(['工作目录', task.worktree]);
    if (task.assigned_pane_id) rows.push(['窗格', task.pane_ref || task.assigned_pane_id]);
    if (task.blocked_by) rows.push(['阻塞于', task.blocked_by]);
    return '<dl class="meta-grid">' + rows.map(function (r) {
      var mono = r[0] === '工作目录' || r[0] === '窗格' || r[0] === '阻塞于';
      return '<dt>' + esc(r[0]) + '</dt><dd class="' + (mono ? 'path' : 'mono-sm') + '">' + esc(r[1]) + '</dd>';
    }).join('') + '</dl>';
  }

  function taskCardHTML(task, options) {
    options = options || {};
    var tone = stateToTone(task.state, task.agent_status);
    var html = '<button type="button" class="card card--interactive' +
      (options.selectable && state.selectedTaskId === task.id ? ' card--accent' : '') +
      '" data-action="open-task" data-task-id="' + esc(task.id) + '" data-key="task-' + esc(task.id) + '"' +
      (options.selectable ? ' aria-current="' + (state.selectedTaskId === task.id) + '"' : '') + '>';

    html += '<div class="row row--between">' +
      '<div class="row" style="min-width:0;">' + dot(tone, tone === 'primary') +
        '<span class="task-id">' + esc(task.id) + '</span></div>' +
      '<div class="row">' + chip(kindLabel(task.kind), 'focus') +
        (task.state === 'in_flight' ? chip(task.duration, 'muted', true) : '') + '</div></div>';

    if (task.state === 'in_flight' || task.state === 'needs-decision') {
      html += '<div class="status-line">' +
        '<span class="status-line__state" data-state="' + esc(task.state) + '">' + esc(stateWord(task)) + '</span>' +
        '<span class="meta-value mono-sm">' + esc(task.agent + ' · Herdr: ' + task.assigned_pane_id) + '</span>' +
      '</div>';
      html += '<div class="stack">' +
        '<div class="meta-value">目标：' + esc(task.title) + '</div>' +
        (task.progress ? '<div class="meta-value">进度：' + esc(task.progress) + '</div>' : '') +
      '</div>';
    } else if (task.state === 'queued') {
      html += '<div class="status-line">' +
        '<span class="status-line__state" data-state="queued">QUEUED</span>' +
        '<span class="meta-value mono-sm">阻塞于 ' + esc(task.blocked_by) + '</span></div>' +
        '<div class="meta-value">目标：' + esc(task.title) + '</div>';
    }

    html += taskMetaHTML(task);

    if (task.recent_output && task.recent_output.length) {
      html += '<div class="stack"><div class="meta-label">近期终端输出</div>' +
        termLines(task.recent_output, 'term--tall') + '</div>';
    }
    html += '</button>';
    return html;
  }

  /* ══════════════════════════════════════════════════════════════════════
     手机竖屏：四个屏幕
     ══════════════════════════════════════════════════════════════════════ */

  function errorStateHTML(m) {
    return '<div class="empty-state empty-state--error" style="text-align:left;">' +
      '<span class="empty-state__mark">!</span>' +
      '<div class="empty-state__title">数据源读取失败（HTTP ' + m.error.status + '）</div>' +
      '<div class="empty-state__body" style="max-width:none;">' + esc(m.error.detail) + '</div>' +
      '<pre class="term" style="width:100%;text-align:left;">' + m.error.log.map(esc).join('\n') + '</pre>' +
      '<div class="empty-state__body">' + esc(m.error.attempts) + '</div>' +
      '<div class="empty-state__body">' + esc(m.error.hint) + '</div>' +
      '<button class="btn" data-action="retry" data-key="retry">重试聚合</button>' +
    '</div>';
  }

  function metricsHTML(m) {
    var alertsTone = m.metrics.alerts > 0 ? 'error' : 'muted';
    return '<div class="metrics">' +
      '<div class="metric"><span class="metric__value" data-tone="primary">' + m.metrics.inFlight + '</span>' +
        '<span class="metric__label">在飞任务</span></div>' +
      '<div class="metric"><span class="metric__value" data-tone="muted">' + m.metrics.queued + '</span>' +
        '<span class="metric__label">排队就绪</span></div>' +
      '<div class="metric"><span class="metric__value" data-tone="' + alertsTone + '">' + m.metrics.alerts + '</span>' +
        '<span class="metric__label">异常告警</span></div>' +
    '</div>';
  }

  function screenOverview(m) {
    var html = '<div class="screen" data-screen="overview">';
    if (!m.ok) {
      html += '<div class="app-body">' + errorStateHTML(m) + '</div></div>';
      return html;
    }
    html += '<div class="app-body">';

    html += '<div class="section">' +
      '<div class="section__head">系统状态<span class="section__count">' + esc(window.APData.CONNECTION[state.connection].label) + '</span></div>' +
      '<div class="card card--accent">' +
        '<div class="row row--between">' +
          '<div class="stack">' +
            '<div style="font-size:var(--type-title-md-size);line-height:var(--type-title-md-line);">' +
              (state.connection === 'healthy' ? '工作流运转中' : (state.connection === 'stale' ? '连接降级，仍可查看缓存数据' : '与监控服务断开连接')) +
            '</div>' +
            '<div class="meta-value mono-sm">127.0.0.1:7890 · 延迟 ' + esc(window.APData.CONNECTION[state.connection].delay) + ' · seq=' + (m.metrics.seq === null ? '—' : m.metrics.seq) + '</div>' +
          '</div>' +
          chip(window.APData.CONNECTION[state.connection].label, window.APData.CONNECTION[state.connection].tone) +
        '</div>' +
      '</div>' +
      metricsHTML(m) +
    '</div>';

    /* 正在执行的任务 */
    var running = m.tasks.filter(function (t) { return t.state === 'in_flight' || t.state === 'needs-decision'; });
    html += '<div class="section">' +
      '<div class="section__head">正在执行的任务<span class="section__count">' + running.length + '</span></div>';
    if (!running.length) {
      html += '<div class="empty-state"><span class="empty-state__mark">∅</span>' +
        '<div class="empty-state__title">当前无在飞任务</div>' +
        '<div class="empty-state__body">FirstMate 队列已清空，等待 captain 派单后此处会出现任务卡片。</div></div>';
    } else {
      running.forEach(function (task) { html += taskCardHTML(task); });
    }
    html += '</div>';

    /* 待处理决策 */
    html += '<div class="section">' +
      '<div class="section__head">待处理决策<span class="section__count">' + m.decisions.length + '</span></div>';
    if (!m.decisions.length) {
      html += '<div class="card"><div class="row">' + dot('success') +
        '<span class="meta-value">暂无阻断决策，工作流顺畅流转中</span></div></div>';
    } else {
      m.decisions.forEach(function (d) { html += alertCardHTML(d); });
    }
    html += '</div>';

    /* 排队队列 */
    var queued = m.tasks.filter(function (t) { return t.state === 'queued'; });
    html += '<div class="section">' +
      '<div class="section__head">排队队列<span class="section__count">' + queued.length + '</span></div>';
    if (!queued.length) {
      html += '<div class="card"><span class="meta-value">队列为空</span></div>';
    } else {
      html += '<div class="card card--list">' + queued.map(function (t) {
        return '<button type="button" class="list__item" data-action="open-task" data-task-id="' + esc(t.id) + '" data-key="queue-' + esc(t.id) + '">' +
          '<span class="list__marker">•</span>' +
          '<span class="stack">' +
            '<span class="task-id">' + esc(t.id) + '</span>' +
            '<span class="meta-value mono-sm">阻塞源：' + esc(t.blocked_by) + '</span>' +
          '</span>' +
          '<span class="row" style="margin-left:auto;">' + chip(kindLabel(t.kind), 'focus') + '</span>' +
        '</button>';
      }).join('') + '</div>';
    }
    html += '</div>';

    html += '</div></div>';
    return html;
  }

  function screenTasks(m) {
    var counts = {
      all: m.tasks.length,
      active: m.tasks.filter(function (t) { return t.state === 'in_flight' || t.state === 'needs-decision'; }).length,
      queued: m.tasks.filter(function (t) { return t.state === 'queued'; }).length,
      done: m.tasks.filter(function (t) { return t.state === 'done'; }).length
    };
    var filters = [
      { key: 'all', label: '全部', n: counts.all },
      { key: 'active', label: '进行中', n: counts.active },
      { key: 'queued', label: '排队中', n: counts.queued },
      { key: 'done', label: '已完成', n: counts.done }
    ];
    var html = '<div class="screen" data-screen="tasks" hidden>';
    html += '<div class="feed-toolbar">' +
      '<span class="chip">筛选</span>' +
      filters.map(function (f) {
        return '<button type="button" class="btn ' + (state.filter === f.key ? 'btn--primary' : 'btn--quiet') +
          '" data-action="filter" data-filter="' + f.key + '" data-key="filter-' + f.key + '">' +
          esc(f.label) + '(' + f.n + ')</button>';
      }).join('') +
      '<span class="feed__note" style="margin-left:auto;">来源 state/home-summary.json</span>' +
    '</div>';

    html += '<div class="app-body">';
    if (!m.ok) {
      html += errorStateHTML(m);
    } else if (!m.tasks.length) {
      html += '<div class="empty-state"><span class="empty-state__mark">∅</span>' +
        '<div class="empty-state__title">任务队列为空</div>' +
        '<div class="empty-state__body">FirstMate 未返回任何任务：state/*.meta 与 data/backlog.md 均无条目。</div></div>';
    } else {
      var visible = m.tasks.filter(function (t) {
        if (state.filter === 'all') return true;
        if (state.filter === 'active') return t.state === 'in_flight' || t.state === 'needs-decision';
        if (state.filter === 'queued') return t.state === 'queued';
        return t.state === 'done';
      });
      if (!visible.length) {
        html += '<div class="empty-state"><span class="empty-state__mark">∅</span>' +
          '<div class="empty-state__title">该筛选下没有任务</div>' +
          '<div class="empty-state__body">切换上方筛选芯片查看其它状态的任务。</div></div>';
      }
      visible.forEach(function (task) { html += taskCardHTML(task); });
    }
    html += '</div></div>';
    return html;
  }

  function screenFeed(m) {
    var events = feedAll();
    var paused = state.connection === 'disconnected' || !m.ok;
    var html = '<div class="screen" data-screen="feed" hidden>';
    html += '<div class="feed-toolbar">' +
      '<span class="chip" data-tone="primary">模拟推流</span>' +
      '<span class="feed__note" id="live-caption">' + (paused ? '已暂停（连接中断）' : '每 2.6 秒合成一条事件') + '</span>' +
      '<button type="button" class="toggle" data-action="toggle-autoscroll" data-key="toggle-autoscroll" aria-pressed="' + state.autoScroll + '">' +
        '<span class="toggle__track"></span>自动滚动：' + (state.autoScroll ? '开' : '关') +
      '</button>' +
    '</div>';
    html += '<div class="app-body app-body--flush">' +
      '<div class="feed js-feed" role="log" aria-live="off" data-feed-scope="all">' +
        feedListHTML(events, '暂无事件') +
      '</div>' +
    '</div></div>';
    return html;
  }

  function screenAlerts(m) {
    var alerts = m.alerts.filter(function (a) {
      return !(state.clearedAck && a.status !== 'active');
    });
    var html = '<div class="screen" data-screen="alerts" hidden>';
    html += '<div class="feed-toolbar">' +
      '<span class="chip" data-tone="' + (m.activeAlerts.length ? 'error' : 'muted') + '">待处理 ' + m.activeAlerts.length + '</span>' +
      '<span class="feed__note">' + (m.ok ? '按严重度分级（spec §4.4）' : '服务异常') + '</span>' +
      '<button type="button" class="btn btn--quiet" style="margin-left:auto;" data-action="clear-read" data-key="clear-read">清空已读</button>' +
    '</div>';

    html += '<div class="app-body">';
    if (!m.ok) {
      html += errorStateHTML(m);
      html += alerts.map(alertCardHTML).join('');
    } else if (!alerts.length) {
      html += '<div class="empty-state"><span class="empty-state__mark">✓</span>' +
        '<div class="empty-state__title">暂无异常</div>' +
        '<div class="empty-state__body">没有阻断事件、任务失败或等待人工决策的卡点。静默级告警只会在底部导航显示琥珀色角标。</div></div>';
    } else {
      alerts.forEach(function (a) { html += alertCardHTML(a); });
    }
    html += '</div></div>';
    return html;
  }

  /* ── 应用外壳（头部 / 横幅 / 导航 / 浮层） ─────────────────────────────── */
  function appHeaderHTML(m, title) {
    var conn = window.APData.CONNECTION[state.connection];
    return '<header class="app-header">' +
      '<div class="app-header__title">' + dot(conn.tone) + esc(title || 'AgentPulse · WSL2') + '</div>' +
      '<div class="app-header__meta">' +
        chip('SSH 7890', 'muted', true) +
        '<span class="chip chip--mono js-clock">' + clock() + '</span>' +
        badgeHTML(m) +
      '</div>' +
    '</header>';
  }

  function badgeHTML(m) {
    var n = m.activeAlerts.length;
    if (!n) return '';
    var worst = m.activeAlerts.some(function (a) { return a.severity === 'critical' || a.severity === 'error'; });
    return '<span class="chip chip--mono" data-tone="' + (worst ? 'error' : 'warning') + '">告警 ' + n + '</span>';
  }

  function bannerHTML() {
    var conn = window.APData.CONNECTION[state.connection];
    if (!conn.banner) return '';
    var detail = conn.banner.detail;
    if (state.connection === 'disconnected') {
      detail = '<span class="js-reconnect">尝试 #' + state.reconnect.attempt + ' · 下次重试 ' + state.reconnect.next + 's</span>';
    }
    return '<div class="app-banner" data-tone="' + conn.banner.tone + '" role="status">' +
      '<span class="app-banner__label">' + esc(conn.banner.label) + '</span>' +
      '<span>' + esc(conn.banner.text) + '</span>' +
      '<span class="app-banner__detail">' + detail + '</span>' +
    '</div>';
  }

  function tabbarHTML(m) {
    var tabs = [
      { key: 'overview', label: '总览' },
      { key: 'tasks', label: '任务' },
      { key: 'feed', label: '动态' },
      { key: 'alerts', label: '告警' }
    ];
    var worst = m.activeAlerts.some(function (a) { return a.severity === 'critical' || a.severity === 'error'; });
    return '<nav class="tabbar" role="tablist" aria-label="主导航">' + tabs.map(function (t) {
      var badge = '';
      if (t.key === 'alerts' && m.activeAlerts.length) {
        badge = '<span class="tabbar__badge"' + (worst ? ' data-tone="error"' : '') + '>' + m.activeAlerts.length + '</span>';
      }
      return '<button type="button" role="tab" class="tabbar__item" aria-selected="' + (state.screen === t.key) + '"' +
        ' data-action="set-screen" data-screen="' + t.key + '" data-key="tab-' + t.key + '">' +
        '<span class="tabbar__glyph"></span>' + esc(t.label) + badge + '</button>';
    }).join('') + '</nav>';
  }

  function sheetHTML(m) {
    if (!state.sheetTaskId) return '<div class="sheet-layer" hidden></div>';
    var task = m.tasks.filter(function (t) { return t.id === state.sheetTaskId; })[0];
    if (!task) return '<div class="sheet-layer" hidden></div>';
    var tone = stateToTone(task.state, task.agent_status);
    var related = feedAll().filter(function (ev) { return ev.task_id === task.id; }).slice(-3);

    var rows = [
      ['状态', stateWord(task)],
      ['类型', kindLabel(task.kind)],
      ['仓库', task.repo],
      ['Harness', task.harness],
      ['模型', task.model],
      ['工作目录', task.worktree || '—（尚未创建 worktree）'],
      ['分支', task.branch || '—'],
      ['PR', task.pr_url || '—'],
      ['窗格', task.pane_ref || '—（未关联 Herdr Pane）'],
      ['状态来源', task.state_source],
      ['阻塞源', task.blocked_by || '—'],
      ['创建时间', task.created_at],
      ['更新时间', task.updated_at]
    ];

    return '<div class="sheet-layer">' +
      '<button class="sheet__scrim" type="button" aria-label="关闭详情" data-action="close-sheet" data-key="scrim"></button>' +
      '<section class="sheet" role="dialog" aria-modal="true" aria-label="任务详情">' +
        '<div class="sheet__handle"></div>' +
        '<header class="sheet__head">' +
          '<div class="stack">' +
            '<div class="sheet__title">' + esc(task.id) + '</div>' +
            '<div class="sheet__sub">' + esc(task.title) + '</div>' +
          '</div>' +
          '<button class="sheet__close" type="button" aria-label="关闭" data-action="close-sheet" data-key="close-sheet">✕</button>' +
        '</header>' +
        '<div class="sheet__body">' +
          '<div class="row row--wrap">' + dot(tone, tone === 'primary') +
            chip(stateWord(task), tone) + chip(kindLabel(task.kind), 'focus') +
            chip('时长 ' + task.duration, 'muted', true) + '</div>' +
          '<dl class="meta-grid">' + rows.map(function (r) {
            var mono = ['工作目录', '窗格', '创建时间', '更新时间', '分支', 'PR'].indexOf(r[0]) >= 0;
            return '<dt>' + esc(r[0]) + '</dt><dd class="' + (mono ? 'path' : 'mono-sm') + '">' + esc(r[1]) + '</dd>';
          }).join('') + '</dl>' +
          '<div class="stack">' +
            '<div class="meta-label">微观会话（Pi）</div>' +
            '<div class="row row--wrap">' +
              (task.session_id
                ? chip(task.session_id, 'primary', true)
                : chip('已降级', 'warning') + chip('active_agent_session_id = null', 'muted', true)) +
            '</div>' +
            '<div class="meta-value">' + esc(task.session_note) + '</div>' +
          '</div>' +
          (task.recent_output.length
            ? '<div class="stack"><div class="meta-label">近期终端输出（Herdr Pane）</div>' + termLines(task.recent_output, 'term--tall') + '</div>'
            : '') +
          (related.length
            ? '<div class="stack"><div class="meta-label">相关事件</div>' +
              '<div class="feed">' + related.map(feedItemHTML).join('') + '</div></div>'
            : '') +
        '</div>' +
      '</section>' +
    '</div>';
  }

  function phoneHTML(m) {
    return '<div class="device-scaler" style="--dw:390px;--dh:844px;">' +
      '<div class="device">' +
        '<div class="device__screen" data-connection="' + state.connection + '">' +
          '<div class="app-surface">' +
            appHeaderHTML(m) +
            bannerHTML() +
            screenOverview(m) + screenTasks(m) + screenFeed(m) + screenAlerts(m) +
            tabbarHTML(m) +
          '</div>' +
          sheetHTML(m) +
        '</div>' +
      '</div>' +
    '</div>';
  }

  /* ══════════════════════════════════════════════════════════════════════
     手机横屏（spec §4.5.1：左 40% 任务与统计 / 右 60% 终端与动态）
     ══════════════════════════════════════════════════════════════════════ */
  function railHTML(m) {
    var tabs = [
      { key: 'overview', label: '总览' },
      { key: 'tasks', label: '任务' },
      { key: 'feed', label: '动态' },
      { key: 'alerts', label: '告警' }
    ];
    var worst = m.activeAlerts.some(function (a) { return a.severity === 'critical' || a.severity === 'error'; });
    return '<nav class="rail" aria-label="主导航">' +
      '<div class="rail__brand"></div>' +
      tabs.map(function (t) {
        var badge = (t.key === 'alerts' && m.activeAlerts.length)
          ? '<span class="rail__badge"' + (worst ? ' style="background:var(--color-error);color:#fff;"' : '') + '>' + m.activeAlerts.length + '</span>'
          : '';
        return '<button type="button" class="rail__item" aria-selected="' + (state.screen === t.key) + '"' +
          ' data-action="set-screen" data-screen="' + t.key + '" data-key="rail-' + t.key + '" title="' + esc(t.label) + '">' +
          '<span class="rail__glyph"></span>' + esc(t.label) + badge + '</button>';
      }).join('') +
    '</nav>';
  }

  function landscapeHTML(m) {
    var selected = m.tasks.filter(function (t) { return t.id === state.selectedTaskId; })[0] || m.tasks[0] || null;
    var right = '';

    if (state.screen === 'feed') {
      right = '<div class="feed-toolbar"><span class="chip" data-tone="primary">模拟推流</span>' +
        '<span class="feed__note">跨任务全局事件</span>' +
        '<button type="button" class="toggle" data-action="toggle-autoscroll" data-key="toggle-autoscroll-2" aria-pressed="' + state.autoScroll + '">' +
        '<span class="toggle__track"></span>自动滚动：' + (state.autoScroll ? '开' : '关') + '</button></div>' +
        '<div class="split__scroll" style="padding:0;"><div class="feed js-feed" role="log" aria-live="off">' +
        feedListHTML(feedAll(), '暂无事件') + '</div></div>';
    } else if (state.screen === 'alerts') {
      var alerts = m.alerts.filter(function (a) { return !(state.clearedAck && a.status !== 'active'); });
      right = '<div class="feed-toolbar"><span class="chip" data-tone="' + (m.activeAlerts.length ? 'error' : 'muted') + '">待处理 ' + m.activeAlerts.length + '</span>' +
        '<button type="button" class="btn btn--quiet" style="margin-left:auto;" data-action="clear-read" data-key="clear-read-2">清空已读</button></div>' +
        '<div class="split__scroll">' +
        (alerts.length ? alerts.map(alertCardHTML).join('')
          : '<div class="empty-state"><span class="empty-state__mark">✓</span><div class="empty-state__title">暂无异常</div></div>') +
        '</div>';
    } else if (!m.ok) {
      right = '<div class="split__scroll">' + errorStateHTML(m) + '</div>';
    } else if (selected) {
      right = '<div class="split__head">终端输出 · ' + esc(selected.assigned_pane_id || '—') +
          '<span class="mono-sm mono" style="margin-left:auto;">' + esc(selected.harness) + '</span></div>' +
        '<div class="split__scroll">' +
          '<div class="card card--accent">' +
            '<div class="row row--between">' + '<span class="task-id">' + esc(selected.id) + '</span>' +
              '<span class="row">' + chip(stateWord(selected), stateToTone(selected.state, selected.agent_status)) + '</span></div>' +
            '<div class="meta-value">' + esc(selected.title) + '</div>' +
            taskMetaHTML(selected) +
          '</div>' +
          '<div class="card"><div class="meta-label">Herdr Pane 实时映像 · ' + esc(selected.pane_ref || '—') + '</div>' +
            termLines(selected.recent_output.length ? selected.recent_output : ['(no output yet · 窗格静默)']) + '</div>' +
          '<div class="card card--list"><div class="split__head" style="border-bottom:1px solid var(--color-outline);">该任务事件</div>' +
            '<div class="feed js-feed" role="log" aria-live="off">' +
            feedListHTML(feedAll().filter(function (ev) { return ev.task_id === selected.id; }), '该任务暂无事件') + '</div></div>' +
        '</div>';
    } else {
      right = '<div class="split__scroll"><div class="empty-state"><span class="empty-state__mark">∅</span>' +
        '<div class="empty-state__title">无在飞任务</div>' +
        '<div class="empty-state__body">队列为空时右侧不再显示终端映像。</div></div></div>';
    }

    var list = m.tasks.length
      ? m.tasks.map(function (t) {
        return '<button type="button" class="board__col--select list__item" style="border-radius:var(--radius-card);border:1px solid var(--color-outline);' +
          (state.selectedTaskId === t.id ? '' : 'background:var(--color-surface);') + '"' +
          ' aria-current="' + (state.selectedTaskId === t.id) + '" data-action="select-task" data-task-id="' + esc(t.id) + '" data-key="sel-' + esc(t.id) + '">' +
          '<span class="row" style="min-width:0;">' + dot(stateToTone(t.state, t.agent_status), t.agent_status === 'working') +
            '<span class="task-id">' + esc(t.id) + '</span></span>' +
          '<span class="row" style="margin-left:auto;">' + chip(kindLabel(t.kind), 'focus') + '</span>' +
        '</button>';
      }).join('')
      : '<div class="empty-state"><span class="empty-state__mark">∅</span><div class="empty-state__title">任务队列为空</div></div>';

    return '<div class="device-scaler" style="--dw:844px;--dh:390px;">' +
      '<div class="device device--landscape">' +
        '<div class="device__screen" data-connection="' + state.connection + '">' +
          '<div class="app-surface" style="flex-direction:row;">' +
            railHTML(m) +
            '<div style="flex:1 1 auto;display:flex;flex-direction:column;min-width:0;">' +
              '<div class="app-surface">' +
                appHeaderHTML(m, 'AgentPulse · 电脑副屏模式') +
                bannerHTML() +
                '<div class="split">' +
                  '<div class="split__col">' +
                    '<div class="split__head">统计与任务<span class="mono-sm" style="margin-left:auto;">' + m.tasks.length + ' 项</span></div>' +
                    '<div class="split__scroll">' + metricsHTML(m) + list + '</div>' +
                  '</div>' +
                  '<div class="split__col split__col--right">' + right + '</div>' +
                '</div>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  /* ══════════════════════════════════════════════════════════════════════
     平板三栏（spec §4.5.2：25% / 45% / 30%）
     ══════════════════════════════════════════════════════════════════════ */
  function tabletHTML(m) {
    var selected = m.tasks.filter(function (t) { return t.id === state.selectedTaskId; })[0] || m.tasks[0] || null;

    var left = '<div class="split__head">全局指标与队列</div>' +
      '<div class="split__scroll">' +
        metricsHTML(m) +
        '<div class="section"><div class="section__head">待处理决策<span class="section__count">' + m.decisions.length + '</span></div>' +
          (m.decisions.length ? m.decisions.map(alertCardHTML).join('')
            : '<div class="card"><div class="row">' + dot('success') + '<span class="meta-value">暂无阻断决策</span></div></div>') +
        '</div>' +
        '<div class="section"><div class="section__head">队列<span class="section__count">' + m.tasks.length + '</span></div>' +
          (m.tasks.length ? m.tasks.map(function (t) {
            return '<button type="button" class="list__item" style="border:1px solid var(--color-outline);border-radius:var(--radius-card);margin-bottom:8px;' +
              (state.selectedTaskId === t.id ? 'background:var(--color-primary-a08);box-shadow:inset 2px 0 0 0 var(--color-primary);' : 'background:var(--color-surface);') + '"' +
              ' data-action="select-task" data-task-id="' + esc(t.id) + '" data-key="tab-sel-' + esc(t.id) + '">' +
              '<span class="row" style="min-width:0;">' + dot(stateToTone(t.state, t.agent_status)) +
                '<span class="task-id">' + esc(t.id) + '</span></span>' +
              '<span class="row" style="margin-left:auto;">' + chip(kindLabel(t.kind), 'focus') + '</span>' +
            '</button>';
          }).join('')
            : '<div class="empty-state"><span class="empty-state__mark">∅</span><div class="empty-state__title">队列为空</div></div>') +
        '</div>' +
      '</div>';

    var middle = '<div class="split__head">在飞任务面板' +
        (selected ? '<span class="mono-sm" style="margin-left:auto;">' + esc(selected.agent + ' · ' + (selected.assigned_pane_id || '—')) + '</span>' : '') +
      '</div>' +
      '<div class="split__scroll">' +
      (m.ok === false ? errorStateHTML(m) : (selected
        ? '<div class="card card--accent">' +
            '<div class="row row--between"><div class="row">' + dot(stateToTone(selected.state, selected.agent_status), selected.agent_status === 'working') +
              '<span class="task-id">' + esc(selected.id) + '</span></div>' +
              '<span class="row">' + chip(stateWord(selected), stateToTone(selected.state, selected.agent_status)) + chip('时长 ' + selected.duration, 'muted', true) + '</span></div>' +
            '<div class="meta-value">' + esc(selected.title) + '</div>' +
            (selected.progress ? '<div class="meta-value">进度：' + esc(selected.progress) + '</div>' : '') +
            taskMetaHTML(selected) +
          '</div>' +
          '<div class="card"><div class="row row--between"><span class="meta-label">终端实时映像（ANSI）</span>' +
            chip('format=ansi', 'muted', true) + '</div>' +
            '<pre class="term term--tall">' +
              (selected.recent_output.length
                ? selected.recent_output.map(function (line) {
                  var cls = line.indexOf('$') === 0 ? 'term__prompt'
                    : line.indexOf('>') === 0 ? 'term__dim'
                      : (line.indexOf('{') === 0 ? 'term__ok' : '');
                  return '<span class="term__line ' + cls + '">' + esc(line) + '</span>';
                }).join('')
                : '<span class="term__line term__dim">(no output yet · 窗格静默)</span>') +
            '</pre></div>'
        : '<div class="empty-state"><span class="empty-state__mark">∅</span><div class="empty-state__title">无在飞任务</div>' +
          '<div class="empty-state__body">中栏在无任务时保持空态，避免出现无意义的占位面板。</div></div>')) +
      '</div>';

    var right = '<div class="split__head">全局活动事件流<span class="mono-sm" style="margin-left:auto;">seq=' +
        (m.metrics.seq === null ? '—' : m.metrics.seq) + '</span></div>' +
      '<div class="split__scroll" style="padding:0;"><div class="feed js-feed" role="log" aria-live="off">' +
        feedListHTML(feedAll(), '暂无事件') + '</div></div>';

    return '<div class="device-scaler" style="--dw:1080px;--dh:760px;">' +
      '<div class="device device--tablet">' +
        '<div class="device__screen" data-connection="' + state.connection + '">' +
          '<div class="app-surface">' +
            appHeaderHTML(m, 'AgentPulse · 极客桌面看板') +
            bannerHTML() +
            '<div class="board">' +
              '<div class="board__col">' + left + '</div>' +
              '<div class="board__col">' + middle + '</div>' +
              '<div class="board__col">' + right + '</div>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  /* ══════════════════════════════════════════════════════════════════════
     Debian TUI
     ══════════════════════════════════════════════════════════════════════ */
  function mappingHTML() {
    var rows = [
      ['活跃进行 (Working)', '#38BDF8', 'Cyan', '81', '\\u001b[36m', 'Cyan', '醒目、不伤眼的高级青色', '--color-primary'],
      ['完成通过 (Success)', '#34D399', 'Emerald', '78', '\\u001b[32m', 'Green', '标准通过绿色', '--color-success'],
      ['阻塞挂起 (Warning)', '#FBBF24', 'Amber', '214', '\\u001b[33m', 'Yellow', '决策与阻塞黄色', '--color-warning'],
      ['严重失败 (Error)', '#F43F5E', 'Rose', '197', '\\u001b[31m', 'Red', '警报红色', '--color-error'],
      ['次级文本 (Muted)', '#8B949E', 'Grey', '244', '\\u001b[90m', 'Bright Black', '辅助边框与灰字', '--color-text-secondary'],
      ['窗格焦点 (Focus)', '#A78BFA', 'Purple', '141', '\\u001b[35m', 'Magenta', '选中区域的高亮边框', '--color-focus']
    ];
    return '<div class="mapping"><table>' +
      '<caption>spec §5.3 · 16 色色彩降级与 ANSI 映射表（TUI 渲染即按此角色着色）</caption>' +
      '<thead><tr><th>语义角色</th><th>24-bit TrueColor</th><th>256 色</th><th>16 色 ANSI</th><th>说明</th></tr></thead>' +
      '<tbody>' + rows.map(function (r) {
        return '<tr><td><span class="swatch"><i style="background:var(' + r[7] + ')"></i>' + esc(r[0]) + '</span></td>' +
          '<td class="mono-sm">' + esc(r[1]) + ' (' + esc(r[2]) + ')</td>' +
          '<td class="mono-sm">' + esc(r[3]) + '</td>' +
          '<td class="mono-sm">' + esc(r[4]) + ' (' + esc(r[5]) + ')</td>' +
          '<td>' + esc(r[6]) + '</td></tr>';
      }).join('') + '</tbody></table></div>';
  }

  function tuiHTML(m) {
    var is80 = state.tuiSize === '80';
    var body = is80 ? window.APTui.build80(m, state, state.clock) : window.APTui.build160(m, state, state.clock);
    return '<div class="tui-wrap">' +
      '<div class="tui-bar">' +
        '<div class="segmented" role="group" aria-label="终端尺寸">' +
          '<button type="button" data-action="tui-size" data-value="80" aria-pressed="' + is80 + '" data-key="tui-80">80 列 x 24 行</button>' +
          '<button type="button" data-action="tui-size" data-value="160" aria-pressed="' + !is80 + '" data-key="tui-160">160 列 x 48 行</button>' +
        '</div>' +
        chip('模拟数据', 'warning') +
        '<span class="tui-caption">字符网格实时合成 · CJK 按终端双宽计算 · 颜色取自 §5.3 角色表</span>' +
      '</div>' +
      '<div class="tui-frame">' +
        '<pre class="tui ' + (is80 ? 'tui--80' : 'tui--160') + '" aria-label="AgentPulse Debian TUI ' + (is80 ? '80x24' : '160x48') + ' 线框渲染">' + body + '</pre>' +
      '</div>' +
      mappingHTML() +
    '</div>';
  }

  /* ══════════════════════════════════════════════════════════════════════
     设计说明（走查备注）
     ══════════════════════════════════════════════════════════════════════ */
  var NOTES = {
    overview: {
      title: '总览大盘（OverviewScreen）',
      lede: '一眼掌握全局运行状态：进度、卡滞与待处理决策，抬眼即见。',
      blocks: [
        { h: '视线顺序', items: ['系统状态与连接三态', '在飞 / 排队 / 告警三项指标', '正在执行的任务卡片', '待处理决策', '排队队列与阻塞源'] },
        { h: '交互', items: ['点任务卡片 → 底部详情浮层', '底部导航切换四屏', '断线时出现红色不可忽略横幅'] },
        { h: '规范对应', items: ['§4.3 屏 1 线框图', '§3.2 C 连接三态模型', '§2.4 事件分级'] }
      ]
    },
    tasks: {
      title: '任务与 Agent 矩阵（TasksScreen）',
      lede: '纵向铺开 FirstMate 队列，点开即可下钻工作目录、Herdr 窗格与微观会话。',
      blocks: [
        { h: '卡片内容', items: ['任务 ID 与类别（Scout / Ship）', '仓库、Harness、工作目录（等宽）', 'Herdr 窗格与实时状态', '近期终端输出片段'] },
        { h: '交互', items: ['筛选：全部 / 进行中 / 排队中 / 已完成', '阻塞任务显示阻塞源', '点击卡片打开详情浮层'] },
        { h: '规范对应', items: ['§4.3 屏 2 线框图', '§2.2 A Task 实体', '§1.4 会话降级矩阵'] }
      ]
    },
    feed: {
      title: '实时动态流（LiveFeedScreen）',
      lede: '以时间流呈现 Agent 的真实工作痕迹：状态流转、命令、返回结果。',
      blocks: [
        { h: '事件行', items: ['时间戳 + 来源标签（FirstMate / Herdr / Pi / Service）', '等宽正文，命令与路径保持英文', '严重度左侧色条', '子行显示退出码与摘要'] },
        { h: '行为', items: ['自动滚动开关（默认开）', '连接断开时事件流暂停并提示', 'Pi 会话缺失时显式标注降级'] },
        { h: '规范对应', items: ['§4.3 屏 3 线框图', '§2.4 事件分级与去重', '§3.2 WebSocket 封包'] }
      ]
    },
    alerts: {
      title: '异常提醒与阻断（AlertsScreen）',
      lede: '高亮汇总阻断事件、任务失败与等待人工决策的卡点；只读版本提供复制指令。',
      blocks: [
        { h: '卡片内容', items: ['严重度徽标与处理状态', '类别、任务、时间、闭环键', '决策选项与电脑处理指令', '复制指令按钮'] },
        { h: '打扰分级', items: ['CRITICAL：系统 Heads-up + 震动（设计约定）', 'WARNING：仅底部导航琥珀角标', 'INFO / DEBUG：只进动态流'] },
        { h: '规范对应', items: ['§4.3 屏 4 线框图', '§4.4 打扰分级策略', '§2.2 D Alert 实体'] }
      ]
    },
    landscape: {
      title: '横屏 · 电脑副屏模式',
      lede: '笔记本旁的横向支架场景：底部导航转为左侧 Navigation Rail，释放垂直空间。',
      blocks: [
        { h: '布局', items: ['左侧 Navigation Rail（4 项，48dp 靶区）', '左 40%：统计与任务列表', '右 60%：选定任务的终端输出与动态'] },
        { h: '行为', items: ['点选任务即刻切换右栏映像', '动态 / 告警页复用右栏', '连接三态横幅保持在顶部'] },
        { h: '规范对应', items: ['§4.5.1 横屏模式', '§4.2 C 间距与动效', '§4.3 四屏复用'] }
      ]
    },
    tablet: {
      title: '平板 / 折叠屏 · 极客桌面看板',
      lede: '展开为三栏看板，把「任务 - 窗格 - 事件」三者放在同一视野内对照。',
      blocks: [
        { h: '三栏', items: ['左 25%：全局指标、待处理决策、队列', '中 45%：在飞任务面板 + ANSI 终端映像', '右 30%：全局实时活动事件流'] },
        { h: '行为', items: ['左栏点选驱动中栏', '右栏事件流常驻自动滚动', '布局在 1080 宽画布内等比缩放'] },
        { h: '规范对应', items: ['§4.5.2 平板 / 折叠屏', '§3.1 接口字段', '§5.3 ANSI 角色'] }
      ]
    },
    tui: {
      title: 'Debian TUI 客户端',
      lede: 'WSL2 本机或纯 SSH 环境下的轻量看板；Rust + Ratatui，无需 GUI。',
      blocks: [
        { h: '两种尺寸', items: ['80 列 x 24 行紧凑型终端', '160 列 x 48 行宽屏三栏终端', '字符网格实时合成，非图片'] },
        { h: '渲染约定', items: ['真实制表符与方块字符', 'CJK 按终端双宽计列', '无 emoji，避免列对齐被破坏'] },
        { h: '规范对应', items: ['§5.1 Rust + Ratatui 选型', '§5.2 A / B 布局线框', '§5.3 16 色降级映射'] }
      ]
    }
  };

  function notesHTML(key, wide) {
    var n = NOTES[key] || NOTES.overview;
    return '<aside class="notes' + (wide ? ' notes--wide' : '') + '">' +
      '<h2 class="notes__title">' + esc(n.title) + '</h2>' +
      '<p class="notes__lede">' + esc(n.lede) + '</p>' +
      '<div class="notes__blocks">' + n.blocks.map(function (b) {
        return '<section class="notes__block"><h4 class="notes__h4">' + esc(b.h) + '</h4>' +
          '<ul class="notes__list">' + b.items.map(function (i) { return '<li><span>' + esc(i) + '</span></li>'; }).join('') + '</ul></section>';
      }).join('') + '</div>' +
    '</aside>';
  }

  /* ══════════════════════════════════════════════════════════════════════
     渲染
     ══════════════════════════════════════════════════════════════════════ */
  function renderStage() {
    var stage = byId('stage');
    var m = model();
    var active = document.activeElement;
    var focusKey = active && active.getAttribute ? active.getAttribute('data-key') : null;
    var html = '';

    if (state.view === 'phone') {
      html = '<div class="stage__viewport">' + phoneHTML(m) + '</div>' + notesHTML(state.screen, false);
    } else if (state.view === 'landscape') {
      html = '<div class="stage__viewport">' + landscapeHTML(m) + '</div>' + notesHTML('landscape', false);
    } else if (state.view === 'tablet') {
      html = '<div class="stage__viewport">' + tabletHTML(m) + '</div>' + notesHTML('tablet', false);
    } else {
      html = '<div class="stage__viewport">' + tuiHTML(m) + '</div>' + notesHTML('tui', true);
    }

    stage.className = 'stage' + (state.view === 'phone' || state.view === 'landscape' ? '' : ' stage--single');
    stage.innerHTML = html;

    if (focusKey) {
      var next = stage.querySelector('[data-key="' + focusKey.replace(/"/g, '\\"') + '"]');
      if (next) next.focus();
    }
    applyScale();
    scrollFeeds();
  }

  function applyScale() {
    var scaler = document.querySelector('.device-scaler');
    if (!scaler) return;
    var dw = parseFloat(scaler.style.getPropertyValue('--dw')) || 390;
    var dh = parseFloat(scaler.style.getPropertyValue('--dh')) || 844;
    var frameW = dw + 34, frameH = dh + 34;
    if (!state.fit) {
      scaler.style.setProperty('--device-scale', '1');
      return;
    }
    var host = scaler.parentElement.getBoundingClientRect();
    var availH = window.innerHeight - host.top - 24;
    var availW = host.width;
    var scale = Math.min(1, availH / frameH, availW / frameW);
    scale = Math.max(0.5, Math.round(scale * 1000) / 1000);
    scaler.style.setProperty('--device-scale', String(scale));
    scaler.style.height = Math.round(frameH * scale) + 'px';
  }

  function scrollFeeds() {
    var feeds = document.querySelectorAll('.js-feed');
    for (var i = 0; i < feeds.length; i++) {
      var host = feeds[i].closest('.app-body, .split__scroll');
      if (host) host.scrollTop = host.scrollHeight;
    }
  }

  function refreshStrip() {
    document.querySelectorAll('[data-control]').forEach(function (group) {
      var name = group.getAttribute('data-control');
      var current = name === 'tui' ? state.tuiSize : state[name];
      group.querySelectorAll('button[data-value]').forEach(function (btn) {
        var on = btn.getAttribute('data-value') === String(current) || (name === 'fit' && (btn.getAttribute('data-value') === 'fit') === state.fit);
        btn.setAttribute('aria-pressed', String(on));
      });
    });
  }

  /* ── 实时推流（模拟） ─────────────────────────────────────────────────── */
  function tickLive() {
    state.clock = new Date();

    if (state.connection === 'disconnected') {
      var backoff = [1, 2, 4, 8, 15];
      state.reconnect.next -= 1;
      if (state.reconnect.next <= 0) {
        state.reconnect.attempt += 1;
        state.reconnect.next = backoff[Math.min(state.reconnect.attempt - 1, backoff.length - 1)];
      }
    } else if (state.data === 'populated') {
      state.tick += 1;
      if (state.tick % 2 === 0 || state.connection === 'healthy') {
        var evt = window.APData.nextEvent(state.tick, lastEventSeq);
        lastEventSeq = evt.seq;
        state.feed.push(evt);
        if (state.feed.length > 40) state.feed.shift();
      }
    }

    var m = model();

    document.querySelectorAll('.js-clock').forEach(function (node) { node.textContent = clock(); });
    document.querySelectorAll('.js-reconnect').forEach(function (node) {
      node.textContent = '尝试 #' + state.reconnect.attempt + ' · 下次重试 ' + state.reconnect.next + 's';
    });
    document.querySelectorAll('.js-feed').forEach(function (node) {
      var scope = node.getAttribute('data-feed-scope');
      var events = feedAll();
      if (scope && scope !== 'all') {
        events = events.filter(function (ev) { return ev.task_id === scope; });
      }
      node.innerHTML = feedListHTML(events, '暂无事件');
    });
    var tui = document.querySelector('pre.tui');
    if (tui) {
      var is80 = state.tuiSize === '80';
      tui.innerHTML = is80 ? window.APTui.build80(m, state, state.clock) : window.APTui.build160(m, state, state.clock);
    }
    var caption = byId('live-caption');
    if (caption) {
      caption.textContent = state.connection === 'disconnected' ? '已暂停（连接中断）' : '每 2.6 秒合成一条事件';
    }
    if (state.autoScroll) scrollFeeds();
  }

  /* ── 交互 ─────────────────────────────────────────────────────────────── */
  var liveStatus = null;

  function announce(text) {
    if (!liveStatus) {
      liveStatus = document.createElement('div');
      liveStatus.className = 'sr-only';
      liveStatus.setAttribute('role', 'status');
      liveStatus.setAttribute('aria-live', 'polite');
      document.body.appendChild(liveStatus);
    }
    liveStatus.textContent = text;
  }

  function copyText(text, alertId) {
    function done() {
      state.copied = alertId;
      announce('指令已复制到剪贴板');
      renderStage();
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(function () { fallback(); });
    } else {
      fallback();
    }
    function fallback() {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', 'readonly');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); } catch (err) { /* 忽略：原型环境 */ }
      document.body.removeChild(ta);
      done();
    }
  }

  function handleStageClick(event) {
    var target = event.target.closest('[data-action]');
    if (!target) return;
    var action = target.getAttribute('data-action');

    if (action === 'set-screen') {
      state.screen = target.getAttribute('data-screen');
      state.sheetTaskId = null;
      renderStage();
    } else if (action === 'open-task') {
      state.sheetTaskId = target.getAttribute('data-task-id');
      renderStage();
      var close = document.querySelector('.sheet__close');
      if (close) close.focus();
    } else if (action === 'close-sheet') {
      state.sheetTaskId = null;
      renderStage();
    } else if (action === 'select-task') {
      state.selectedTaskId = target.getAttribute('data-task-id');
      renderStage();
    } else if (action === 'filter') {
      state.filter = target.getAttribute('data-filter');
      renderStage();
    } else if (action === 'toggle-autoscroll') {
      state.autoScroll = !state.autoScroll;
      announce('自动滚动已' + (state.autoScroll ? '开启' : '关闭'));
      renderStage();
    } else if (action === 'clear-read') {
      state.clearedAck = true;
      announce('已清空已读告警');
      renderStage();
    } else if (action === 'retry') {
      state.data = 'populated';
      state.connection = 'healthy';
      state.scenario = 'steady';
      state.tick = 0;
      state.feed = [];
      announce('已重新拉取聚合数据');
      refreshStrip();
      renderStage();
    } else if (action === 'copy') {
      copyText(target.getAttribute('data-copy'), target.getAttribute('data-key'));
    } else if (action === 'tui-size') {
      state.tuiSize = target.getAttribute('data-value');
      refreshStrip();
      renderStage();
    }
  }

  function handleStripClick(event) {
    var btn = event.target.closest('button[data-value]');
    if (!btn) return;
    var group = btn.closest('[data-control]');
    if (!group) return;
    var name = group.getAttribute('data-control');
    var value = btn.getAttribute('data-value');

    if (name === 'view') {
      state.view = value;
      state.sheetTaskId = null;
    } else if (name === 'fit') {
      state.fit = value === 'fit';
    } else if (name === 'tui') {
      state.tuiSize = value;
    } else if (name === 'connection') {
      state.connection = value;
      state.reconnect = { attempt: 1, next: 1 };
      if (value === 'disconnected') state.autoScroll = true;
    } else if (name === 'data') {
      state.data = value;
    } else if (name === 'scenario') {
      state.scenario = value;
    }
    refreshStrip();
    renderStage();
    announce(group.getAttribute('aria-label') + '：' + btn.textContent.trim());
  }

  function handleKeydown(event) {
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    var tag = (event.target.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea') return;

    if (event.key === 'Escape') {
      if (state.sheetTaskId) {
        state.sheetTaskId = null;
        renderStage();
      }
      return;
    }
    var screens = { '1': 'overview', '2': 'tasks', '3': 'feed', '4': 'alerts' };
    if (screens[event.key] && state.view !== 'tui') {
      state.screen = screens[event.key];
      renderStage();
      return;
    }
    if (event.key === 't' || event.key === 'T') {
      state.tuiSize = state.tuiSize === '80' ? '160' : '80';
      refreshStrip();
      renderStage();
    }
  }

  /* ── 启动 ─────────────────────────────────────────────────────────────── */
  function init() {
    byId('strip').addEventListener('click', handleStripClick);
    byId('stage').addEventListener('click', handleStageClick);
    document.addEventListener('keydown', handleKeydown);
    window.addEventListener('resize', function () { applyScale(); });
    var body = byId('stage');
    refreshStrip();
    renderStage();
    setInterval(tickLive, 2600);
    setInterval(function () {
      if (state.connection !== 'disconnected') return;
      var m = model();
      document.querySelectorAll('.js-reconnect').forEach(function (node) {
        node.textContent = '尝试 #' + state.reconnect.attempt + ' · 下次重试 ' + state.reconnect.next + 's';
      });
    }, 1000);
  }

  return { init: init, state: state };
})();

document.addEventListener('DOMContentLoaded', function () { window.APApp.init(); });

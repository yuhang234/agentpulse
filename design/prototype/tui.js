/* ============================================================================
   AgentPulse 原型 · Debian TUI 渲染器
   ---------------------------------------------------------------
   用真实字符网格（含 CJK 双宽计算）合成 spec §5.2 的 80x24 与 160x48 终端画面，
   输出为等宽 <pre> 内的 span 序列，颜色取自 §5.3 的语义角色。
   不使用图片，不使用 emoji（emoji 破坏终端列对齐）。
   ========================================================================= */

window.APTui = (function () {
  'use strict';

  /* ── 终端列宽：CJK 与全角字符占 2 列 ───────────────────────────────────── */
  function charWidth(cp) {
    if (cp >= 0x1100 && (
      cp <= 0x115f ||
      cp === 0x2329 || cp === 0x232a ||
      (cp >= 0x2e80 && cp <= 0xa4cf && cp !== 0x303f) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xfe10 && cp <= 0xfe19) ||
      (cp >= 0xfe30 && cp <= 0xfe6f) ||
      (cp >= 0xff00 && cp <= 0xff60) ||
      (cp >= 0xffe0 && cp <= 0xffe6)
    )) return 2;
    return 1;
  }

  function strWidth(text) {
    var w = 0;
    for (var i = 0; i < text.length; i++) {
      var cp = text.codePointAt(i);
      if (cp > 0xffff) i++;
      w += charWidth(cp);
    }
    return w;
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* ── 字符网格 ─────────────────────────────────────────────────────────── */
  function Grid(w, h) {
    this.w = w; this.h = h;
    this.cells = [];
    for (var r = 0; r < h; r++) {
      var row = [];
      for (var c = 0; c < w; c++) row.push({ ch: ' ', cls: null, cont: false });
      this.cells.push(row);
    }
  }

  Grid.prototype.put = function (row, col, text, cls, maxWidth) {
    if (row < 0 || row >= this.h) return col;
    var limit = this.w;
    if (typeof maxWidth === 'number') limit = Math.min(limit, col + maxWidth);
    var c = col;
    for (var i = 0; i < text.length; i++) {
      var cp = text.codePointAt(i);
      if (cp > 0xffff) i++;
      var width = charWidth(cp);
      if (c + width > limit) break;
      this.cells[row][c] = { ch: String.fromCodePoint(cp), cls: cls, cont: false };
      if (width === 2) this.cells[row][c + 1] = { ch: '', cls: cls, cont: true };
      c += width;
    }
    return c;
  };

  Grid.prototype.repeat = function (row, col, len, glyph, cls) {
    var s = '';
    for (var i = 0; i < len; i++) s += glyph;
    return this.put(row, col, s, cls);
  };

  Grid.prototype.box = function (x, y, w, h, cls) {
    this.put(y, x, '┌' + rep('─', w - 2) + '┐', cls);
    for (var r = y + 1; r < y + h - 1; r++) {
      this.put(r, x, '│', cls);
      this.put(r, x + w - 1, '│', cls);
    }
    this.put(y + h - 1, x, '└' + rep('─', w - 2) + '┘', cls);
  };

  Grid.prototype.toHTML = function () {
    var out = [];
    for (var r = 0; r < this.h; r++) {
      var html = '';
      var current = null;
      var buffer = '';
      for (var c = 0; c < this.w; c++) {
        var cell = this.cells[r][c];
        if (cell.cont) continue;
        if (cell.cls !== current) {
          if (buffer) html += wrap(current, buffer);
          current = cell.cls;
          buffer = cell.ch;
        } else {
          buffer += cell.ch;
        }
      }
      if (buffer) html += wrap(current, buffer);
      out.push(html);
    }
    return out.join('\n');
  };

  function rep(glyph, n) {
    var s = '';
    for (var i = 0; i < n; i++) s += glyph;
    return s;
  }

  function wrap(cls, text) {
    return cls ? '<span class="' + cls + '">' + esc(text) + '</span>' : esc(text);
  }

  /* ── 通用取值 ─────────────────────────────────────────────────────────── */
  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  function clockText(date) {
    return pad2(date.getHours()) + ':' + pad2(date.getMinutes()) + ':' + pad2(date.getSeconds());
  }

  function stampText(date) {
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) +
      ' ' + clockText(date) + ' UTC+8';
  }

  function activeTask(model) {
    return model.tasks.filter(function (t) {
      return t.state === 'in_flight' || t.state === 'needs-decision';
    })[0] || null;
  }

  function queuedTasks(model) {
    return model.tasks.filter(function (t) { return t.state === 'queued'; });
  }

  function stateWord(task) {
    if (!task) return 'IDLE';
    if (task.state === 'needs-decision') return 'BLOCKED';
    return String(task.agent_status || task.state).toUpperCase();
  }

  function stateRole(task) {
    var word = stateWord(task);
    if (word === 'WORKING') return 'r-primary';
    if (word === 'BLOCKED') return 'r-warning';
    if (word === 'DONE') return 'r-success';
    if (word === 'FAILED') return 'r-error';
    return 'r-muted';
  }

  function connRole(state) {
    if (state.connection === 'healthy') return 'r-success';
    if (state.connection === 'stale') return 'r-warning';
    return 'r-error';
  }

  function connWord(state) {
    return window.APData.CONNECTION[state.connection].label;
  }

  function mark(role) {
    return role === 'r-warning' ? '!' : (role === 'r-error' ? 'x' : 'o');
  }

  /* ══════════════════════════════════════════════════════════════════════
     80 x 24 紧凑型终端（spec §5.2 A）
     ══════════════════════════════════════════════════════════════════════ */
  function build80(model, state, date) {
    var W = 80, H = 24;
    var g = new Grid(W, H);
    var B = 'r-muted';        /* 边框与灰字（Muted #8B949E） */
    var L = 38;               /* 左栏内宽  1..38  */
    var R = 39;               /* 右栏内宽 40..78  */
    var split = 39;           /* 分栏符列 */

    /* 顶栏：标题 + 连接状态 */
    var titleCore = 'AgentPulse · FirstMate+Herdr+Pi ';
    var stateCore = '(' + connWord(state) + ')';
    var labelWidth = strWidth(titleCore) + strWidth(stateCore);
    var padTotal = (W - 2) - (labelWidth + 2);
    var padLeft = Math.floor(padTotal / 2);
    var padRight = padTotal - padLeft;
    g.put(0, 0, '┌', B);
    g.repeat(0, 1, padLeft, '─', B);
    g.put(0, 1 + padLeft, ' ', null);
    var c = g.put(0, 2 + padLeft, titleCore, 'r-text');
    c = g.put(0, c, stateCore, connRole(state));
    g.put(0, c, ' ' + rep('─', padRight) + '┐', B);

    /* 标签行 */
    g.put(1, 0, '│', B);
    var tabs = [
      { key: '1', label: 'Overview', on: true },
      { key: '2', label: 'Tasks', n: model.tasks.length },
      { key: '3', label: 'LiveFeed', on: false },
      { key: '4', label: 'Alerts', n: model.activeAlerts.length }
    ];
    var col = 2;
    tabs.forEach(function (tab) {
      var text = '[' + tab.key + ']' + tab.label + (tab.n !== undefined ? '(' + tab.n + ')' : '');
      var cls = tab.on ? 'r-focus' : 'r-muted';
      col = g.put(1, col, text, cls);
      col = g.put(1, col, '  ', null);
    });
    var clock = clockText(date);
    g.put(1, W - 3 - clock.length, clock, 'r-text');
    g.put(1, W - 1, '│', B);

    /* 分栏线 */
    function rule(row, left, mid, right) {
      g.put(row, 0, '├' + rep('─', L) + left, B);
      g.put(row, split, mid, B);
      g.put(row, split + 1, rep('─', R), B);
      g.put(row, W - 1, right, B);
    }
    rule(2, '┬', '┬', '┤');
    rule(16, '┼', '┼', '┤');

    /* 侧边界 */
    function side(row, cls) {
      g.put(row, 0, '│', B);
      g.put(row, split, '│', cls || B);
      g.put(row, W - 1, '│', B);
    }

    /* ── 区块 A（行 3..15）左：当前任务 ── */
    var task = activeTask(model);
    var leftA = [];
    if (model.ok === false) {
      leftA.push(['◆ ACTIVE TASK', 'r-error']);
      leftA.push(['! 聚合数据不可用 (HTTP 500)', 'r-error']);
      leftA.push(['  collector firstmate failed', 'r-muted']);
      leftA.push(['  ENOENT state/home-summary.json', 'r-muted']);
      leftA.push(['', null]);
      leftA.push(['  retry 3/5 · backoff 2s', 'r-warning']);
    } else if (!task) {
      leftA.push(['◆ ACTIVE TASK', 'r-primary']);
      leftA.push(['(none) 无在飞任务', 'r-dim']);
      leftA.push(['', null]);
      leftA.push(['队列已清空，等待 captain 派单', 'r-dim']);
    } else {
      var role = stateRole(task);
      leftA.push(['◆ ACTIVE TASK', 'r-primary']);
      leftA.push(['ID: ' + task.id + ' [' + (task.kind === 'scout' ? 'Scout' : 'Ship') + ']', 'r-bright']);
      leftA.push(['Repo: ' + task.repo + '   Harness: ' + task.harness, 'r-text']);
      leftA.push(['Status: ' + stateWord(task) + ' ' + mark(role), role]);
      leftA.push(['Doing: ' + task.doing, 'r-text']);
      leftA.push(['Time: ' + task.duration, 'r-text']);
      leftA.push(['', null]);
      leftA.push(['◆ HERDR PANE', 'r-primary']);
      var paneRef = task.pane_ref || '—';
      leftA.push(['Pane: ' + paneRef + '  WS: ' + (task.assigned_pane_id ? task.assigned_pane_id.split(':')[0] : '—'), 'r-focus']);
      leftA.push(['PID:  ' + (task.agent_pid || '—') + '  SRC: pane', 'r-text']);
      leftA.push(['Session: — harness=' + task.harness, 'r-muted']);
      leftA.push(['', null]);
      leftA.push(['◆ DECISION: none', 'r-primary']);
    }
    var rowA = 3;
    leftA.forEach(function (line) {
      g.put(rowA, 1, ' ', null);
      g.put(rowA, 2, line[0], line[1] || 'r-dim', L - 1);
      side(rowA);
      rowA++;
    });
    for (; rowA <= 15; rowA++) side(rowA);

    /* ── 区块 A（行 3..15）右：窗格输出 ── */
    var rightA = [['◆ RECENT LOG / PANE OUTPUT', 'r-primary']];
    if (model.error) {
      rightA.push(['(no pane output · 聚合失败)', 'r-dim']);
    } else if (model.feed.length === 0) {
      rightA.push(['(no output yet · 窗格静默)', 'r-dim']);
    } else {
      model.feed.slice(-6).forEach(function (ev) {
        var cls = ev.severity === 'warning' ? 'r-warning' : (ev.severity === 'error' || ev.severity === 'critical' ? 'r-error' : 'r-text');
        rightA.push([ev.t + ' ' + (ev.source === 'herdr' ? 'pane' : ev.source), cls]);
        if (ev.sub) rightA.push(['  ' + ev.sub, 'r-muted']);
      });
    }
    var rowB = 3;
    rightA.forEach(function (line) {
      if (rowB > 15) return;
      g.put(rowB, split + 1, ' ', null);
      g.put(rowB, split + 2, line[0], line[1] || 'r-muted', R - 1);
      side(rowB);
      rowB++;
    });
    for (; rowB <= 15; rowB++) side(rowB);

    /* ── 区块 B（行 17..20）：队列 / 告警 ── */
    rule(21, '┴', '┴', '┤');
    var queued = queuedTasks(model);
    var leftB = [['◆ QUEUED BACKLOG (' + queued.length + ')', 'r-primary']];
    if (!model.ok) {
      leftB.push(['(unavailable · 采集失败)', 'r-error']);
    } else if (queued.length === 0) {
      leftB.push(['(empty) 队列为空', 'r-dim']);
    } else {
      queued.slice(0, 2).forEach(function (t) {
        leftB.push(['• ' + t.id + '  (blocked)', 'r-warning']);
      });
      leftB.push(['  ↳ blocked by ' + queued[0].blocked_by, 'r-muted']);
    }
    var rowC = 17;
    leftB.forEach(function (line) {
      if (rowC > 20) return;
      g.put(rowC, 2, line[0], line[1] || 'r-dim', L - 1);
      side(rowC);
      rowC++;
    });
    for (; rowC <= 20; rowC++) side(rowC);

    var alerts = model.activeAlerts || [];
    var rightB = [['◆ LATEST ALERTS & DECISIONS', 'r-primary']];
    if (!model.ok) {
      rightB.push(['x 数据源读取失败 (HTTP 500)', 'r-error']);
      rightB.push(['  key: collector-firstmate', 'r-muted']);
    } else if (alerts.length === 0) {
      rightB.push(['(None. Workflow running smoothly)', 'r-dim']);
    } else {
      alerts.slice(0, 3).forEach(function (a) {
        var cls = a.severity === 'critical' ? 'r-error' : (a.severity === 'warning' ? 'r-warning' : 'r-muted');
        rightB.push([mark(cls) + ' ' + a.title + '  [' + a.category + ']', cls]);
      });
    }
    var rowD = 17;
    rightB.forEach(function (line) {
      if (rowD > 20) return;
      g.put(rowD, split + 2, line[0], line[1] || 'r-muted', R - 1);
      side(rowD);
      rowD++;
    });
    for (; rowD <= 20; rowD++) side(rowD);

    /* ── 底栏与边框 ── */
    g.put(22, 0, '│', B);
    g.put(22, 1, '[Tab]切换面板  [j/k]上下选择  [Enter]展开详情  [r]刷新  [q]退出', 'r-muted', W - 2);
    g.put(22, W - 1, '│', B);
    g.put(23, 0, '└' + rep('─', W - 2) + '┘', B);

    return g.toHTML();
  }

  /* ══════════════════════════════════════════════════════════════════════
     160 x 48 宽屏终端（spec §5.2 B）
     ══════════════════════════════════════════════════════════════════════ */
  function build160(model, state, date) {
    var W = 160, H = 48;
    var g = new Grid(W, H);
    var B = 'r-muted';
    var COL1 = { x: 1, w: 38 };              /* 1..38    */
    var COL2 = { x: 40, w: 66 };             /* 40..105  */
    var COL3 = { x: 107, w: 52 };            /* 107..158 */
    var SEP1 = 39, SEP2 = 106;

    function span(x, w) { return { x: x, w: w }; }

    /* 顶栏 */
    var title = ' AgentPulse 监控中心 (Debian TUI v1.0) ';
    var tw = strWidth(title);
    var inner = W - 2;
    var padLeft = Math.floor((inner - tw) / 2);
    var padRight = inner - tw - padLeft;
    g.put(0, 0, '┌', B);
    g.repeat(0, 1, padLeft, '─', B);
    var after = g.put(0, 1 + padLeft, title, 'r-bright');
    g.put(0, after, rep('─', padRight) + '┐', B);

    /* 状态栏 */
    var task = activeTask(model);
    var queued = queuedTasks(model);
    var alerts = model.activeAlerts || [];
    g.put(1, 0, '│', B);
    var bar = [
      ['主机: WSL2-Debian', 'r-text'],
      [' │ ', 'r-dim'],
      ['连接: 127.0.0.1:7890 ' + mark(connRole(state)) + ' (Delay: ' + window.APData.CONNECTION[state.connection].delay + ')', connRole(state)],
      [' │ ', 'r-dim'],
      ['在飞任务: ' + (model.ok ? String(model.metrics.inFlight) : '—'), 'r-text'],
      [' │ ', 'r-dim'],
      ['排队: ' + (model.error ? '—' : String(queued.length)), 'r-text'],
      [' │ ', 'r-dim'],
      ['告警: ' + alerts.length, alerts.length ? 'r-warning' : 'r-text'],
      [' │ ', 'r-dim'],
      ['当前时间: ' + stampText(date), 'r-text']
    ];
    var bc = 2;
    bar.forEach(function (part) { bc = g.put(1, bc, part[0], part[1], W - 2); });
    g.put(1, bc, rep(' ', Math.max(0, W - 2 - bc)), null);
    g.put(1, W - 1, '│', B);

    /* 分栏线 */
    function rule(row, left, mid, right, l) {
      g.put(row, 0, l + rep('─', COL1.w) + left, B);
      g.put(row, SEP1, mid, B);
      g.put(row, SEP1 + 1, rep('─', COL2.w), B);
      g.put(row, SEP2, mid, B);
      g.put(row, SEP2 + 1, rep('─', COL3.w), B);
      g.put(row, W - 1, right, B);
    }
    rule(2, '┬', '┬', '┤', '├');
    rule(4, '┼', '┼', '┤', '├');
    rule(45, '┴', '┴', '┤', '├');

    function side(row) {
      g.put(row, 0, '│', B);
      g.put(row, SEP1, '│', B);
      g.put(row, SEP2, '│', B);
      g.put(row, W - 1, '│', B);
    }

    /* 栏目标题 */
    var titles = [
      { c: COL1, text: '◆ 任务流水线 (Tasks Pipeline)', cls: 'r-bright' },
      { c: COL2, text: '◆ 终端窗格实时映像 (Herdr Pane ' + (task && task.assigned_pane_id ? task.assigned_pane_id : '—') + ')', cls: 'r-focus' },
      { c: COL3, text: '◆ 实时事件动态 (Activity Feed)', cls: 'r-bright' }
    ];
    titles.forEach(function (t) {
      g.put(3, t.c.x, ' ', null);
      g.put(3, t.c.x + 1, t.text, t.cls, t.c.w - 1);
    });
    side(3);

    /* ── 左栏：任务流水线 ── */
    var left = [];
    if (model.error) {
      left.push(['! 聚合数据不可用', 'r-error']);
      left.push(['  HTTP 500 · GET /api/v1/overview', 'r-muted']);
      left.push(['  collector firstmate failed', 'r-muted']);
      left.push(['  key: collector-firstmate', 'r-muted']);
      left.push([]);
      left.push(['  retry 3/5 · backoff 2s', 'r-warning']);
      left.push([]);
      left.push(['  服务本身存活:', 'r-muted']);
      left.push(['  /api/v1/health = degraded', 'r-text']);
    } else if (!task) {
      left.push(['▶ [In Flight]', 'r-dim']);
      left.push(['  (none) 无在飞任务', 'r-dim']);
      left.push([]);
      left.push(['⏸ [Queued Backlog]', 'r-dim']);
      left.push(['  (empty) 队列为空', 'r-dim']);
    } else {
      left.push(['▶ [In Flight]', 'r-primary']);
      left.push(['  ● ' + task.id, 'r-bright']);
      left.push(['    Kind: ' + (task.kind === 'scout' ? 'Scout' : 'Ship') + '  Repo: ' + task.repo, 'r-text']);
      left.push(['    Harness: ' + task.harness, 'r-text']);
      left.push(['    Pane: ' + (task.pane_ref || '—'), 'r-focus']);
      left.push(['    Duration: ' + task.duration, 'r-text']);
      left.push(['    Status: ' + stateWord(task) + ' ' + mark(stateRole(task)), stateRole(task)]);
      left.push(['    Source: pane (herdr-native)', 'r-muted']);
      left.push([]);
      left.push(['⏸ [Queued Backlog]', 'r-warning']);
      queued.forEach(function (t) {
        left.push(['  • ' + t.id, 'r-text']);
        left.push(['    Kind: ' + (t.kind === 'scout' ? 'Scout' : 'Ship'), 'r-muted']);
        left.push(['    Blocked by: ' + t.blocked_by, 'r-warning']);
      });
    }
    left.push([]);
    left.push(['◆ [Metrics]', 'r-primary']);
    left.push(['  In Flight : ' + (model.ok ? model.metrics.inFlight : '—'), 'r-text']);
    left.push(['  Queued    : ' + (model.ok ? model.metrics.queued : '—'), 'r-text']);
    left.push(['  Done      : ' + (model.ok ? model.metrics.done : '—'), 'r-text']);
    left.push(['  Alerts    : ' + alerts.length, alerts.length ? 'r-warning' : 'r-text']);
    left.push(['  Agents    : ' + (model.ok ? model.metrics.workingAgents + ' working' : '—'), 'r-text']);
    left.push([]);
    left.push(['◆ [Collectors]', 'r-primary']);
    left.push(['  firstmate : ' + (model.ok ? 'ok   1792313390' : 'fail ENOENT'), model.ok ? 'r-success' : 'r-error']);
    left.push(['  herdr     : ok   proto 22', 'r-success']);
    left.push(['  pi        : ok   0 session', 'r-muted']);
    left.push([]);
    left.push(['◆ [Sources]', 'r-primary']);
    left.push(['  state/home-summary.json', 'r-muted']);
    left.push(['    fm-secondmate-home-summary.v1', 'r-dim']);
    left.push(['  herdr.sock', 'r-muted']);
    left.push(['    events.subscribe', 'r-dim']);
    left.push(['  ~/.pi/agent/sessions/', 'r-muted']);
    left.push(['    no session (harness=' + (task ? task.harness : '—') + ')', 'r-dim']);
    left.push([]);
    left.push(['◆ [Retention]', 'r-primary']);
    left.push(['  1000 evt/task · 7d · sqlite', 'r-text']);

    var lr = 5;
    left.forEach(function (line) {
      if (lr > 44) return;
      g.put(lr, COL1.x + 1, line[0] || '', line[1] || null, COL1.w - 1);
      side(lr);
      lr++;
    });
    for (; lr <= 44; lr++) side(lr);

    /* ── 中栏：窗格实时映像 ── */
    var mid = [];
    var hr = rep('─', COL2.w - 6);
    if (!model.ok) {
      mid.push(['[CWD]   (unavailable)', 'r-muted']);
      mid.push(['[Agent] —            [Status] UNKNOWN', 'r-dim']);
      mid.push([hr, 'r-muted']);
      mid.push([' 采集失败，无法读取 Herdr 窗格。', 'r-warning']);
      mid.push([' 上一次成功映像: 16:45:08 (w1D:p2)', 'r-muted']);
    } else if (!task) {
      mid.push(['[CWD]   —', 'r-muted']);
      mid.push(['[Agent] —            [Status] IDLE', 'r-dim']);
      mid.push([hr, 'r-muted']);
      mid.push([' 无在飞任务，无窗格映像。', 'r-dim']);
    } else {
      mid.push(['[CWD]   ' + task.worktree, 'r-text']);
      mid.push(['[Agent] ' + task.agent + ' (PID: ' + task.agent_pid + ')   [Status] ' + stateWord(task), stateRole(task)]);
      mid.push([hr, 'r-muted']);
      mid.push(['└ Tip: Press shift+tab to cycle modes (default, accept-edits)', 'r-muted']);
      mid.push([hr, 'r-muted']);
      mid.push(['> checking data-source inventory for FirstMate and Herdr...', 'r-text']);
      mid.push(['> reading ~/.pi/agent/sessions/ directories...', 'r-text']);
      mid.push(['> extracting JSON schemas and event sequences...', 'r-text']);
      mid.push(['$ herdr agent list', 'r-success']);
      mid.push(['{"id":"cli:agent:list","result":{"agents":[{', 'r-muted']);
      mid.push(['   "agent":"agy","pane_id":"w1D:p2","agent_status":"working"}]}}', 'r-muted']);
      mid.push(['$ bin/fm-fleet-snapshot.sh --json | jq .tasks[0].current_state', 'r-success']);
      mid.push(['"working"', 'r-text']);
      mid.push(['$ herdr pane process-info --pane w1D:p2', 'r-success']);
      mid.push(['foreground_processes: [ { name: "agy", pid: 308453,', 'r-muted']);
      mid.push(['                         cwd: "…/agentpulse-0ede77/1/agentpulse" } ]', 'r-muted']);
      mid.push([hr, 'r-muted']);
      mid.push(['> esc to cancel', 'r-text']);
      mid.push(['esc to cancel                                 Gemini 3.8 Flash · high', 'r-dim']);
      mid.push(['> scanning state/*.meta for herdr_pane_id …', 'r-text']);
      mid.push(['  agentpulse-design.meta → pane_id=w1D:p2 (确定性关联)', 'r-muted']);
      mid.push(['  agentpulse-service.meta → 未开工，跳过', 'r-muted']);
      mid.push(['> reading backlog: ## In flight / ## Queued / ## Done', 'r-text']);
      mid.push(['$ tasks-axi list --fields blocked,blocked_by,created', 'r-success']);
      mid.push(['count: 3  in_flight 1  queued 2  done 0', 'r-text']);
      mid.push([hr, 'r-muted']);
      mid.push(['ring buffer: 增量拉取游标 seq=1042 · 已转储 13,486 行', 'r-muted']);
      mid.push(['sqlite: 7d retention · 50k rows · VACUUM ok', 'r-muted']);
    }
    var mr = 5;
    mid.forEach(function (line) {
      if (mr > 44) return;
      g.put(mr, COL2.x + 1, line[0] || '', line[1] || null, COL2.w - 1);
      side(mr);
      mr++;
    });
    for (; mr <= 44; mr++) side(mr);

    /* ── 右栏：事件流 ── */
    var right = [];
    if (!model.ok) {
      right.push(['16:45:11 [Service] HTTP 500 overview', 'r-error']);
      right.push(['16:45:11 [FM]      collector ENOENT', 'r-error']);
      right.push(['16:45:10 [Herdr]   pane w1D:p2 ok', 'r-muted']);
      right.push(['16:45:10 [Service] retry 3/5 backoff 2s', 'r-warning']);
    } else if (model.feed.length === 0) {
      right.push(['(no events · 事件流为空)', 'r-dim']);
    } else {
      model.feed.slice(-16).forEach(function (ev) {
        var src = ev.source === 'firstmate' ? 'FM     ' : (ev.source === 'herdr' ? 'Herdr  ' : (ev.source === 'pi' ? 'Pi     ' : 'Service'));
        var cls = ev.severity === 'warning' ? 'r-warning' : ((ev.severity === 'error' || ev.severity === 'critical') ? 'r-error' : 'r-text');
        right.push([ev.t + ' [' + src + '] ' + ev.text, cls]);
        if (ev.sub) right.push(['            ' + ev.sub, 'r-muted']);
      });
    }
    right.push([]);
    right.push(['◆ 摘要', 'r-primary']);
    right.push(['  待处理决策: ' + (model.decisions.length || 0), model.decisions.length ? 'r-warning' : 'r-dim']);
    right.push(['  活跃告警  : ' + alerts.length, alerts.length ? 'r-warning' : 'r-dim']);
    right.push(['  事件序号  : seq=' + (model.metrics.seq === null ? '—' : model.metrics.seq), 'r-dim']);
    right.push(['  连接状态  : ' + connWord(state), connRole(state)]);

    var rr = 5;
    right.forEach(function (line) {
      if (rr > 44) return;
      g.put(rr, COL3.x + 1, line[0] || '', line[1] || null, COL3.w - 1);
      side(rr);
      rr++;
    });
    for (; rr <= 44; rr++) side(rr);

    /* 底栏 */
    var keys = ' 快捷键: [1-4]视图切换  [Tab]遍历窗格  [j/k/↑/↓]导航  [Enter]打开详情浮层  [t]进入全屏终端跟随  [a]告警中心  [r]强制数据重对齐  [?]帮助  [q]退出';
    g.put(46, 0, '│', B);
    g.put(46, 1, keys, 'r-muted', W - 2);
    g.put(46, W - 1, '│', B);
    g.put(47, 0, '└' + rep('─', W - 2) + '┘', B);

    return g.toHTML();
  }

  return {
    build80: build80,
    build160: build160,
    strWidth: strWidth
  };
})();

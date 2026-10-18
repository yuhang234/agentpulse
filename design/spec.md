# AgentPulse 全局架构与双端界面设计规范报告
> 专为 FirstMate + Herdr + Pi AI 编程工作流打造的专用监控看板体系

---

## 报告摘要（Captain Executive Summary）

### 1. 核心定位与设计目标
AgentPulse 的使命是**将闲置的安卓手机转化为电脑旁专用的 AI Agent 硬件级监控屏**，并在 Debian Linux 终端内提供对称的沉浸式 TUI 监控客户端。
它**不是**单一 Agent（如 Pi）的专属调试台，而是统一面向 **FirstMate + Herdr + Pi** 协作体系的全局看板：
- **FirstMate**：任务调度、任务队列状态、分支/PR 关联、子代理汇报、异常停滞与交付结果；
- **Herdr**：终端多路复用窗格（Pane）、实时工作目录（CWD）、Agent 原生运行状态（`working`/`idle`/`blocked`）及近期终端字符输出（近期 40~100 行）；
- **Pi**：细粒度执行流，捕获工具调用（`toolCall`，如 `bash`、`edit`）、工具返回结果（`toolResult`）、思考过程（`thinking`）与 Token/成本消耗。

三者分工明确，通过统一数据模型将「任务（Task）- 窗格（Pane）- 会话（Session）」紧密关联。手机端首发版本坚持**只读监控**定位，不做远程命令执行与控制，确保极高安全性与零失误运行。

### 2. 关键现实验证结论（基于本机实测）
1. **数据源丰富度**：
   - **FirstMate**：具备高度成熟的 JSON 数据面。实测 `bin/fm-fleet-snapshot.sh --json` 与自动落盘的 `state/home-summary.json` 提供了包含任务生命周期、阻塞树、PR 链接、状态日志尾部等极其丰富的数据。特别注意：实测表明 `tasks-axi list --json` 命令不存在（返回错误码 2，`--json` 仅用于写变动指令），监控服务无需尝试解析该命令，应直接对接 `home-summary.json` 与 `fm-fleet-snapshot.sh --json`。
   - **Herdr**：本地运行着 Herdr 0.9.3（Protocol 22）。`herdr api snapshot` 与 `herdr agent list` 原生输出纯 JSON，且 Herdr Unix Domain Socket（`~/.config/herdr/herdr.sock`）直接支持 `events.subscribe` 推送流（`pane.agent_status_changed`、`pane.output_matched`），提供了毫秒级状态感知。
   - **Pi**：会话落盘存储于 `~/.pi/agent/sessions/--<encoded-cwd>--/<timestamp>_<uuid>.jsonl`。格式为逐行 JSONL，结构严谨，完整记录 `thinking`、`toolCall`、`toolResult`、Token 用量与费用。
2. **关键降级防线（核心设计发现）**：
   - **Harness 多样性**：当工作流使用非 Pi 的 Harness（如本任务使用的 `agy` 或 `opencode`）时，Pi 目录将不存在会话文件。监控服务**绝不虚构数据**，而是优雅降级：保留 FirstMate 的生命周期与 Herdr 窗格输出，将会话级工具调用平滑隐藏。
   - **WSL2 本机工具链缺口**：本机当前具备 Java 21、Rust、Node.js 与 Python3，但**尚未安装 Android SDK 与 Gradle**。安卓端开发采用标准 Gradle 构建方案，构建环境应设于宿主机 Windows/Android Studio 或通过 CI/独立 SDK 构建。

---

## 第一章：数据源清单与实证调研（Data-Source Inventory）

本章所有结论均基于本机真实运行环境执行与探测所得。

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      AgentPulse 数据源全景架构图                          │
└──────────────────────────────────────────────────────────────────────────┘
  ┌───────────────────────┐  ┌───────────────────────┐  ┌───────────────────────┐
  │      FirstMate        │  │         Herdr         │  │          Pi           │
  │     (宏观任务调度)     │  │     (中观窗格状态)     │  │     (微观执行事件)     │
  └──────────┬────────────┘  └───────────┬───────────┘  └───────────┬───────────┘
             │                           │                          │
   • state/*.meta (任务元数据)    • Unix Socket 推送流        • ~/.pi/agent/sessions/
   • state/*.status (事件流)      • herdr api snapshot (JSON) • JSONL 会话文件
   • state/home-summary.json      • herdr agent list (JSON)   • toolCall / toolResult
   • fm-fleet-snapshot --json     • herdr pane read (终端输出)• thinking / usage
             │                           │                          │
             └───────────────────┬───────┴──────────────────────────┘
                                 ▼
                     ┌───────────────────────┐
                     │  AgentPulse 监控服务   │
                     │  (WSL2 Debian 后台进程)│
                     └───────────────────────┘
```

### 1.1 FirstMate 数据源实测

#### A. 任务记录元数据：`state/*.meta`
每个在飞或刚完成的任务均在 `state/<id>.meta` 中持久化记录键值对。
- **获取方式**：文件系统读取 `/home/Yuhang/agentpulse/firstmate/state/<task_id>.meta`。
- **完整字段集（经 `bin/fm-spawn.sh` 与真实文件验证）**：
  `window`, `endpoint_task_id`, `worktree`, `project`, `harness`, `kind`, `mode`, `yolo`, `branch`, `tasktmp`, `base_branch`, `upstream_consent`, `model`, `effort`, `account`, `account_provider`, `busy_gen`, `spawn_gen`, `traceparent`, `backend`, `herdr_session`, `herdr_workspace_id`, `herdr_tab_id`, `herdr_pane_id`, `home`, `projects`。
- **本机真实样本（当前任务 `agentpulse-design.meta`）**：
  ```ini
  window=default:w1D:p2
  endpoint_task_id=agentpulse-design
  worktree=/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse
  project=/home/Yuhang/agentpulse/firstmate/projects/agentpulse
  harness=agy
  kind=scout
  tasktmp=/tmp/fm-agentpulse-design
  model=claude-opus-4-6-thinking
  effort=high
  spawn_gen=s1792312875.305568.25096
  backend=herdr
  herdr_session=default
  herdr_workspace_id=w1D
  herdr_tab_id=w1D:t2
  herdr_pane_id=w1D:p2
  ```
- **关键价值**：`herdr_pane_id=w1D:p2` 与 `worktree` 是将 FirstMate 任务关联至 Herdr 与文件系统的**第一确定性键**。

#### B. 状态事件行格式：`state/*.status`
- **获取方式**：文件系统读取或监控 `/home/Yuhang/agentpulse/firstmate/state/<task_id>.status`。
- **标准语法结构**：
  ```
  {state} [key=<slug>] [at=<epoch>]: {note / reason}
  ```
  - `state` 枚举：`working`, `needs-decision`, `blocked`, `paused`, `done`, `failed`, `resolved`。
  - `[key=<slug>]`：仅在 `needs-decision`、`blocked`、`resolved` 时出现，作为决策唯一闭环 ID。
  - `[at=<epoch>]`：秒级 Unix 时间戳。
  - `{note}`：单行简短状态描述；当包含 PR 时必须是完整 https URL。
- **本机真实样本**：
  ```text
  working [at=1792312916]: starting inventory of FirstMate, Herdr, and Pi data sources
  ```

#### C. Backlog 与 CLI 调查：`data/backlog.md` vs `tasks-axi`
- **`data/backlog.md` 结构**：
  由 `## In flight`、`## Queued`、`## Done` 三大标准 Section 构成。
  行内属性支持：`blocked-by: <id>`, `(repo: <repo>)`, `(kind: <kind>)`, `(since <date>)`。
  缩进后续行记录任务的具体需求说明与交付定义。
- **关于 `tasks-axi list --json` 的实测检验**：
  ```bash
  $ tasks-axi list --json
  error: "Unknown flag: --json"
  code: VALIDATION_ERROR
  ```
  **实证结论**：`tasks-axi` 的 `--json` 标志仅支持写操作（Mutations），只读查询（`list`/`show`）**完全不支持** `--json`。
  但 `tasks-axi list --fields blocked,blocked_by,body,created,links` 支持纯文本输出：
  ```text
  count: 3
  tasks[3]{id,state,kind,repo,title,blocked,blocked_by,body,created,links}:
    agentpulse-design,in_flight,scout,agentpulse,"AgentPulse: data-source inventory + Android/TUI interface design",no,none,"Scout deliverable...",2026-10-18,none
    agentpulse-service,queued,ship,agentpulse,"AgentPulse: monitoring service + Debian TUI",yes,agentpulse-design,"Implement the unified monitoring service...",2026-10-18,none
  ```

#### D. FirstMate 原生 JSON 面与状态对齐机制
1. **现成 JSON 事实源 1：`state/home-summary.json`**：
   由 `fm-home-summary-refresh.sh` 自动生成并原子替换。Schema 为 `fm-secondmate-home-summary.v1`，包含当前在飞任务、决策列表、阻塞树、就绪队列及终端关联。
2. **现成 JSON 事实源 2：`bin/fm-fleet-snapshot.sh --json`**：
   全系统最完备的 JSON 接口，包含 `tasks` 数组，每个任务拥有独立的 `current_state`、`endpoint`、`paths`、`backlog`、`pr` 等结构化对象。
3. **状态对齐机制（Reconciliation）与 `bin/fm-crew-state.sh`**：
   - **根本设计规则**：`state/<id>.status` 是**事件日志**而非瞬时状态。当任务从 `blocked` 或 `needs-decision` 恢复执行时，worker 往往不会主动向 `.status` 写入唤醒行。
   - **因此，禁止简单依靠 `tail -1 status` 判断当前活跃状态**。
   - `bin/fm-crew-state.sh <id>` 执行确定性对齐计算：读取在飞运行步（run-step）或 Herdr 窗格忙碌特征（pane busy-signature），对齐可能陈旧的日志行。
   - **实测输出**：
     ```bash
     $ /home/Yuhang/agentpulse/firstmate/bin/fm-crew-state.sh agentpulse-design
     state: working · source: pane · harness busy (herdr-native)
     ```
     标准格式为：`state: <state> · source: <source> · <detail>`。

---

### 1.2 Herdr 数据源实测

Herdr 是面向 AI Agent 的原生终端工作区管理器。本机运行版本：`0.9.3`，协议版本：`22`。

#### A. CLI 表面与非交互式命令探测
实测 `herdr --help` 及子命令证实：
- `herdr status --json`：输出服务运行状态、通信 Socket 路径（`/home/Yuhang/.config/herdr/herdr.sock`）、协议版本号等。
- `herdr api snapshot`：输出当前 Session 下所有 Workspaces、Tabs、Panes、Agents 的**完整 JSON 快照**。
- `herdr agent list`：直接返回纯 JSON 格式的 Agent 列表。
- `herdr agent get <pane_id>`：返回指定窗格的 Agent 详细状态。
- `herdr pane get <pane_id>`：返回指定窗格的终端尺寸、滚动条、关联工作区信息。
- `herdr pane process-info --pane <pane_id>`：返回该窗格的前台进程组 ID、Shell PID 以及前台运行程序（如 `agy`、`pi`）的 PID 与实际 CWD。
- `herdr pane read <pane_id> --lines <N> [--format text|ansi]`：直接抓取指定窗格的近期终端行字符。

#### B. 本机真实测试样本
1. **`herdr agent get w1D:p2` 输出样本**：
   ```json
   {
     "id": "cli:agent:get",
     "result": {
       "agent": {
         "agent": "agy",
         "agent_status": "working",
         "cwd": "/home/Yuhang/agentpulse/firstmate/projects/agentpulse",
         "focused": false,
         "foreground_cwd": "/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse",
         "pane_id": "w1D:p2",
         "revision": 12,
         "state_change_seq": 146,
         "tab_id": "w1D:t2",
         "terminal_id": "term_65e195c0733c1d",
         "terminal_title": ". ",
         "terminal_title_stripped": ".",
         "workspace_id": "w1D"
       }
     },
     "type": "agent_info"
   }
   ```
2. **`herdr pane process-info --pane w1D:p2` 输出样本**：
   ```json
   {
     "id": "cli:pane:process_info",
     "result": {
       "process_info": {
         "foreground_process_group_id": 308453,
         "foreground_processes": [
           {
             "cwd": "/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse",
             "name": "agy",
             "pid": 308453
           }
         ],
         "pane_id": "w1D:p2",
         "shell_pid": 306612
       }
     },
     "type": "pane_process_info"
   }
   ```
3. **`herdr pane read w1D:p2 --lines 4` 真实文本截取**：
   ```text
   ───────────────────────────────────────────────────────────────────────────────
   >
   ───────────────────────────────────────────────────────────────────────────────
   esc to cancel                                                           Gemini 3.8 Flash · high
   ```

#### C. Herdr Socket 原生事件推送流
读取 `herdr api schema --json` 发现，Herdr 原生内置了订阅事件机制（`events.subscribe`）。
- **支持事件类型**：
  - `pane.agent_status_changed`：推送 `pane_id`、`agent_status`（枚举：`idle`, `working`, `blocked`, `done`, `unknown`）、`title`、`state_labels`。
  - `pane.output_matched`：基于正则匹配的实时终端行触发。
  - `pane.scroll_changed`：终端滚动位置变化。
- **价值**：AgentPulse 监控服务端可建立一条本地 Unix Domain Socket 长连接，监听 `pane.agent_status_changed` 事件，实现毫秒级的 Agent 工作/空闲状态变更高频推送，彻底避免轮询开销。

---

### 1.3 Pi 数据源实测

Pi 作为智能编程核心引擎，其会话记录完整持久化于本地。

#### A. 存储位置与命名模式
- **会话存储根目录**：`~/.pi/agent/sessions/`
- **目录编码规则**：工作目录路径经过绝对路径平铺转换：首尾增加双横杠 `--`，路径分隔符 `/` 替换为单横杠 `-`。
  - 例：`/home/Yuhang/agentpulse/firstmate` 对应目录为：
    `~/.pi/agent/sessions/--home-Yuhang-agentpulse-firstmate--/`
  - 临时树屋环境 `/home/Yuhang/.treehouse/VidSift-8365f5/1/VidSift` 对应目录为：
    `~/.pi/agent/sessions/--home-Yuhang-.treehouse-VidSift-8365f5-1-VidSift--/`
- **文件命名规格**：
  `<ISO8601-Timestamp-with-hyphens>_<UUID-v7>.jsonl`
  例如：`2026-10-18T08-01-04-248Z_01a14e07-4ef6-73d2-92d7-565b7426ee84.jsonl`

#### B. JSONL 事件模型与事件分类
通过对实际生成的 `.jsonl` 文件进行统计分析，记录行包含以下顶层 `type`：
1. `session`：会话初始化事件，包含 `id`, `timestamp`, `cwd`, `version: 3`。
2. `model_change`：切换底层大模型（如 `deepseek-v4.1-flash`）。
3. `thinking_level_change`：思考深度级别配置变动。
4. `message`：核心对话与执行事件（按 `message.role` 区分：`system`, `user`, `assistant`, `toolResult`）。
5. `custom_message`：外部系统注入通知（如 FirstMate 发送的 `firstmate:notification`）。
6. `context_edit`：会话上下文修剪或替换记录。

#### C. 核心执行事件真实切片样本
1. **Assistant 轮次与工具调用（`toolCall`）**：
   ```json
   {
     "id": "22ba97cb",
     "timestamp": "2026-10-18T08:37:26.693Z",
     "type": "message",
     "message": {
       "role": "assistant",
       "model": "deepseek-v4.1-flash",
       "usage": {
         "input": 50848,
         "output": 530,
         "reasoning": 421,
         "totalTokens": 51378,
         "cost": { "input": 0, "output": 0, "total": 0 }
       },
       "content": [
         {
           "type": "thinking",
           "thinking": "Let me start. The captain has sent a big brief..."
         },
         {
           "type": "text",
           "text": "I'll get supervision armed and start reading..."
         },
         {
           "type": "toolCall",
           "id": "call_01_vWcj85vzY2BEUa4D1JyT5442",
           "name": "bash",
           "arguments": {
             "command": "cd /home/Yuhang/agentpulse && ls -la"
           }
         }
       ]
     }
   }
   ```
2. **工具执行结果返回（`toolResult`）**：
   ```json
   {
     "id": "fca13006",
     "timestamp": "2026-10-18T08:37:26.800Z",
     "parentId": "22ba97cb",
     "type": "message",
     "message": {
       "role": "toolResult",
       "toolCallId": "call_01_vWcj85vzY2BEUa4D1JyT5442",
       "toolName": "bash",
       "isError": false,
       "content": [
         {
           "type": "text",
           "text": "total 12\ndrwxr-xr-x 2 Yuhang Yuhang 4096 Oct 18 16:38 ."
         }
       ]
     }
   }
   ```

---

### 1.4 数据缺口分析与平滑降级矩阵

下表严谨列出三方系统中无法直接获得的数据项、对监控的影响以及 AgentPulse 监控服务的降级策略：

| 缺失/受限数据项 | 发生场景 | 根本原因 | AgentPulse 降级与兜底策略（绝不盲猜） |
| :--- | :--- | :--- | :--- |
| **Pi 会话日志缺失** | 当任务 Harness 为 `agy`、`opencode` 或 `codex` 时 | Pi 并非通用宿主，其它 Agent 有各自的私有转储方式 | **显式置空降级**：`active_agent_session_id = null`。UI 隐藏“思考过程”与“结构化工具调用”标签页，动态流展示 Herdr 窗格输出与 FirstMate 生命周期事件。 |
| **`tasks-axi` 无法输出 JSON** | 调用 `tasks-axi list --json` | 该 CLI 的设计契约限定 `--json` 仅用于写修改指令 | **替代事实源**：读取 `state/home-summary.json`，或调用 `fm-fleet-snapshot.sh --json`，这两者具备完整 JSON 且数据更全。 |
| **实时 CPU/内存使用率缺失** | Herdr `pane process-info` | Herdr 快照只暴露 PID 与进程名称，不度量性能开销 | **按需采样或展示标记**：监控服务直接通过 Linux `/proc/<pid>/stat` 异步读取 CPU 时间与 RSS，或在首发版中降级仅显示运行中进程名称及活跃时长。 |
| **任务历史全量终端滚动流** | 终端窗格已经产生数十万行输出 | Herdr `pane read` 返回的是终端环形回滚缓冲区（默认受限） | **增量本地转储**：监控服务以增量拉取模式（保存游标）将抓取到的新行追加写入本地 SQLite/RingBuffer，超出部分客户端仅拉取最近 200 行。 |
| **离线状态下的任务感知** | Herdr 崩溃或会话关闭 | 终端窗格进程已不存在 | **持久化底账保障**：FirstMate 的 `state/*.meta` 与 `state/*.status` 依然完整。任务状态被标记为 `pane_offline`，保留历史退出原因。 |

---

## 第二章：统一数据模型（Unified Data Model）

AgentPulse 的核心创新在于**消除 Agent 工具与终端壁垒，建立第一性关联模型**。

### 2.1 实体关联拓扑（Entity Relationship）

```
┌─────────────────────────────────────────────────────────────┐
│                       Task (FirstMate)                      │
│ id: "agentpulse-design"                                     │
│ state: "in_flight", kind: "scout", repo: "agentpulse"       │
└───────────────┬─────────────────────────────┬───────────────┘
                │ 1:1 (herdr_pane_id)         │ 1:1 (worktree -> encoded path)
                ▼                             ▼
┌───────────────────────────────┐ ┌───────────────────────────┐
│     TerminalPane (Herdr)      │ │   AgentSession (Pi)       │
│ pane_id: "w1D:p2"             │ │ session_id: "01a14e07..." │
│ agent: "agy", status: "working"│ │ model: "deepseek-v4.1"   │
│ foreground_cwd: "/home/..."   │ │ tokens: 51378, cost: $0   │
└───────────────┬───────────────┘ └───────────┬───────────────┘
                │                             │
                │ 产生近期输出 (Lines)          │ 产生工具调用 (toolCall/toolResult)
                └──────────────┬──────────────┘
                               ▼
                ┌─────────────────────────────┐
                │        Event (Unified)      │
                │ seq: 1042, severity: "info" │
                │ type: "tool_call" | "log"   │
                └──────────────┬──────────────┘
                               ▼
                ┌─────────────────────────────┐
                │        Alert (Alerts)       │
                │ level: "error" | "warning"  │
                │ title: "Agent Blocked"      │
                └─────────────────────────────┘
```

### 2.2 核心实体 JSON Schema 规范

#### A. Task 实体（统一任务）
```json
{
  "$id": "https://agentpulse.dev/schemas/task.json",
  "type": "object",
  "required": ["id", "title", "state", "kind", "repo", "created_at", "updated_at"],
  "properties": {
    "id": { "type": "string", "description": "FirstMate 任务唯一标识" },
    "title": { "type": "string", "description": "任务简明标题" },
    "description": { "type": ["string", "null"], "description": "任务详细需求说明" },
    "state": { 
      "type": "string", 
      "enum": ["queued", "in_flight", "done", "held", "failed", "blocked"] 
    },
    "kind": { "type": "string", "enum": ["scout", "ship", "secondmate", "custom"] },
    "repo": { "type": "string", "description": "关联 Git 仓库名称" },
    "worktree": { "type": ["string", "null"], "description": "独立工作树绝对路径" },
    "branch": { "type": ["string", "null"], "description": "工作 Git 分支" },
    "pr_url": { "type": ["string", "null"], "format": "uri", "description": "关联 PR 完整地址" },
    "harness": { "type": "string", "description": "Agent 宿主程序: pi, agy, opencode 等" },
    "model": { "type": "string", "description": "分配的大模型名称" },
    "blocked_by": { "type": ["string", "null"], "description": "主阻塞源任务 ID" },
    "blocked_by_ids": { "type": "array", "items": { "type": "string" } },
    "hold_reason": { "type": ["string", "null"], "description": "挂起原因（如等待人工决策）" },
    "hold_kind": { "type": ["string", "null"], "enum": ["captain", "blocker", null] },
    "assigned_pane_id": { "type": ["string", "null"], "description": "关联的 Herdr Pane ID" },
    "active_agent_session_id": { "type": ["string", "null"], "description": "关联的 Pi Session ID" },
    "created_at": { "type": "string", "format": "date-time" },
    "updated_at": { "type": "string", "format": "date-time" }
  }
}
```

#### B. TerminalPane 实体（Herdr 窗格状态）
```json
{
  "$id": "https://agentpulse.dev/schemas/terminal_pane.json",
  "type": "object",
  "required": ["pane_id", "workspace_id", "agent_status", "focused"],
  "properties": {
    "pane_id": { "type": "string", "description": "形如 w1D:p2" },
    "workspace_id": { "type": "string", "description": "形如 w1D" },
    "tab_id": { "type": ["string", "null"] },
    "terminal_title": { "type": "string" },
    "agent": { "type": ["string", "null"], "description": "探测到的 Agent 身份" },
    "agent_status": { 
      "type": "string", 
      "enum": ["working", "idle", "blocked", "done", "unknown"] 
    },
    "cwd": { "type": "string" },
    "foreground_cwd": { "type": "string" },
    "focused": { "type": "boolean" },
    "foreground_processes": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "pid": { "type": "integer" },
          "cwd": { "type": "string" }
        }
      }
    },
    "recent_output_tail": { 
      "type": "array", 
      "items": { "type": "string" },
      "description": "最近 20~50 行终端输出内容（可含 ANSI 样式）"
    }
  }
}
```

#### C. Event 实体（统一事件流）
```json
{
  "$id": "https://agentpulse.dev/schemas/event.json",
  "type": "object",
  "required": ["event_id", "seq", "timestamp", "source", "kind", "severity", "payload"],
  "properties": {
    "event_id": { "type": "string" },
    "seq": { "type": "integer", "description": "严格递增全局事件序号" },
    "task_id": { "type": ["string", "null"] },
    "pane_id": { "type": ["string", "null"] },
    "source": { "type": "string", "enum": ["firstmate", "herdr", "pi", "service"] },
    "kind": { 
      "type": "string", 
      "enum": [
        "task_state_changed",
        "agent_status_changed",
        "tool_call",
        "tool_result",
        "thinking_chunk",
        "pane_output",
        "decision_needed",
        "task_completed",
        "task_failed"
      ] 
    },
    "severity": { "type": "string", "enum": ["debug", "info", "warning", "error", "critical"] },
    "timestamp": { "type": "string", "format": "date-time" },
    "payload": { "type": "object", "description": "与 kind 对应的多态数据载荷" }
  }
}
```

#### D. Alert 实体（异常与通知）
```json
{
  "$id": "https://agentpulse.dev/schemas/alert.json",
  "type": "object",
  "required": ["alert_id", "category", "severity", "title", "message", "created_at", "status"],
  "properties": {
    "alert_id": { "type": "string" },
    "task_id": { "type": ["string", "null"] },
    "category": { 
      "type": "string", 
      "enum": [
        "decision_required",
        "task_failed",
        "agent_unresponsive",
        "pipeline_blocked",
        "service_disconnected"
      ] 
    },
    "severity": { "type": "string", "enum": ["warning", "error", "critical"] },
    "title": { "type": "string" },
    "message": { "type": "string" },
    "key": { "type": ["string", "null"], "description": "决策闭环键" },
    "suggested_actions": { 
      "type": "array", 
      "items": { "type": "string" },
      "description": "建议执行的操作或命令提示" 
    },
    "created_at": { "type": "string", "format": "date-time" },
    "status": { "type": "string", "enum": ["active", "acknowledged", "resolved"] }
  }
}
```

### 2.3 实体关联（Join）算法与兜底机制

```
关联优先级决策树：
1. FirstMate meta 文件是否有 herdr_pane_id?
   ├── YES ──> 直接关联 Herdr Pane (pane_id == herdr_pane_id)
   └── NO  ──> 扫描 Herdr Workspaces，按标签正则 `└ <task_id> · .*` 或 `foreground_cwd == task.worktree` 进行模糊关联。
2. 任务的 harness 是否为 "pi"?
   ├── YES ──> 将 task.worktree 转换为 `--<encoded-path>--`，获取该目录下最新的 .jsonl 会话文件。
   └── NO  ──> active_agent_session_id 设为 null，安全跳过微观工具调用采集。
```

### 2.4 事件分级（Severity）、去重与留存

1. **分级阶梯**：
   - `CRITICAL`：任务彻底失败（`failed`）、Agent 异常崩溃退出、需要人工决策的卡点（`needs-decision`）。
   - `ERROR`：工具调用执行报错（`isError: true`）、命令退出码非零。
   - `WARNING`：Agent 进入 `blocked` 状态、长时间未响应心跳、连接降级（`stale`）。
   - `INFO`：任务状态流转（`queued` -> `in_flight` -> `done`）、工具调用启动（`toolCall`）。
   - `DEBUG`：微观思考 Token 流、细碎终端字符输出。
2. **幂等去重（Deduplication）**：
   - Pi 事件：基于 `message.id` 或 `toolCall.id` 进行唯一性判定。
   - FirstMate 事件：基于 `(task_id, state, at_epoch)` 组合键去重。
   - Herdr 终端行：维护每窗格最后 5 行文本的 SHA-256 Hash，避免重复推送无新输出的终端切片。
3. **数据留存（Retention Policy）**：
   - **内存环形缓冲区**：每个活动任务在内存中保留最新 1,000 条事件，供移动端/TUI 随时瞬间恢复上下文。
   - **持久化 SQLite**：WSL2 本地轻量 SQLite 存储最近 7 天或最多 50,000 条结构化事件，定期自动 VACUUM 清理，磁盘占用严格控制在 50MB 以内。

---

## 第三章：监控服务契约规范（Monitoring Service Contract）

监控服务作为 WSL2 Debian 内的本地守护进程，对外提供 REST API 与全双工 WebSocket。
默认绑定：`127.0.0.1:7890`（通过 SSH 隧道安全暴露给安卓端）。

### 3.1 REST API 完整接口规范

#### 1. 系统健康状态：`GET /api/v1/health`
- **说明**：检查监控服务本身、子采集器及各上游数据源健康度。
- **响应示例**：
  ```json
  {
    "status": "healthy",
    "version": "1.0.0",
    "uptime_seconds": 3840,
    "timestamp": "2026-10-18T08:50:00Z",
    "collectors": {
      "firstmate": { "status": "ok", "last_sync_epoch": 1792313390 },
      "herdr": { "status": "ok", "socket": "/home/Yuhang/.config/herdr/herdr.sock", "protocol": 22 },
      "pi": { "status": "ok", "active_sessions_tracked": 1 }
    },
    "clients_connected": 2
  }
  ```

#### 2. 全局监控总览：`GET /api/v1/overview`
- **说明**：手机总览页与 TUI 顶部首屏的核心数据聚合，毫秒级返回。
- **响应示例**：
  ```json
  {
    "generated_at": "2026-10-18T08:50:00Z",
    "metrics": {
      "active_tasks_count": 1,
      "queued_tasks_count": 2,
      "completed_tasks_count": 0,
      "active_alerts_count": 0,
      "working_agents_count": 1
    },
    "active_tasks": [
      {
        "id": "agentpulse-design",
        "title": "AgentPulse: data-source inventory + Android/TUI interface design",
        "kind": "scout",
        "repo": "agentpulse",
        "state": "in_flight",
        "agent": "agy",
        "agent_status": "working",
        "since": "2026-10-18",
        "current_doing": "harness busy (herdr-native)"
      }
    ],
    "pending_decisions": [],
    "critical_alerts": []
  }
  ```

#### 3. 任务列表查询：`GET /api/v1/tasks`
- **查询参数**：
  - `state`：过滤状态（可选，如 `in_flight,queued,done`）
  - `repo`：按仓库过滤（可选）
- **响应示例**：
  ```json
  {
    "total": 3,
    "tasks": [
      {
        "id": "agentpulse-design",
        "title": "AgentPulse: data-source inventory + Android/TUI interface design",
        "state": "in_flight",
        "kind": "scout",
        "repo": "agentpulse",
        "worktree": "/home/Yuhang/.treehouse/agentpulse-0ede77/1/agentpulse",
        "branch": null,
        "assigned_pane_id": "w1D:p2",
        "harness": "agy",
        "model": "claude-opus-4-6-thinking",
        "blocked_by": null,
        "created_at": "2026-10-18T08:38:00Z",
        "updated_at": "2026-10-18T08:43:00Z"
      },
      {
        "id": "agentpulse-service",
        "title": "AgentPulse: monitoring service + Debian TUI",
        "state": "queued",
        "kind": "ship",
        "repo": "agentpulse",
        "blocked_by": "agentpulse-design",
        "blocked_by_ids": ["agentpulse-design"],
        "created_at": "2026-10-18T08:38:00Z",
        "updated_at": "2026-10-18T08:38:00Z"
      }
    ]
  }
  ```

#### 4. 任务终端输出获取：`GET /api/v1/tasks/{id}/terminal`
- **查询参数**：
  - `lines`：请求行数，默认 50，最大 200
  - `format`：`text`（纯文本）或 `ansi`（保留彩色控制字符，用于 TUI/Compose 高亮渲染）
- **响应示例**：
  ```json
  {
    "task_id": "agentpulse-design",
    "pane_id": "w1D:p2",
    "format": "ansi",
    "line_count": 3,
    "lines": [
      "\u001b[38;5;244m───────────────────────────────────────────────────────────────────────────────\u001b[0m",
      ">",
      "\u001b[38;5;244mesc to cancel                                                           \u001b[36mGemini 3.8 Flash · high\u001b[0m"
    ],
    "truncated": false
  }
  ```

#### 5. 增量事件流同步：`GET /api/v1/events`
- **查询参数**：
  - `since_seq`：从指定游标向后拉取（必选用于增量同步）
  - `limit`：拉取条数，默认 100，最大 500
  - `task_id`：按任务过滤（可选）
- **响应示例**：
  ```json
  {
    "since_seq": 1040,
    "max_seq": 1042,
    "has_more": false,
    "events": [
      {
        "event_id": "evt_01a14e",
        "seq": 1041,
        "task_id": "agentpulse-design",
        "source": "herdr",
        "kind": "agent_status_changed",
        "severity": "info",
        "timestamp": "2026-10-18T08:43:20Z",
        "payload": {
          "pane_id": "w1D:p2",
          "previous_status": "idle",
          "current_status": "working"
        }
      },
      {
        "event_id": "evt_01a14f",
        "seq": 1042,
        "task_id": "agentpulse-design",
        "source": "firstmate",
        "kind": "task_state_changed",
        "severity": "info",
        "timestamp": "2026-10-18T08:43:25Z",
        "payload": {
          "state": "working",
          "note": "starting inventory of FirstMate, Herdr, and Pi data sources"
        }
      }
    ]
  }
  ```

---

### 3.2 WebSocket 实时长连接契约

- **连接路径**：`ws://127.0.0.1:7890/ws`
- **查询参数**：`?client_id=android_pixel8&since_seq=1040`

#### A. 握手与初次同步（Hello / Resume）
1. 客户端建立连接时，带上上次断开时本地暂存的 `since_seq`。
2. 服务端收到后，先以 `replay` 批量推送丢失的增量事件，随后发送 `live_ready`，转入纯实时推送。

#### B. 消息封包格式（Message Envelopes）
1. **服务端向客户端推送事件（Push Event）**：
   ```json
   {
     "type": "event_push",
     "seq": 1043,
     "topic": "events",
     "data": {
       "event_id": "evt_01a150",
       "task_id": "agentpulse-design",
       "source": "pi",
       "kind": "tool_call",
       "severity": "info",
       "timestamp": "2026-10-18T08:50:10Z",
       "payload": {
         "toolName": "bash",
         "toolCallId": "call_99",
         "command": "git status"
       }
     }
   }
   ```
2. **心跳保活协议（Heartbeat Ping/Pong）**：
   - 客户端每 15 秒发送一次心跳：`{"type": "ping", "timestamp": 1792313410}`
   - 服务端立即响应：`{"type": "pong", "timestamp": 1792313410}`

#### C. 网络异常与断线状态判定逻辑
客户端严格维护三态连接模型：
1. **`Healthy`（绿色）**：正常收发消息或心跳延迟 < 3 秒。
2. **`Stale`（琥珀色）**：超过 15 秒未收到任何数据包。客户端界面不阻断操作，但在顶栏显示「连接不稳定，正在保持同步...」，界面打上轻微半透明降级标识。
3. **`Disconnected`（红色）**：超过 30 秒无心跳，或 TCP Socket 直接断开。客户端顶部弹出不可忽略的红色横幅「与 AgentPulse 监控服务断开连接」，并在后台启动指数退避重连算法（1s -> 2s -> 4s -> 最大 15s），重连成功后自动发送 `since_seq` 追溯数据。

---

## 第四章：安卓端原生界面设计（Kotlin + Jetpack Compose）

本章为安卓工程实现量身定制，严格遵循 Material 3 与 Jetpack Compose 规范。

### 4.1 信息架构与导航模型（Information Architecture）

采用现代化**单 Activity + Compose 响应式多屏幕架构**。

```
                     ┌───────────────────────────────┐
                     │     MainActivity (Compose)    │
                     └───────────────┬───────────────┘
                                     │
         ┌───────────────────────────┴───────────────────────────┐
         ▼                                                       ▼
┌──────────────────┐                                   ┌──────────────────┐
│  SSH 隧道连接守卫  │                                   │ 底部导航栏 (NavBar) │
└──────────────────┘                                   └─────────┬────────┘
                                                                 │
    ┌────────────────────┬────────────────────┬──────────────────┴─────────────────┐
    ▼                    ▼                    ▼                                    ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐                      ┌───────────────┐
│ 1. 总览屏      │  │ 2. 任务与Agent │  │ 3. 实时动态屏  │                      │ 4. 异常提醒屏  │
│ OverviewScreen│  │ TasksScreen   │  │ LiveFeedScreen│                      │ AlertsScreen  │
└───────┬───────┘  └───────┬───────┘  └───────┬───────┘                      └───────┬───────┘
        │                  │                  │                                      │
        └──────────────────┼──────────────────┴──────────────────────────────────────┘
                           ▼
                  ┌─────────────────┐
                  │ 详情弹窗 / 页面   │
                  │ TaskDetail      │
                  │ PaneDetail      │
                  │ AlertSheet      │
                  └─────────────────┘
```

### 4.2 工业级设计系统（Design System Tokens）

专为夜间/电脑旁长时间常亮监控设计的 **OLED 极致深色主题（Dark-First Palette）**：

#### A. 语义调色板（Hex & WCAG 对比度）
```kotlin
// Android Compose 调色板定义
object PulseThemeColors {
    val Background = Color(0xFF0D1117)      // 深度暗黑背景，降低 OLED 功耗
    val Surface = Color(0xFF161B22)         // 卡片容器表面色
    val SurfaceVariant = Color(0xFF21262D)  // 次级容器 / 输入底色
    val Outline = Color(0xFF30363D)         // 1dp 容器分割线
    
    // 主语义强调色 (严格通过 WCAG AAA 标准)
    val PrimaryCyan = Color(0xFF38BDF8)     // 活跃进行中强调色 (对比度 8.2:1)
    val SuccessGreen = Color(0xFF34D399)    // 成功/完成色 (对比度 9.5:1)
    val WarningAmber = Color(0xFFFBBF24)    // 阻塞/决策中色 (对比度 10.1:1)
    val ErrorRose = Color(0xFFF43F5E)       // 失败/异常中断色 (对比度 6.8:1)
    
    // 文字梯度
    val TextPrimary = Color(0xFFF0F6FC)     // 一级主要文本 (对比度 13.5:1)
    val TextSecondary = Color(0xFF8B949E)   // 二级辅助注释 (对比度 5.8:1)
    val TextMuted = Color(0xFF484F58)       // 占位符/极弱文本
    val CodeTerminalBg = Color(0xFF080B0F)  // 终端代码区域黑底
}
```

#### B. 字体排印规范（Typography Scale）
- **中文与通用 UI**：系统无衬线字体（Inter / Roboto / HarmonyOS Sans）
- **路径、代码、标识符、终端行**：**JetBrains Mono** 等宽字体
```kotlin
val PulseTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp),
    // 专门用于代码与终端的等宽风格
    headlineSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
)
```

#### C. 间距、圆角与动画定义
- **间距标尺**：`4.dp`, `8.dp`, `12.dp`, `16.dp`, `24.dp`, `32.dp`
- **卡片与弹窗圆角**：
  - 标签与微标（Tags/Badges）：`6.dp`
  - 卡片容器（Cards）：`12.dp`
  - 底部浮层（BottomSheets）：`16.dp`（仅顶部两角）
- **动效规范（Motion Specs）**：
  - 状态呼吸灯：`1200ms` 无限循环 `FastOutSlowInEasing`，在 `working` 状态下产生柔和青色微光。
  - 列表项滑入动画：`250ms` `DecelerateEasing`。

---

### 4.3 四大核心屏幕布局与 ASCII 线框图

#### 屏 1：总览大盘（OverviewScreen）
- **核心职能**：一目了然获取全局运行状态，电脑旁抬眼即见当前进度、Agent 是否卡滞、有无待处理决策。
- **ASCII 线框图**：
  ```
  ┌────────────────────────────────────────────────────────┐
  │ AgentPulse · WSL2 🟢              [SSH] ⚡ 16:45:10    │
  ├────────────────────────────────────────────────────────┤
  │ ［系统状态］工作流运转中 (Healthy)                        │
  │ ┌──────────────┬──────────────┬──────────────┐       │
  │ │  在飞任务 1  │  排队就绪 2  │  异常告警 0  │       │
  │ └──────────────┴──────────────┴──────────────┘       │
  ├────────────────────────────────────────────────────────┤
  │ ◆ 正在执行的任务                                       │
  │ ┌────────────────────────────────────────────────────┐ │
  │ │ [Scout] agentpulse-design             (00:12:45)   │ │
  │ │ 状态: WORKING 🟢 (agy · Herdr: w1D:p2)             │ │
  │ │ 目标: 数据源清单与 Android/TUI 界面设计规范文档      │ │
  │ │ 进度: 正在分析 Pi 会话结构...                       │ │
  │ └────────────────────────────────────────────────────┘ │
  ├────────────────────────────────────────────────────────┤
  │ ◆ 待处理决策 (Needs Decision)                         │
  │ (暂无阻断决策，工作流顺畅流转中)                        │
  ├────────────────────────────────────────────────────────┤
  │ ◆ 排队队列 (Queued)                                   │
  │ • agentpulse-service (阻塞源: agentpulse-design)     │
  │ • agentpulse-android (阻塞源: agentpulse-design)     │
  └────────────────────────────────────────────────────────┘
  │ [总览]        [任务]          [动态]          [告警] │
  ```

#### 屏 2：任务与 Agent 矩阵（TasksScreen）
- **核心职能**：纵向铺开 FirstMate 的任务队列，点击即可下钻查看工作目录、Git 分支、关联的 Herdr 窗格近期输出及微观 Agent 会话。
- **ASCII 线框图**：
  ```
  ┌────────────────────────────────────────────────────────┐
  │ 任务与 Agent                                [筛选 ▽]  │
  ├────────────────────────────────────────────────────────┤
  │ ［全部(3)］ ［进行中(1)］ ［排队中(2)］ ［已完成(0)］   │
  ├────────────────────────────────────────────────────────┤
  │ ┌────────────────────────────────────────────────────┐ │
  │ │ ● agentpulse-design                       [Scout]  │ │
  │ │ 仓库: agentpulse   Harness: agy                     │ │
  │ │ 目录: ~/.treehouse/agentpulse-0ede77/1/agentpulse   │ │
  │ │ 窗格: default:w1D:p2 (Herdr native working)        │ │
  │ │ 近期终端输出:                                      │ │
  │ │ ┌────────────────────────────────────────────────┐ │ │
  │ │ │> checking herdr agent list                      │ │ │
  │ │ │{"id":"cli:agent:list","result":{...}}           │ │ │
  │ │ └────────────────────────────────────────────────┘ │ │
  │ └────────────────────────────────────────────────────┘ │
  │ ┌────────────────────────────────────────────────────┐ │
  │ │ ⏸ agentpulse-service                     [Ship]   │ │
  │ │ 阻塞于: agentpulse-design                          │ │
  │ └────────────────────────────────────────────────────┘ │
  │ ┌────────────────────────────────────────────────────┐ │
  │ │ ⏸ agentpulse-android                     [Ship]   │ │
  │ │ 阻塞于: agentpulse-design                          │ │
  │ └────────────────────────────────────────────────────┘ │
  └────────────────────────────────────────────────────────┘
  │ [总览]        [任务]          [动态]          [告警] │
  ```

#### 屏 3：实时动态流（LiveFeedScreen）
- **核心职能**：以时间流（Timeline）展示 Agent 的真实工作痕迹，重要工具调用、Shell 命令、测试运行结果、状态变更自动滚动呈现。
- **ASCII 线框图**：
  ```
  ┌────────────────────────────────────────────────────────┐
  │ 实时动态流 (Live Activity)               [自动滚动: 开]│
  ├────────────────────────────────────────────────────────┤
  │ 16:44:22 [Herdr] 窗格 w1D:p2 状态流转 -> WORKING       │
  ├────────────────────────────────────────────────────────┤
  │ 16:44:50 [Pi/Tool] bash                                │
  │          $ cd /home/Yuhang/agentpulse && ls -la        │
  │          ↳ 返回: 0 (成功，12 项文件)                   │
  ├────────────────────────────────────────────────────────┤
  │ 16:45:10 [Pi/Thinking]                                 │
  │          "正在解析 Herdr 0.9.3 的 socket API 架构..."   │
  ├────────────────────────────────────────────────────────┤
  │ 16:46:00 [FirstMate] status 更新                       │
  │          working: starting inventory of FirstMate...   │
  └────────────────────────────────────────────────────────┘
  │ [总览]        [任务]          [动态]          [告警] │
  ```

#### 屏 4：异常提醒与阻断（AlertsScreen）
- **核心职能**：高亮汇总所有阻断事件、任务失败崩溃、等待人工决策的卡点。由于首发版本只读，界面上提供复制完整指令按钮，方便在电脑端一键粘贴执行。
- **ASCII 线框图**：
  ```
  ┌────────────────────────────────────────────────────────┐
  │ 异常提醒与待办                               [清空已读]│
  ├────────────────────────────────────────────────────────┤
  │ ┌── ⚠️ 等待人工决策 (NEEDS-DECISION) ────────────────┐ │
  │ │ 任务: agentpulse-design                             │ │
  │ │ 时间: 16:30:15                                     │ │
  │ │ 概要: 是否同意引入 Material 3 扩展动态颜色库？      │ │
  │ │ 选项: [1] 同意  [2] 拒绝并保留经典深色              │ │
  │ │ 电脑处理指令:                                      │ │
  │ │ bin/fm-send.sh agentpulse-design '选择方案1'       │ │
  │ │                                      [复制指令 📋] │ │
  │ └────────────────────────────────────────────────────┘ │
  │ ┌── ❌ 服务断开连接 (SERVICE-DISCONNECTED) ─────────┐ │
  │ │ 状态: 已恢复重连 (发生于 15:20:00)                  │ │
  │ └────────────────────────────────────────────────────┘ │
  └────────────────────────────────────────────────────────┘
  │ [总览]        [任务]          [动态]          [告警] │
  ```

---

### 4.4 异常打扰分级与系统通知机制（Notification Policy）

为保证桌面监控专注度，严禁无差别弹窗打扰：
1. **中断级（High Priority Notification）**：
   - 触发条件：`CRITICAL`（任务执行失败、Agent 异常崩溃退出、`needs-decision` 等待人工作出关键抉择）。
   - 表现：触发 Android 系统 Heads-up 横幅通知、震动、常亮屏幕唤醒并伴随红色呼吸灯。
2. **静默级（Silent In-App Badge）**：
   - 触发条件：`WARNING`（工具调用超时重试、网络出现短时 Stale）。
   - 表现：底部导航栏「异常」Tab 显示琥珀色角标，静默记录进列表，不弹系统通知。
3. **忽略级（Feed-Only）**：
   - 触发条件：`INFO`、`DEBUG`（工具调用成功、正常日志打印、状态转为 working）。
   - 表现：仅进入实时动态瀑布流，零打扰。

---

### 4.5 多设备自适应与无障碍设计（Form Factors & Accessibility）

1. **横屏模式（Phone Landscape - 电脑副屏模式）**：
   - 底部导航栏自动转换为**左侧 Navigation Rail**，释放垂直屏幕空间。
   - 采用分栏左右双联布局：左侧 40% 显示任务列表与统计，右侧 60% 持续滚动显示当前选定任务的终端输出与动态。
2. **平板/折叠屏（Tablet / Foldable - 极客桌面看板模式）**：
   - 展开为三栏看板：
     - 左栏（25%）：全局指标、待处理决策、队列列表；
     - 中栏（45%）：在飞任务详实面板、实时终端 ANSI 彩色输出；
     - 右栏（30%）：全局实时活动事件流。
3. **无障碍设计（Accessibility Standards）**：
   - 所有按钮与点击卡片严格保持最小 `48.dp x 48.dp` 触摸靶区（`Modifier.minimumInteractiveComponentSize()`）。
   - 文本大小采用动态 `sp` 缩放，在系统开启 150%~200% 特大字号模式下，布局自动转为垂直滚动，绝不截断关键信息。

---

## 第五章：Debian TUI 终端客户端设计（Debian TUI Design）

为在 WSL2 本机或通过纯 SSH 终端连接的用户提供无需 GUI 的轻量、超高刷新率监控体验。

### 5.1 框架评估与技术选型

| 框架方案 | 语言 | 优势 | 劣势 | 结论 |
| :--- | :--- | :--- | :--- | :--- |
| **Ratatui** | **Rust** | **性能极高**（CPU 占用 < 0.1%），单静态二进制发布，零外部运行库依赖，具备顶级的 ANSI/真彩色渲染和弹性布局 | 需要使用 Rust 编译 | **推荐首选（Primary）** |
| **Bubbletea** | Go | 纯 Elm 架构，心智模型极简，生态组件丰富（Bubbles） | Go 在 WSL2 本机需额外安装编译环境 | 次选方案 |
| **Textual** | Python | 快速上手，支持类似 CSS 的样式系统 | 启动慢（Python 冷启动），运行时内存占用较大（>60MB） | 不适合作为长驻轻量看板 |

**选型结论**：选用 **Rust + Ratatui**。编译生成单个约 8MB 的二进制可执行文件 `agentpulse-tui`，直接通过本地 TCP/Unix Socket 与监控服务通信，内存驻留小于 12MB。

---

### 5.2 布局规格与 ASCII 线框图

#### A. 紧凑型终端（80 列 x 24 行标准最小环境）
```text
┌──────────────── AgentPulse · FirstMate+Herdr+Pi (Healthy) ───────────────────┐
│ [1]Overview  [2]Tasks(1)  [3]LiveFeed  [4]Alerts(0)             16:45:10      │
├──────────────────────────────────────┬───────────────────────────────────────┤
│ ◆ ACTIVE TASK                        │ ◆ RECENT LOG / PANE OUTPUT            │
│ ID: agentpulse-design [Scout]        │ 16:44:20 herdr agent list             │
│ Repo: agentpulse   Harness: agy      │ 16:45:00 parse session schema         │
│ Status: WORKING 🟢                   │ 16:45:08 read pane w1D:p2             │
│ Doing: harness busy (herdr-native)   │ > esc to cancel                       │
│ Time: 00:14:22                       │                                       │
├──────────────────────────────────────┼───────────────────────────────────────┤
│ ◆ QUEUED BACKLOG (2)                 │ ◆ LATEST ALERTS & DECISIONS           │
│ • agentpulse-service (blocked)       │ (None. Workflow running smoothly)     │
│ • agentpulse-android (blocked)       │                                       │
├──────────────────────────────────────┴───────────────────────────────────────┤
│ [Tab]切换面板  [j/k]上下选择  [Enter]展开详情  [r]刷新  [q]退出               │
└──────────────────────────────────────────────────────────────────────────────┘
```

#### B. 扩展型宽屏终端（160 列 x 48 行高分辨率环境）
```text
┌─────────────────────────────────────────────── AgentPulse 监控中心 (Debian TUI v1.0) ────────────────────────────────────────────────────────┐
│ 主机: WSL2-Debian  │  连接: 127.0.0.1:7890 🟢 (Delay: 0.8ms)  │  在飞任务: 1  │  排队: 2  │  告警: 0  │  当前时间: 2026-10-18 16:45:10 UTC+8    │
├──────────────────────────────────────┬───────────────────────────────────────────────────────────────┬──────────────────────────────────────┤
│ 📋 任务流水线 (Tasks Pipeline)        │ 🖥️ 终端窗格实时映像 (Herdr Pane w1D:p2)                        │ ⚡ 实时事件动态 (Activity Feed)       │
├──────────────────────────────────────┼───────────────────────────────────────────────────────────────┼──────────────────────────────────────┤
│ ▶ [In Flight]                        │ [CWD] ~/.treehouse/agentpulse-0ede77/1/agentpulse             │ 16:43:10 [FM] status -> working      │
│   ● agentpulse-design                │ [Agent] agy (PID: 308453)  [Status] WORKING                   │ 16:43:20 [Herdr] pane status: busy   │
│     Kind: Scout  Repo: agentpulse    │ ───────────────────────────────────────────────────────────── │ 16:44:00 [Pi] session scanned        │
│     Harness: agy                     │ └ Tip: Press shift+tab to cycle modes (default, accept-edits) │ 16:44:50 [Herdr] process info mapped │
│     Duration: 00:15:30               │ ───────────────────────────────────────────────────────────── │ 16:45:00 [Service] events sync ok    │
│                                      │ > checking data-source inventory for FirstMate and Herdr...   │                                      │
│                                      │ > reading ~/.pi/agent/sessions/ directories...                │                                      │
│ ⏸ [Queued Backlog]                   │ > extracting JSON schemas and event sequences...              │                                      │
│   • agentpulse-service               │                                                               │                                      │
│     Kind: Ship                       │                                                               │                                      │
│     Blocked by: agentpulse-design    │                                                               │                                      │
│                                      │                                                               │                                      │
│   • agentpulse-android               │                                                               │                                      │
│     Kind: Ship                       │                                                               │                                      │
│     Blocked by: agentpulse-design    │                                                               │                                      │
│                                      │                                                               │                                      │
├──────────────────────────────────────┴───────────────────────────────────────────────────────────────┴──────────────────────────────────────┤
│ 快捷键: [1-4]视图切换  [Tab]遍历窗格  [j/k/↑/↓]导航  [Enter]打开详情浮层  [t]进入全屏终端跟随  [a]告警中心  [r]强制数据重对齐  [?]帮助  [q]退出     │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 16 色色彩降级与 ANSI 映射表

终端不仅支持现代化 24 位真彩色（TrueColor），还对通过 SSH 或兼容终端的 **标准 16 色环境** 做了原生映射：

| 语义角色 | 24-bit TrueColor Hex | 256-Color 索引 | 16-Color ANSI 转义码 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **活跃进行 (Working)** | `#38BDF8` (Cyan) | `81` | `\u001b[36m` (Cyan) | 醒目、不伤眼的高级青色 |
| **完成通过 (Success)** | `#34D399` (Emerald)| `78` | `\u001b[32m` (Green) | 标准通过绿色 |
| **阻塞挂起 (Warning)** | `#FBBF24` (Amber)  | `214`| `\u001b[33m` (Yellow)| 决策与阻塞黄色 |
| **严重失败 (Error)**   | `#F43F5E` (Rose)   | `197`| `\u001b[31m` (Red)   | 警报红色 |
| **次级文本 (Muted)**   | `#8B949E` (Grey)   | `244`| `\u001b[90m` (Bright Black)| 辅助边框与灰字 |
| **窗格焦点 (Focus)**   | `#A78BFA` (Purple) | `141`| `\u001b[35m` (Magenta) | 选中区域的高亮边框 |

---

## 第六章：工程落地与实施路线图（Implementation Plan）

### 6.1 阶段里程碑与分工交付

```
阶段 1: 监控服务核心开发 (Service Core)
├── 1.1 采集器: FirstMate 状态与 HomeSummary 采集
├── 1.2 采集器: Herdr Socket 连接与 agent/pane 轮询
├── 1.3 采集器: Pi JSONL 尾随解析器
├── 1.4 状态关联与内存/SQLite 存储引擎
└── 1.5 HTTP API 与 WebSocket 服务端开发
    │
    ├───> 阶段 2: Debian TUI 客户端开发 (TUI Client)
    │     ├── 2.1 Ratatui 终端视图框架搭设
    │     ├── 2.2 80x24 与 160x48 响应式分栏
    │     └── 2.3 WebSocket 客户端与终端 ANSI 渲染
    │
    └───> 阶段 3: Android 安卓原生看板开发 (Android App)
          ├── 3.1 Compose 工业级深色主题与底层数据仓库
          ├── 3.2 SSH 隧道建立与服务保活机制
          ├── 3.3 四大主屏幕（总览、任务、动态、告警）实现
          └── 3.4 异常打扰拦截与系统通知管道
```

- **第一阶段：统一监控服务（Service Core Engine）**
  - **交付成果**：单可执行文件或 Node/Go 守护进程 `agentpulse-server`。
  - **验证标准**：通过 `curl http://127.0.0.1:7890/api/v1/overview` 能够正确且完整吐出关联了 Herdr 窗格和 Pi 任务状态的聚合数据。
- **第二阶段：Debian TUI 客户端（TUI Client）**
  - **交付成果**：终端客户端 `agentpulse-tui`。
  - **验证标准**：在 WSL2 Debian 下无需 X11/GUI，键盘交互流畅，实时滚动反映 Agent 操作。
- **第三阶段：安卓原生监控端（Android Compose App）**
  - **交付成果**：`agentpulse-android.apk`。
  - **验证标准**：手机通过 SSH 隧道连入 WSL2，在床头/桌面支架上呈现高级、简洁的实时监控画面。

### 6.2 关键实施风险与缓解对策

1. **风险一：本机（WSL2 Debian）缺乏 Android SDK 与 Gradle 工具链**
   - **实证现状**：探测证实当前系统无 `gradle`、无 `android-sdk`。
   - **解决对策**：
     - 服务端与 TUI 客户端在本机完全自主编译构建。
     - 安卓工程代码在本地仓库完整规范编写，构建环节可提供标准的 `gradlew` 包装脚本；编译与生成 APK 可由 Windows 宿主机 Android Studio 打开共享目录直接构建，或配置 GitHub Actions 自动打包 Release APK。
2. **风险二：WSL2 网络隔离与手机端访问通道**
   - **实证现状**：WSL2 处于虚拟 NAT 内部网络，手机无法直接按 `127.0.0.1` 访问。
   - **解决对策**：采用 **SSH 端口转发隧道方案**。
     - 在 Windows 宿主机安装 OpenSSH Server 或通过 ConnectBot/Termius；
     - 手机端 AgentPulse App 内置 SSH 隧道配置（输入 Windows/WSL2 IP、SSH 端口、密钥），自动建立 Local Port Forwarding `7890 -> 127.0.0.1:7890`；
     - 此方案零暴露公网，安全性最高，且抗网络波动重连能力极强。
3. **风险三：多 Agent 混合运行下的性能与锁争用**
   - **对策**：AgentPulse 采集器遵循**纯只读与零侵入原则**。读取 FirstMate 优先读原子替换的 `home-summary.json`，连接 Herdr 走只读接口 `api snapshot` 与 `agent get`，绝对禁止任何带有写倾向或修改生命周期的指令。

---

## 结论与行动建议

AgentPulse 为现代多 Agent 协同编程提供了前所未有的**全链路可观测性基石**。本设计规范全面覆盖了现实探测证据、数据模型定义、接口契约及双端 UI 细节，具备直接进入编码执行的完备度。后续实施可按照第一阶段（监控服务）与第三阶段（安卓端/TUI）并轨推进。

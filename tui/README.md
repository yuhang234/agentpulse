# AgentPulse TUI 终端客户端 (Debian TUI)

> 基于 Rust + Ratatui 构建的 AgentPulse 只读监控看板终端客户端。

---

## 产品定位与架构说明

TUI 是监控服务的**第二个客户端**，用来在终端或别的服务器上查看同一套工作流状态（FirstMate 任务、Herdr 终端面板、Pi 执行事件）。**主客户端是手机 App**。手机 App 和 TUI 都是通过 SSH 隧道连接同一个监控服务的客户端，TUI 不负责与手机通信，也不是服务端的替代品，更不是把手机连到 Debian 的那一层——那一层是监控服务本身（HTTP API + WebSocket 推送）。用脚本直接往手机推数据做不到服务已经提供的这些能力：稳定接口、增量事件游标、断线重连、多客户端并发读取。所以架构上就是「一个服务 + 两个客户端」，TUI 是可选的可视化终端，服务于别的终端和服务器场景。

**核心约束：TUI 绝对只读**。客户端不发指令、不改任务状态、不操作 agent 终端、不向 Herdr 窗格注入任何 stdin，也不写入或移动 FirstMate 的任何底层状态文件。所有操作均为单向拉取与长连接事件流订阅，保障多 Agent 并发作业时的环境安全。

---

## 核心特性

- **多尺寸终端自适应**：
  - **80x24（紧凑标准型）**：2x2 四宫格自洽布局（活跃任务、终端窗格、排队就绪、告警与决策）。
  - **120x30（日常开发型）**：非对称弹性双栏布局（左侧任务流水线、右侧终端映像与系统健康指标）。
  - **160x48（扩展宽屏型）**：三栏全景看板（任务流水线、终端字符流、实时事件动态）。
  - **超小终端防护**：当尺寸低于 80x24 时自动展示 `RenderSizeWarning` 单列提示，防止布局崩溃。
- **高弹性网络与断线续传**：
  - 维持 HTTP 保底轮询与 WebSocket 增量实时推送。
  - 底层断线后自动触发指数退避重连（1s → 2s → 4s → 8s，上限 8s）。
  - 重连成功后携带 `since_seq` 游标拉取增量事件回放，保证事件流零丢失、无跳屏。
- **CJK 双宽字符与单字符截断防护**：
  - 严格采用 `unicode-width` 核算物理单元格宽度，杜绝中英混排时边框错位与断裂。
  - 截断函数基于单字符省略号 `…`（1 单元格宽）与边界探测，避免截断半个汉字导致乱码。
- **Pi 会话优雅降级**：
  - 针对使用 `agy` 或 `opencode` 等非 Pi Harness 的任务，后端返回的 `active_agent_session_id` 为 `null`。
  - TUI 严格遵循非 Pi 不造假原则，自动折叠或平滑降级微观思维流，并以 Herdr 真实终端输出完全接管展示。
- **色彩正交与 16 色降级**：
  - 支持 TrueColor (24-bit)、256 色与 ANSI 16 色环境自动降级。
  - 6 种语义角色（Working 青色、Success 绿色、Warning 黄色、Error 红色、Muted 灰色、Focus 紫色）在 16 色下两两绝对正交。
- **防误触退出机制**：
  - 激活模态浮层时按 `q` 仅关闭浮层，绝不退出程序。
  - 主界面按 `q` 弹出黄色双击确认倒计时（1.5 秒），再次按 `q` 方可安全退出；随时支持 `Ctrl+C` 强退。

---

## 构建与运行

### 编译

依赖 Rust 1.75+ 工具链与 Cargo：

```bash
cd tui

# 检查代码与类型
cargo check

# 运行单元测试 (56 项单元测试覆盖解析、降级、时区、截断等)
cargo test

# 构建 Release 高性能二进制
cargo build --release
```

编译产物位于 `tui/target/release/agentpulse-tui`。

### 命令行参数

```bash
# 默认连接本地监控服务 (http://127.0.0.1:7890)
./target/release/agentpulse-tui

# 指定自定义服务端点
./target/release/agentpulse-tui --url http://127.0.0.1:7890

# 指定轮询间隔与陈旧超时时间 (秒)
./target/release/agentpulse-tui --url http://127.0.0.1:7890 --interval 3 --stale-after 10

# 环境变量配置 (优先级低于 --url 命令行参数)
export AGENTPULSE_SERVICE_URL="http://127.0.0.1:7890"
./target/release/agentpulse-tui

# 查阅帮助与版本
./target/release/agentpulse-tui --help
./target/release/agentpulse-tui --version
```

---

## 快捷键速查表

| 按键 | 说明 | 适用范围 |
| :--- | :--- | :--- |
| `1` | 直达 **总览中心** (Overview) | 全局主界面 |
| `2` | 直达 **任务流水线** (Tasks Pipeline 表格) | 全局主界面 |
| `3` | 直达 **全局事件流** (Activity Feed) | 全局主界面 |
| `4` / `a` | 直达 **告警与决策中心** (Alerts & Decisions) | 全局主界面 |
| `Tab` / `Shift+Tab` | 顺时针 / 逆时针轮换面板活动焦点 | 复合面板布局 |
| `j` / `k` 或 `↓` / `↑` | 上下移动选中光标 / 滚动历史 | 列表、事件流、终端窗格 |
| `g` / `G` | 快速直达首行 / 末行（在事件流中触发 Tail Lock） | 列表与长文本 |
| `Enter` | 展开选中项的居中详情模态浮层 | 任务列表、告警列表 |
| `t` | 循环切换任务状态过滤（全部 / 进行中 / 就绪 / 阻塞 / 待决 / 完成） | 任务视图 |
| `s` | 循环切换严重级别过滤（全部 / Critical / Warning / Error / Info / Debug） | 事件流视图 |
| `Space` | 暂停 / 恢复事件流末尾锁定 (Tail Lock) | 事件流视图 |
| `r` | 强制触发全量数据重对齐与拉取 | 全局 |
| `?` / `F1` | 打开快捷键与色彩指南浮层 | 全局 |
| `Esc` | 关闭当前浮层 / 清除过滤 / 返回 | 浮层与模态状态 |
| `q` | 退出浮层；若在主界面则触发 1.5s 双击防误退安全确认 | 全局 |
| `Ctrl+C` | 系统级强制退出并恢复终端 Raw Mode | 全局 |

---

## 模块结构与代码组织

按照设计规范严格解耦，所有显示文本、配色方案与尺寸判定集中在独立模块中：

```text
tui/
├── Cargo.toml          # 依赖配置 (ratatui, crossterm, serde, unicode-width)
├── Cargo.lock          # 锁定版本
├── README.md           # 本文档
└── src/
    ├── main.rs         # 入口、panic hook 安装与 raw-mode 生命周期
    ├── app.rs          # 客户端状态机、数据模型容器、缓存与按键派发
    ├── ui.rs           # Ratatui 视图渲染（多尺寸适配、面板网格与浮层）
    ├── layout.rs       # 布局尺寸计算与降级模式判定 (Wide / Compact / Fallback)
    ├── palette.rs      # 语义调色板与色彩级别降级 (TrueColor / 256 / 16 色)
    ├── strings.rs      # 集中化中文文案字典与权威用词表
    ├── format.rs       # CJK 宽度核算、时间格式化、ANSI 转义剥离与事件格式化
    ├── model.rs        # 对齐 service/agentpulse_service/models.py 的 DTO 结构
    ├── http.rs         # 极简轻量只读 HTTP/1.1 客户端 (支持 Chunked)
    ├── net.rs          # 异步后台网络工作线程 (HTTP 轮询 + WebSocket + 退避重连)
    └── cli.rs          # 命令行参数解析与 URL 规范化
```

---

## 规范参考

- **TUI 界面设计规范**：`/home/Yuhang/agentpulse/firstmate/data/agentpulse-tui-design/report.md`
- **交互原型与线框预览**：`/home/Yuhang/agentpulse/firstmate/data/agentpulse-tui-design-html/index.html`
- **AgentPulse 总览设计**：`firstmate/data/agentpulse-design/report.md`

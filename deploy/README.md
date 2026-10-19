# AgentPulse 部署与运维指南

本目录提供 AgentPulse 监控服务与 APK 下载服务的一键管理工具 `pulse`。

## 选型结论：现成应用 vs 脚本包装

针对 Debian 环境下的管理需求，结论如下：

**Debian 上没有值得引入的现成应用。**
当前需求本质上是"启动两个轻量进程（FastAPI 监控服务与 Python HTTP 下载服务）+ 维护一段 nftables 防火墙规则"。
引入通用进程管理器（如 systemd、supervisord 或 pm2）会带来额外的配置文件、常驻后台守护开销以及全局权限复杂性。
相比之下，一个经过充分健壮性检验的 bash 脚本并在安装时软链至 `~/.local/bin/pulse` 是最合适的形态：
- 零额外运行时依赖，完全复用现有系统工具。
- 不常驻后台占用资源，仅在执行命令时触发。
- 不修改系统级服务，避免污染全局环境。
- 敲一下即启动，敲一下即关闭，体验顺手直接。

## 目录与文件布局

默认安装根目录为 `~/.local/share/agentpulse`，支持通过环境变量 `AGENTPULSE_INSTALL_DIR` 覆盖。

```text
~/.local/bin/pulse                     -> 软链接至 ~/.local/share/agentpulse/pulse
~/.local/share/agentpulse/
├── service/                           # FastAPI 服务源码
├── .venv/                             # 专用 Python 虚拟环境与依赖
├── run/                               # 运行时状态（PID 与端口文件）
│   ├── service.pid
│   ├── download.pid
│   ├── service.port
│   └── download.port
├── logs/                              # 日志输出目录
│   ├── service.log                    # 监控服务日志
│   └── download.log                   # APK 下载服务日志
├── config/                            # 持久化配置文件
│   └── allowed-ip                     # 允许访问 7890 端口的手机 IP
└── download/                          # 静态文件目录，提供 APK 安装包下载
```

## 安装

在代码仓库根目录或 `deploy/` 目录下执行安装脚本：

```bash
# 默认安装至 ~/.local/share/agentpulse 并软链接至 ~/.local/bin/pulse
./deploy/install.sh

# 可选：指定自定义安装目录与初始放行 IP
./deploy/install.sh --dir /path/to/custom-dir --allow 10.103.111.190
```

安装完成后，在任意目录均可直接调用 `pulse` 命令。

## 常用命令

### 1. 启动服务 (`pulse start`)

一条命令启动监控服务（默认端口 7890）与 APK 下载服务（默认端口 7891），并按配置规则应用 nftables 防火墙：

```bash
pulse start
```

输出示例：
```text
监控服务: 已启动 (PID: 12345, 端口: 7890, 日志: .../service.log)
下载服务: 已启动 (PID: 12346, 端口: 7891, 日志: .../download.log)

AgentPulse 服务启动成功！
  本机地址:   http://127.0.0.1:7890/api/v1/health
  局域网地址: http://10.103.105.100:7890
  安装包下载: http://10.103.105.100:7891/
```

- **幂等性保障**：若服务已在运行，命令仅报告当前状态，不会重复启动进程。
- **端口冲突排查**：若端口被其他程序占用，明确提示占用进程名称与 PID（如 `进程 'uvicorn', PID 566935`），避免盲目失败。

### 2. 停止服务 (`pulse stop`)

停止所有服务进程，并清理创建的 nftables 防火墙表：

```bash
pulse stop
```

- **安全防误杀**：严格结合 PID 文件与进程命令行（`/proc/<pid>/cmdline`）双重校验，杜绝误杀其他进程。
- **幂等清理**：重复执行不报错。

### 3. 重启服务 (`pulse restart`)

平滑停止已有进程并重新启动服务、重载配置与防火墙：

```bash
pulse restart
```

### 4. 查看状态 (`pulse status`)

集中展示进程、端口、HTTP 健康码、当前连接手机数量及防火墙规则，一眼确认服务连通性：

```bash
pulse status
```

输出示例：
```text
================ AgentPulse 状态 ================
  监控服务      : 运行中 (PID: 12345)
  下载服务      : 运行中 (PID: 12346)
  端口 7890     : 0.0.0.0:7890 (监听中) | 端口 7891   : 0.0.0.0:7891 (监听中)
  健康检查      : 200 OK (正常)
  手机连接      : 1 台 (10.103.111.190)
  放行 IP       : 10.103.111.190
  防火墙状态    : 已生效 (tcp dport 7890 ip saddr 127.0.0.1 accept ...)
=================================================
```

### 5. 查看日志 (`pulse logs`)

查看服务运行日志，支持分别查看监控服务或下载服务日志，并支持持续跟随：

```bash
# 查看监控服务最近 50 行日志（默认）
pulse logs

# 实时跟随监控服务日志
pulse logs -f

# 查看下载服务日志
pulse logs download

# 实时跟随下载服务日志
pulse logs download -f
```

### 6. 修改放行手机 IP (`pulse allow <ip>`)

修改允许访问 7890 监控端口的手机 IP。
该命令会自动校验 IPv4 地址合法性，即时重载 nftables 防火墙规则，并持久化写入 `config/allowed-ip`：

```bash
pulse allow 10.103.111.190
```

- 7890 端口仅允许本机 loopback 与放行 IP 访问，绝不向整个网段暴露。
- 7891 端口（公开 APK 下载）保持对任何来源开放。

### 7. 彻底卸载 (`pulse uninstall`)

停止所有运行中的服务、清空防火墙规则、移除 `~/.local/bin/pulse` 软链接并清理安装目录：

```bash
pulse uninstall
```

## 环境变量支持

可在启动命令前附加环境变量临时覆盖配置：

| 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `AGENTPULSE_INSTALL_DIR` | `~/.local/share/agentpulse` | 安装根目录 |
| `AGENTPULSE_PORT` | `7890` | 监控服务监听端口 |
| `AGENTPULSE_DOWNLOAD_PORT` | `7891` | APK 下载服务监听端口 |
| `AGENTPULSE_NFT_TABLE` | `agentpulse` | nftables 表名 |
| `AGENTPULSE_HOME` | `~/agentpulse/firstmate` | Firstmate 数据根目录 |

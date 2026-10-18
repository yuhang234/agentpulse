# AgentPulse Android

AgentPulse Android 监控客户端，采用 Kotlin + Jetpack Compose + Material 3 构建，专为闲置手机打造的只读监控看板。

支持四大核心屏幕：
- **总览** (Overview)：全局指标、活跃任务、待决策卡片与严重告警。
- **任务** (Tasks)：任务工作树、分支、Agent 运行状态与 ANSI 实时终端回放。
- **动态** (Live)：按 seq 排序的事件流瀑布与断线增量追溯。
- **告警** (Alerts)：分类告警列表、角标计数与电脑处理指令一键复制。

---

## 1. 兼容性与网络支持

- **系统要求**：Android 8.0+（minSdk `26`），覆盖并兼容 Android 9（API 28）及以上版本。
- **明文连接**：在 `AndroidManifest.xml` 中配置了 `android:usesCleartextTraffic="true"`，允许直接通过明文 HTTP/WebSocket 连接局域网内的监控服务，无需配置 HTTPS 证书。
- **局域网自适应**：设置页支持直接输入电脑局域网地址（如 `192.168.x.x:7890` 或分别输入主机与端口），保存后 REST 接口基地址与 WebSocket 长连接将立即切换并生效。

---

## 2. 本地构建

### 环境准备

- **JDK**：JDK 17+（本机环境为 JDK 21）
- **Android SDK**：API 35（默认位于 `~/android-sdk`）
- **Gradle**：8.x（本机位于 `~/gradle/bin/gradle`）

在 `android/` 目录下创建 `local.properties`（该文件已加入 `.gitignore`，请勿提交）：

```properties
sdk.dir=/home/Yuhang/android-sdk
```

> **网络提示**：Maven Central / Google 仓库若需要代理，可在 `android/gradle.properties` 中配置 `systemProp.http(s).proxyHost` 与 `proxyPort`。

### 构建与测试命令

```bash
# 进入 android 目录
cd android

# 运行单元测试
~/gradle/bin/gradle :app:testDebugUnitTest

# 构建 Debug 签名 APK
~/gradle/bin/gradle :app:assembleDebug
```

构建成功后，APK 产物位于：
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 3. 安装与连接指南

### 3.1 安装 APK 到手机

使用 debug 自动签名包，安装方式如下：

1. **通过 ADB 直接安装**：
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. **通过文件传输安装**：
   将 APK 传输到手机存储后，在文件管理器中点击安装。如系统提示「禁止安装未知应用」，进入提示设置页开启「允许来自此来源的应用」，即可继续安装。

### 3.2 局域网连接电脑服务

1. **电脑端启动服务（必须监听 0.0.0.0）**：
   ```bash
   ./service/run.sh --host 0.0.0.0 --port 7890
   ```
2. **获取电脑局域网 IP**：
   在电脑终端执行 `ip route get 1`、`hostname -I` 或 Windows 的 `ipconfig`，获取手机所在局域网的电脑 IP（如 `192.168.31.50`）。
3. **App 内设置**：
   打开 AgentPulse App，点击顶栏右上角设置图标（⚙️），在「主机 host」填入电脑局域网 IP（例如 `192.168.31.50`，支持直接输入或粘贴 `192.168.31.50:7890`），端口填 `7890`，点击「保存」。
   App 顶栏端点标签将实时更新，并立即建立 WebSocket 长连接与拉取 REST 监控数据。

### 3.3 故障排查

若出现「服务断开连接」：
- **同局域网确认**：确保手机和电脑连接的是同一个 Wi-Fi，且路由器未开启 AP 隔离；
- **浏览器直连排查**：在手机浏览器输入 `http://<电脑局域网IP>:7890/api/v1/health`，若无法打开，优先排查电脑防火墙是否放行 7890 端口，或服务是否正确监听了 `0.0.0.0`；
- **USB 备选**：可通过 USB 连接执行 `adb reverse tcp:7890 tcp:7890`，在 App 设置中恢复默认 `127.0.0.1:7890` 直连。

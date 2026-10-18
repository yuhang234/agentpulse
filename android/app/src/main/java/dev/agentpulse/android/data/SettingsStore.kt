package dev.agentpulse.android.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 监控服务接入参数。
 *
 * 默认 `127.0.0.1:7890`（报告第三章：监控服务默认绑定 `127.0.0.1:7890`）。
 * v1 把 SSH 隧道留在应用之外，因此这里只描述隧道**本机出口**这一侧的地址：
 *
 * - 手机通过 USB 调试连接时：`adb reverse tcp:7890 tcp:7890`，默认值直接可用。
 * - 手机在同一局域网 / Wi-Fi 调试时：`ssh -L 7890:127.0.0.1:7890 <user>@<host>`，
 *   主机字段填隧道出口地址。
 *
 * 隧道由使用者在本应用之外建立，应用只消费隧道暴露的环路端点。
 */
data class TransportSettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** WebSocket 连接路径（报告 3.2：`ws://127.0.0.1:7890/ws`）。 */
    val socketPath: String = DEFAULT_SOCKET_PATH,
    /** REST 接口前缀（报告 3.1：`/api/v1`）。 */
    val apiPrefix: String = DEFAULT_API_PREFIX,
    /** 明文 HTTP；隧道场景下流量不出宿主，v1 不引入 TLS。 */
    val useTls: Boolean = false,
    /** 上报给服务端的客户端标识（报告 3.2 查询参数 `client_id`）。 */
    val clientId: String = defaultClientId(),
) {
    val httpScheme: String get() = if (useTls) "https" else "http"
    val wsScheme: String get() = if (useTls) "wss" else "ws"

    /** REST 基地址，例如 `http://127.0.0.1:7890`。 */
    val baseUrl: String get() = "$httpScheme://$host:$port"

    /** WebSocket 完整地址，例如 `ws://127.0.0.1:7890/ws`。 */
    val socketUrl: String get() = "$wsScheme://$host:$port${normalizedSocketPath()}"

    /** REST 接口根，例如 `http://127.0.0.1:7890/api/v1`。 */
    val apiBaseUrl: String get() = "$baseUrl${normalizedApiPrefix()}"

    /** 顶栏展示用短标识，例如 `127.0.0.1:7890`。 */
    val endpointLabel: String get() = "$host:$port"

    /**
     * 规范化接入参数，自动处理局域网 IP / host:port / 协议头 / 路径容错。
     */
    fun sanitized(): TransportSettings {
        var cleanHost = host.trim()
        var cleanPort = port

        // 兼容带协议头的情况（如 http://192.168.1.5:7890 或 ws://192.168.1.5:7890）
        val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        cleanHost = cleanHost.replace(schemeRegex, "")

        // 兼容带路径的情况（如 192.168.1.5:7890/ 或 192.168.1.5/api）
        if (cleanHost.contains("/")) {
            cleanHost = cleanHost.substringBefore("/")
        }

        // 兼容在 host 字段中直接填入 host:port 的情况（如 192.168.1.5:7890）
        if (cleanHost.contains(":")) {
            if (cleanHost.startsWith("[") && cleanHost.contains("]:")) {
                val hostPart = cleanHost.substringBefore("]:") + "]"
                val portPart = cleanHost.substringAfter("]:")
                cleanHost = hostPart
                portPart.toIntOrNull()?.let { cleanPort = it }
            } else if (!cleanHost.startsWith("[")) {
                val parts = cleanHost.split(":")
                if (parts.size == 2) {
                    cleanHost = parts[0]
                    parts[1].toIntOrNull()?.let { cleanPort = it }
                }
            }
        }

        return copy(
            host = cleanHost.ifBlank { DEFAULT_HOST },
            port = cleanPort.coerceIn(1, 65_535),
            socketPath = socketPath.trim().ifBlank { DEFAULT_SOCKET_PATH },
            apiPrefix = apiPrefix.trim().trim('/').ifBlank { DEFAULT_API_PREFIX.trim('/') },
            clientId = clientId.trim().ifBlank { defaultClientId() },
        )
    }

    private fun normalizedSocketPath(): String =
        "/" + socketPath.trim().trimStart('/')

    private fun normalizedApiPrefix(): String =
        "/" + apiPrefix.trim().trim('/')

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7890
        const val DEFAULT_SOCKET_PATH = "/ws"
        const val DEFAULT_API_PREFIX = "/api/v1"

        /** 形如 `android_pixel8`（报告 3.2 查询参数示例）。 */
        fun defaultClientId(): String {
            val model = runCatching { Build.MODEL }.getOrNull().orEmpty()
            val slug = model.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            return if (slug.isBlank()) "android_client" else "android_$slug"
        }
    }
}

/**
 * 接入参数持久化（SharedPreferences），并额外保存本地已读告警集合。
 *
 * 已读集合必须跨进程存活，否则「清空已读」会在下次冷启动后复活。
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TransportSettings> = _settings.asStateFlow()

    fun current(): TransportSettings = _settings.value

    fun update(transform: (TransportSettings) -> TransportSettings) {
        val next = transform(_settings.value).sanitized()
        _settings.value = next
        prefs.edit()
            .putString(KEY_HOST, next.host)
            .putInt(KEY_PORT, next.port)
            .putString(KEY_SOCKET_PATH, next.socketPath)
            .putString(KEY_API_PREFIX, next.apiPrefix)
            .putBoolean(KEY_TLS, next.useTls)
            .putString(KEY_CLIENT_ID, next.clientId)
            .apply()
    }

    fun resetToDefaults() = update { TransportSettings() }

    /** 本地已读告警 id 集合。 */
    fun acknowledgedAlertIds(): Set<String> =
        prefs.getStringSet(KEY_ACK_ALERTS, emptySet())?.toSet() ?: emptySet()

    fun persistAcknowledged(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ACK_ALERTS, ids).apply()
    }

    private fun load(): TransportSettings {
        val defaults = TransportSettings()
        return TransportSettings(
            host = prefs.getString(KEY_HOST, defaults.host) ?: defaults.host,
            port = prefs.getInt(KEY_PORT, defaults.port),
            socketPath = prefs.getString(KEY_SOCKET_PATH, defaults.socketPath) ?: defaults.socketPath,
            apiPrefix = prefs.getString(KEY_API_PREFIX, defaults.apiPrefix) ?: defaults.apiPrefix,
            useTls = prefs.getBoolean(KEY_TLS, defaults.useTls),
            clientId = prefs.getString(KEY_CLIENT_ID, defaults.clientId) ?: defaults.clientId,
        ).sanitized()
    }

    private companion object {
        const val PREFS_NAME = "agentpulse.settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_SOCKET_PATH = "socket_path"
        const val KEY_API_PREFIX = "api_prefix"
        const val KEY_TLS = "use_tls"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ACK_ALERTS = "acknowledged_alerts"
    }
}

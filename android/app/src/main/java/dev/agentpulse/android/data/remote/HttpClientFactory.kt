package dev.agentpulse.android.data.remote

import dev.agentpulse.android.data.model.AgentPulseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端工厂。
 *
 * 读超时设为无限：WebSocket 长连接在空闲期不应该被引擎掐断，
 * 链路活性完全交给报告 3.2.C 的三态判定 + 应用层心跳来负责；
 * REST 调用另有协程级 [kotlinx.coroutines.withTimeout] 兜底。
 */
object HttpClientFactory {
    fun create(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.MILLISECONDS)
                writeTimeout(10, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        expectSuccess = true
        install(ContentNegotiation) {
            json(AgentPulseJson.instance)
        }
        install(WebSockets)
    }
}

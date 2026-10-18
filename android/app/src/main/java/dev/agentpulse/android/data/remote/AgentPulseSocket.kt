package dev.agentpulse.android.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * WebSocket 客户端 —— 报告 3.2 全双工长连接。
 *
 * 一次 [run] 调用对应**一个连接生命周期**：建立连接、投递帧、直到通道关闭或
 * 调用方取消。重连策略（指数退避 + `since_seq` 追溯）由 [dev.agentpulse.android.data.PulseRepository]
 * 的驱动循环负责，本类不做任何重试，保证「一次失败 = 一次明确的重连决策」。
 */
class AgentPulseSocket(private val client: HttpClient) {

    /**
     * @param url 完整连接地址，含 `client_id` 与 `since_seq` 查询参数（报告 3.2）。
     * @param outbound 出站帧队列：心跳、握手、以及任何需要主动上行的文本帧。
     * @param onOpened 连接握手成功回调（此时可以发送 `hello`）。
     * @param onText 收到文本帧回调。
     */
    suspend fun run(
        url: String,
        outbound: ReceiveChannel<String>,
        onOpened: suspend () -> Unit,
        onText: suspend (String) -> Unit,
    ) {
        client.webSocket(urlString = url) {
            onOpened()
            val writer = launch {
                for (message in outbound) {
                    send(Frame.Text(message))
                }
            }
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> onText(frame.readText())
                        is Frame.Close -> break
                        else -> Unit // 二进制/分片帧不在本协议内，静默忽略。
                    }
                }
            } finally {
                writer.cancel()
            }
        }
    }
}

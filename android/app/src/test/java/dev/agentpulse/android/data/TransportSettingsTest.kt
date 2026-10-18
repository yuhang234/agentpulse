package dev.agentpulse.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportSettingsTest {

    @Test
    fun `default transport settings point to loopback port 7890`() {
        val settings = TransportSettings()
        assertEquals("127.0.0.1", settings.host)
        assertEquals(7890, settings.port)
        assertEquals("http://127.0.0.1:7890", settings.baseUrl)
        assertEquals("http://127.0.0.1:7890/api/v1", settings.apiBaseUrl)
        assertEquals("ws://127.0.0.1:7890/ws", settings.socketUrl)
        assertEquals("127.0.0.1:7890", settings.endpointLabel)
    }

    @Test
    fun `custom LAN host and port updates all endpoints`() {
        val settings = TransportSettings(
            host = "192.168.31.50",
            port = 7890,
        )
        assertEquals("192.168.31.50", settings.host)
        assertEquals(7890, settings.port)
        assertEquals("http://192.168.31.50:7890", settings.baseUrl)
        assertEquals("http://192.168.31.50:7890/api/v1", settings.apiBaseUrl)
        assertEquals("ws://192.168.31.50:7890/ws", settings.socketUrl)
        assertEquals("192.168.31.50:7890", settings.endpointLabel)
    }

    @Test
    fun `sanitized handles host containing port`() {
        val raw = TransportSettings(host = "192.168.1.100:8080", port = 7890)
        val sanitized = raw.sanitized()
        assertEquals("192.168.1.100", sanitized.host)
        assertEquals(8080, sanitized.port)
        assertEquals("http://192.168.1.100:8080", sanitized.baseUrl)
        assertEquals("http://192.168.1.100:8080/api/v1", sanitized.apiBaseUrl)
        assertEquals("ws://192.168.1.100:8080/ws", sanitized.socketUrl)
    }

    @Test
    fun `sanitized strips URI scheme and trailing slash`() {
        val raw = TransportSettings(host = "http://192.168.1.100:7890/")
        val sanitized = raw.sanitized()
        assertEquals("192.168.1.100", sanitized.host)
        assertEquals(7890, sanitized.port)
        assertEquals("http://192.168.1.100:7890", sanitized.baseUrl)
    }

    @Test
    fun `sanitized falls back to default on blank host`() {
        val raw = TransportSettings(host = "   ")
        val sanitized = raw.sanitized()
        assertEquals(TransportSettings.DEFAULT_HOST, sanitized.host)
        assertEquals(TransportSettings.DEFAULT_PORT, sanitized.port)
    }
}

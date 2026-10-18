package dev.agentpulse.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three-state connection model and reconnect backoff, per design report 3.2.C.
 */
class ConnectionStateTest {

    private val machine = ConnectionStateMachine(
        freshLatencyMs = 3_000L,
        staleAfterMs = 15_000L,
        disconnectedAfterMs = 30_000L,
    )

    @Test
    fun `closed socket is immediately disconnected`() {
        assertEquals(ConnectionState.Disconnected, machine.evaluate(silenceMs = 0L, socketOpen = false))
        assertEquals(ConnectionState.Disconnected, machine.evaluate(silenceMs = 60_000L, socketOpen = false))
    }

    @Test
    fun `quiet under 15s stays healthy`() {
        assertEquals(ConnectionState.Healthy, machine.evaluate(silenceMs = 0L, socketOpen = true))
        assertEquals(ConnectionState.Healthy, machine.evaluate(silenceMs = 14_999L, socketOpen = true))
    }

    @Test
    fun `silence past 15s degrades to stale`() {
        assertEquals(ConnectionState.Stale, machine.evaluate(silenceMs = 15_001L, socketOpen = true))
        assertEquals(ConnectionState.Stale, machine.evaluate(silenceMs = 29_999L, socketOpen = true))
    }

    @Test
    fun `silence past 30s becomes disconnected`() {
        assertEquals(ConnectionState.Disconnected, machine.evaluate(silenceMs = 30_001L, socketOpen = true))
    }

    @Test
    fun `state exposes blocking and degraded flags`() {
        assertTrue(ConnectionState.Disconnected.isBlocking)
        assertFalse(ConnectionState.Stale.isBlocking)
        assertTrue(ConnectionState.Stale.isDegraded)
        assertFalse(ConnectionState.Healthy.isDegraded)
    }

    @Test
    fun `latency health respects 3s threshold`() {
        assertTrue(machine.isLatencyHealthy(null))
        assertTrue(machine.isLatencyHealthy(0L))
        assertTrue(machine.isLatencyHealthy(3_000L))
        assertFalse(machine.isLatencyHealthy(3_001L))
    }

    @Test
    fun `backoff follows 1 2 4 8 15 then caps`() {
        val backoff = ReconnectBackoff()
        assertEquals(1_000L, backoff.nextDelayMillis())
        assertEquals(2_000L, backoff.nextDelayMillis())
        assertEquals(4_000L, backoff.nextDelayMillis())
        assertEquals(8_000L, backoff.nextDelayMillis())
        assertEquals(15_000L, backoff.nextDelayMillis())
        // capped at the final step
        assertEquals(15_000L, backoff.nextDelayMillis())
        assertEquals(15_000L, backoff.nextDelayMillis())
    }

    @Test
    fun `backoff reset restarts from 1s`() {
        val backoff = ReconnectBackoff()
        repeat(3) { backoff.nextDelayMillis() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMillis())
    }
}

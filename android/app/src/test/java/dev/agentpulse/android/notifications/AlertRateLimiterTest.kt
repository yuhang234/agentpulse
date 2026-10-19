package dev.agentpulse.android.notifications

import dev.agentpulse.android.data.model.AlertCategory
import dev.agentpulse.android.data.model.AlertStatus
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertRateLimiterTest {

    private var fakeCurrentTime = 1000L
    private lateinit var limiter: AlertRateLimiter

    @Before
    fun setUp() {
        fakeCurrentTime = 1000L
        limiter = AlertRateLimiter(
            burstWindowMs = 3000L,
            clock = { fakeCurrentTime },
        )
    }

    private fun sampleAlert(
        id: String,
        tier: String = "critical",
        severity: Severity = Severity.Critical,
    ): PulseAlert = PulseAlert(
        alertId = id,
        category = AlertCategory.DecisionRequired,
        severity = severity,
        tier = tier,
        title = "Test alert $id",
        message = "Message for $id",
        status = AlertStatus.Active,
    )

    @Test
    fun `first critical alert is dispatched immediately`() {
        val alert = sampleAlert("al_1")
        val decision = limiter.process(alert)
        assertEquals(AlertDispatchDecision.Immediate(alert), decision)
    }

    @Test
    fun `same alert is deduplicated and never dispatched twice`() {
        val alert = sampleAlert("al_1")
        val d1 = limiter.process(alert)
        assertEquals(AlertDispatchDecision.Immediate(alert), d1)

        fakeCurrentTime = 1500L
        val d2 = limiter.process(alert)
        assertEquals(AlertDispatchDecision.DropDuplicate, d2)

        fakeCurrentTime = 10000L
        val d3 = limiter.process(alert)
        assertEquals(AlertDispatchDecision.DropDuplicate, d3)
    }

    @Test
    fun `non critical alerts are ignored by rate limiter`() {
        val warnAlert = sampleAlert("al_w1", tier = "warning", severity = Severity.Warning)
        val d1 = limiter.process(warnAlert)
        assertEquals(AlertDispatchDecision.SuppressNonCritical, d1)

        val infoAlert = sampleAlert("al_i1", tier = "info", severity = Severity.Info)
        val d2 = limiter.process(infoAlert)
        assertEquals(AlertDispatchDecision.SuppressNonCritical, d2)
    }

    @Test
    fun `burst of critical alerts collapses into single notification with count`() {
        val a1 = sampleAlert("burst_1")
        val a2 = sampleAlert("burst_2")
        val a3 = sampleAlert("burst_3")

        fakeCurrentTime = 1000L
        val d1 = limiter.process(a1)
        assertEquals(AlertDispatchDecision.Immediate(a1), d1)

        fakeCurrentTime = 2000L
        val d2 = limiter.process(a2)
        assertTrue(d2 is AlertDispatchDecision.Collapsed)
        val c2 = d2 as AlertDispatchDecision.Collapsed
        assertEquals(2, c2.count)
        assertEquals(a2, c2.latestAlert)

        fakeCurrentTime = 2800L
        val d3 = limiter.process(a3)
        assertTrue(d3 is AlertDispatchDecision.Collapsed)
        val c3 = d3 as AlertDispatchDecision.Collapsed
        assertEquals(3, c3.count)
        assertEquals(a3, c3.latestAlert)
    }

    @Test
    fun `critical alert arriving after burst window starts new immediate notification`() {
        val a1 = sampleAlert("burst_1")
        val a2 = sampleAlert("burst_2")
        val a3 = sampleAlert("burst_3")

        fakeCurrentTime = 1000L
        limiter.process(a1)

        fakeCurrentTime = 2000L
        limiter.process(a2)

        // Window is 3000ms. Since last burst at 2000L, arriving at 6000L is > 3000ms later.
        fakeCurrentTime = 6000L
        val d3 = limiter.process(a3)
        assertEquals(AlertDispatchDecision.Immediate(a3), d3)
    }

    @Test
    fun `reset clears deduplication history and burst state`() {
        val alert = sampleAlert("al_reset")
        fakeCurrentTime = 1000L
        limiter.process(alert)

        fakeCurrentTime = 1500L
        assertEquals(AlertDispatchDecision.DropDuplicate, limiter.process(alert))

        limiter.reset()

        fakeCurrentTime = 2000L
        val freshDecision = limiter.process(alert)
        assertEquals(AlertDispatchDecision.Immediate(alert), freshDecision)
    }
}

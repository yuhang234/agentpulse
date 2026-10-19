package dev.agentpulse.android.notifications

import dev.agentpulse.android.data.model.AgentPulseJson
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.PulseEvent
import dev.agentpulse.android.data.model.PulseTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierPresentationPolicyTest {

    private val json = AgentPulseJson.instance

    @Test
    fun `critical tier policy enables sound vibration notification banner and badge`() {
        val policy = TierPresentationPolicy.presentationFor(PulseTier.Critical)
        assertTrue(policy.allowSystemNotification)
        assertTrue(policy.soundAndVibration)
        assertTrue(policy.showInAppBanner)
        assertTrue(policy.showBadgeCount)
        assertFalse(policy.streamOnly)
    }

    @Test
    fun `warning tier policy enables only badge and count, no interruption`() {
        val policy = TierPresentationPolicy.presentationFor(PulseTier.Warning)
        assertFalse(policy.allowSystemNotification)
        assertFalse(policy.soundAndVibration)
        assertFalse(policy.showInAppBanner)
        assertTrue(policy.showBadgeCount)
        assertFalse(policy.streamOnly)
    }

    @Test
    fun `info tier policy never interrupts and stays in stream only`() {
        val policy = TierPresentationPolicy.presentationFor(PulseTier.Info)
        assertFalse(policy.allowSystemNotification)
        assertFalse(policy.soundAndVibration)
        assertFalse(policy.showInAppBanner)
        assertFalse(policy.showBadgeCount)
        assertTrue(policy.streamOnly)
    }

    @Test
    fun `unknown tier falls back to info tier policy`() {
        val policy = TierPresentationPolicy.presentationFor(PulseTier.Unknown)
        assertEquals(TierPresentationPolicy.presentationFor(PulseTier.Info), policy)
    }

    @Test
    fun `PulseTier fromWire maps wire names case-insensitively with info fallback`() {
        assertEquals(PulseTier.Critical, PulseTier.fromWire("critical"))
        assertEquals(PulseTier.Critical, PulseTier.fromWire("CRITICAL"))
        assertEquals(PulseTier.Warning, PulseTier.fromWire("warning"))
        assertEquals(PulseTier.Warning, PulseTier.fromWire("WARNING"))
        assertEquals(PulseTier.Info, PulseTier.fromWire("info"))
        assertEquals(PulseTier.Info, PulseTier.fromWire("INFO"))
        assertEquals(PulseTier.Info, PulseTier.fromWire("invalid"))
        assertEquals(PulseTier.Info, PulseTier.fromWire(null))
        assertEquals(PulseTier.Warning, PulseTier.fromWire(null, dev.agentpulse.android.data.model.Severity.Error))
        assertEquals(PulseTier.Critical, PulseTier.fromWire(null, dev.agentpulse.android.data.model.Severity.Critical))
    }

    @Test
    fun `PulseAlert decodes tier and defaults to severity mapping when missing`() {
        val withTier = """
            {
              "alert_id": "al_tier_1",
              "category": "decision_required",
              "severity": "critical",
              "tier": "critical",
              "title": "Decision",
              "message": "Approve",
              "status": "active"
            }
        """.trimIndent()
        val alert1 = json.decodeFromString(PulseAlert.serializer(), withTier)
        assertEquals("critical", alert1.tier)
        assertEquals(PulseTier.Critical, alert1.tierLevel)

        val withoutTier = """
            {
              "alert_id": "al_tier_2",
              "category": "task_failed",
              "severity": "error",
              "title": "Failed",
              "message": "Compile error",
              "status": "active"
            }
        """.trimIndent()
        val alert2 = json.decodeFromString(PulseAlert.serializer(), withoutTier)
        assertEquals("", alert2.tier)
        assertEquals(PulseTier.Warning, alert2.tierLevel)
    }

    @Test
    fun `PulseEvent decodes tier and defaults to severity mapping when missing`() {
        val withTier = """
            {
              "event_id": "ev_tier_1",
              "source": "firstmate",
              "kind": "status",
              "severity": "critical",
              "tier": "critical",
              "timestamp": "2026-10-18T08:50:00Z"
            }
        """.trimIndent()
        val event1 = json.decodeFromString(PulseEvent.serializer(), withTier)
        assertEquals("critical", event1.tier)
        assertEquals(PulseTier.Critical, event1.tierLevel)

        val withoutTier = """
            {
              "event_id": "ev_tier_2",
              "source": "firstmate",
              "kind": "status",
              "severity": "info",
              "timestamp": "2026-10-18T08:50:00Z"
            }
        """.trimIndent()
        val event2 = json.decodeFromString(PulseEvent.serializer(), withoutTier)
        assertEquals("", event2.tier)
        assertEquals(PulseTier.Info, event2.tierLevel)
    }
}

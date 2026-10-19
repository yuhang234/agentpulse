package dev.agentpulse.android.notifications

import dev.agentpulse.android.data.model.PulseAlert

sealed class AlertDispatchDecision {
    data object DropDuplicate : AlertDispatchDecision()
    data object SuppressNonCritical : AlertDispatchDecision()
    data class Immediate(val alert: PulseAlert) : AlertDispatchDecision()
    data class Collapsed(
        val count: Int,
        val latestAlert: PulseAlert,
        val collapsedSummary: String,
    ) : AlertDispatchDecision()
}

/**
 * Deduplication and rate limiting for alert notifications:
 * - The same event/alert ID must never alert twice.
 * - A burst of criticals arriving close together (within [burstWindowMs])
 *   collapses into a single alert carrying a count rather than a machine-gun of notifications.
 * - Non-critical alerts (warning/info) never trigger system notifications.
 */
class AlertRateLimiter(
    private val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxDedupeEntries: Int = 1000,
) {
    private val seenAlertIds = LinkedHashSet<String>()
    private var lastCriticalDispatchedAt: Long = 0L
    private var currentBurstCount: Int = 0

    @Synchronized
    fun process(alert: PulseAlert): AlertDispatchDecision {
        val presentation = TierPresentationPolicy.presentationFor(alert.tierLevel)
        if (!presentation.allowSystemNotification) {
            return AlertDispatchDecision.SuppressNonCritical
        }

        val id = alert.alertId.ifBlank { "alert:${alert.title}:${alert.createdAt}" }
        if (seenAlertIds.contains(id)) {
            return AlertDispatchDecision.DropDuplicate
        }

        seenAlertIds.add(id)
        while (seenAlertIds.size > maxDedupeEntries) {
            val oldest = seenAlertIds.iterator().next()
            seenAlertIds.remove(oldest)
        }

        val now = clock()
        val silence = now - lastCriticalDispatchedAt

        return if (silence < burstWindowMs && lastCriticalDispatchedAt > 0L) {
            currentBurstCount++
            AlertDispatchDecision.Collapsed(
                count = currentBurstCount,
                latestAlert = alert,
                collapsedSummary = "${currentBurstCount} 条严重告警: ${alert.title}",
            )
        } else {
            currentBurstCount = 1
            lastCriticalDispatchedAt = now
            AlertDispatchDecision.Immediate(alert)
        }
    }

    @Synchronized
    fun reset() {
        seenAlertIds.clear()
        lastCriticalDispatchedAt = 0L
        currentBurstCount = 0
    }

    companion object {
        const val DEFAULT_BURST_WINDOW_MS = 3_000L
    }
}

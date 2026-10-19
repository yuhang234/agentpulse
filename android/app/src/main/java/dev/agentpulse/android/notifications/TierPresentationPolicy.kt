package dev.agentpulse.android.notifications

import dev.agentpulse.android.data.model.PulseTier

/**
 * Three-tier presentation policy (Captain's intent):
 *
 * - CRITICAL: sound, vibration, Android system notification, in-app banner.
 * - WARNING: silent badge or count in the UI. No sound, no vibration, no system notification.
 * - INFO: event stream only. Never interrupts.
 */
data class TierPresentation(
    val tier: PulseTier,
    val allowSystemNotification: Boolean,
    val soundAndVibration: Boolean,
    val showInAppBanner: Boolean,
    val showBadgeCount: Boolean,
    val streamOnly: Boolean,
)

object TierPresentationPolicy {
    fun presentationFor(tier: PulseTier): TierPresentation = when (tier) {
        PulseTier.Critical -> TierPresentation(
            tier = PulseTier.Critical,
            allowSystemNotification = true,
            soundAndVibration = true,
            showInAppBanner = true,
            showBadgeCount = true,
            streamOnly = false,
        )

        PulseTier.Warning -> TierPresentation(
            tier = PulseTier.Warning,
            allowSystemNotification = false,
            soundAndVibration = false,
            showInAppBanner = false,
            showBadgeCount = true,
            streamOnly = false,
        )

        PulseTier.Info, PulseTier.Unknown -> TierPresentation(
            tier = PulseTier.Info,
            allowSystemNotification = false,
            soundAndVibration = false,
            showInAppBanner = false,
            showBadgeCount = false,
            streamOnly = true,
        )
    }
}

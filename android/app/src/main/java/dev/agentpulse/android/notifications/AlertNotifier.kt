package dev.agentpulse.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.agentpulse.android.MainActivity
import dev.agentpulse.android.R
import dev.agentpulse.android.data.SettingsStore
import dev.agentpulse.android.data.model.PulseAlert

/**
 * Three-tier alert notifier:
 *
 * - CRITICAL: sound, vibration, Android system notification on [CHANNEL_CRITICAL] (IMPORTANCE_HIGH).
 * - WARNING: silent badge or count in the UI only. Suppressed from system notifications.
 * - INFO: live event feed only. Suppressed from system notifications.
 *
 * Rate limiting & deduplication via [AlertRateLimiter]:
 * - Same alert is never alerted twice.
 * - Critical bursts close together collapse into a single alert with count.
 */
class AlertNotifier(
    private val context: Context,
    private val settingsStore: SettingsStore? = null,
    val rateLimiter: AlertRateLimiter = AlertRateLimiter(),
) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            context.getString(R.string.notif_channel_critical),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_critical_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 200, 350)
            enableLights(true)
            lightColor = 0xFFF43F5E.toInt()
            setShowBadge(true)
            setSound(soundUri, audioAttributes)
        }

        val warning = NotificationChannel(
            CHANNEL_WARNING,
            context.getString(R.string.notif_channel_warning),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_warning_desc)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
        }

        system.createNotificationChannels(listOf(critical, warning))
    }

    /**
     * Dispatch an alert through rate limiting and deduplication.
     * Posts a system notification only for CRITICAL alerts when not muted.
     */
    fun notify(alert: PulseAlert) {
        if (settingsStore?.isMuted() == true) return
        if (!hasPermission()) return

        when (val decision = rateLimiter.process(alert)) {
            is AlertDispatchDecision.DropDuplicate -> return
            is AlertDispatchDecision.SuppressNonCritical -> return

            is AlertDispatchDecision.Immediate -> {
                postCriticalNotification(
                    id = NOTIFICATION_ID_CRITICAL,
                    title = decision.alert.title,
                    message = decision.alert.message,
                    taskId = decision.alert.taskId,
                    alertOnce = false,
                )
            }

            is AlertDispatchDecision.Collapsed -> {
                postCriticalNotification(
                    id = NOTIFICATION_ID_CRITICAL,
                    title = "${decision.count} 条严重告警",
                    message = "${decision.latestAlert.title} · ${decision.latestAlert.message}",
                    taskId = decision.latestAlert.taskId,
                    alertOnce = true,
                    subText = "${decision.count} 条告警",
                )
            }
        }
    }

    private fun postCriticalNotification(
        id: Int,
        title: String,
        message: String,
        taskId: String?,
        alertOnce: Boolean,
        subText: String? = null,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ALERTS)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val vibrationPattern = longArrayOf(0, 350, 200, 350)

        val builder = NotificationCompat.Builder(context, CHANNEL_CRITICAL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(vibrationPattern)
            .setOnlyAlertOnce(alertOnce)
            .setContentIntent(pending)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        if (taskId != null) {
            builder.addAction(0, taskId, pending)
        }

        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between check and post.
        }
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_CRITICAL = "agentpulse_critical"
        const val CHANNEL_WARNING = "agentpulse_warning"
        const val NOTIFICATION_ID_CRITICAL = 7891
    }
}

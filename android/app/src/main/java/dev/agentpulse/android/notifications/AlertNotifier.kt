package dev.agentpulse.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.agentpulse.android.MainActivity
import dev.agentpulse.android.R
import dev.agentpulse.android.data.model.PulseAlert
import dev.agentpulse.android.data.model.Severity

/**
 * Notification policy of design report 4.4. Explicit severity -> channel mapping:
 *
 * | severity  | channel              | behaviour                                              |
 * |-----------|----------------------|--------------------------------------------------------|
 * | CRITICAL  | agentpulse_critical  | heads-up banner + vibration + badge (IMPORTANCE_HIGH)   |
 * | WARNING / | agentpulse_warning   | silent (IMPORTANCE_LOW): no sound, no heads-up, badge  |
 * | ERROR     |                      |                                                        |
 * | INFO/DEBUG| none                 | feed-only; never leaves a system notification           |
 *
 * Only alerts are ever notified, and alerts are only derived for CRITICAL/WARNING/ERROR situations,
 * so INFO/DEBUG events stay in the live feed with zero interruption.
 */
class AlertNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            context.getString(R.string.notif_channel_critical),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_critical_desc)
            enableVibration(true)
            enableLights(true)
            lightColor = 0xFFF43F5E.toInt()
            setShowBadge(true)
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

    /** Maps one alert to its channel and posts it, if the user granted POST_NOTIFICATIONS. */
    fun notify(alert: PulseAlert) {
        if (!hasPermission()) return
        val channel = when (alert.severity) {
            Severity.Critical -> CHANNEL_CRITICAL
            Severity.Warning, Severity.Error -> CHANNEL_WARNING
            else -> return // feed-only severity: never notify
        }
        val priority = if (channel == CHANNEL_CRITICAL) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_LOW
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ALERTS)
        }
        val pending = PendingIntent.getActivity(
            context,
            alert.alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(priority)
            .setCategory(if (alert.severity == Severity.Critical) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply {
                if (alert.taskId != null) {
                    addAction(0, alert.taskId, pending)
                }
            }
            .build()

        try {
            manager.notify(alert.alertId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; ignore.
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
    }
}

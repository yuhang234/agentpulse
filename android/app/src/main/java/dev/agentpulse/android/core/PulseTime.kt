package dev.agentpulse.android.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Time helpers shared by the timelines, alert cards and overview counters. */
object PulseTime {

    private val clockFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Formats an epoch-millisecond value as HH:mm:ss in the device timezone. */
    fun formatClock(epochMs: Long): String = clockFormatter.format(Instant.ofEpochMilli(epochMs))

    /**
     * Parses an ISO-8601 timestamp from the service (e.g. "2026-10-18T08:50:10Z") and formats it as HH:mm:ss.
     * Returns "--:--:--" when the input cannot be parsed; the UI never fabricates a time.
     */
    fun formatIsoClock(iso: String?): String {
        if (iso.isNullOrBlank()) return "--:--:--"
        return try {
            dateTimeFormatter.format(Instant.parse(iso))
        } catch (_: Exception) {
            "--:--:--"
        }
    }

    fun parseIso(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso)
        } catch (_: Exception) {
            try {
                // The service may hand out date-only values (e.g. "since": "2026-10-18").
                java.time.LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Formats a duration as HH:mm:ss, used by the "运行时长" counters. */
    fun formatDuration(durationMs: Long): String {
        val clamped = durationMs.coerceAtLeast(0L)
        val d = Duration.ofMillis(clamped)
        val hours = d.toHours()
        val minutes = d.toMinutes() % 60
        val seconds = d.seconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}

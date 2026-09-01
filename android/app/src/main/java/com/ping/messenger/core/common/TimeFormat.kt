package com.ping.messenger.core.common

import android.content.Context
import android.text.format.DateFormat
import com.ping.messenger.R
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * All human-facing time formatting.
 *
 * Everything routes through the platform's [DateFormat] so the user's 12/24-hour preference and
 * locale are respected; the app never hard-codes a pattern like "HH:mm".
 */
class TimeFormatter(private val context: Context, private val clock: Clock = SystemClock) {

    private val locale: Locale get() = context.resources.configuration.locales[0]

    /** "14:32" or "2:32 PM". Used inside message bubbles. */
    fun timeOfDay(timestamp: Long): String =
        DateFormat.getTimeFormat(context).format(Date(timestamp))

    /** "Mon", "12 Aug", "12/08/2024" — the day separator in a transcript. */
    fun dayLabel(timestamp: Long): String = when {
        isToday(timestamp) -> context.getString(R.string.time_today)
        isYesterday(timestamp) -> context.getString(R.string.time_yesterday)
        isWithinLastWeek(timestamp) -> weekdayFormat().format(Date(timestamp))
        isThisYear(timestamp) -> monthDayFormat().format(Date(timestamp))
        else -> DateFormat.getMediumDateFormat(context).format(Date(timestamp))
    }

    /**
     * The chat-list timestamp: time for today, "Yesterday", a weekday inside the last week,
     * then a date. Deliberately never a relative "3 h ago", which forces the reader to do
     * arithmetic when scanning a list.
     */
    fun listTimestamp(timestamp: Long): String = when {
        isToday(timestamp) -> timeOfDay(timestamp)
        isYesterday(timestamp) -> context.getString(R.string.time_yesterday)
        isWithinLastWeek(timestamp) -> weekdayFormat().format(Date(timestamp))
        isThisYear(timestamp) -> monthDayFormat().format(Date(timestamp))
        else -> shortDateFormat().format(Date(timestamp))
    }

    /** "now", "5 min ago", "3 h ago", then falls back to [dayLabel]. Used for last-seen. */
    fun relative(timestamp: Long): String {
        val delta = clock.now() - timestamp
        return when {
            delta < TimeUnit.MINUTES.toMillis(1) -> context.getString(R.string.time_now)
            delta < TimeUnit.HOURS.toMillis(1) ->
                context.getString(R.string.time_minutes_ago, TimeUnit.MILLISECONDS.toMinutes(delta).toInt())
            delta < TimeUnit.DAYS.toMillis(1) && isToday(timestamp) ->
                context.getString(R.string.time_hours_ago, TimeUnit.MILLISECONDS.toHours(delta).toInt())
            isYesterday(timestamp) ->
                "${context.getString(R.string.time_yesterday)} ${timeOfDay(timestamp)}"
            else -> "${dayLabel(timestamp)} ${timeOfDay(timestamp)}"
        }
    }

    /** Countdown for a scheduled message or an expiring status. */
    fun until(timestamp: Long): String {
        val delta = timestamp - clock.now()
        return when {
            delta <= 0 -> context.getString(R.string.time_now)
            delta < TimeUnit.HOURS.toMillis(1) ->
                context.getString(R.string.time_in_minutes, TimeUnit.MILLISECONDS.toMinutes(delta).toInt().coerceAtLeast(1))
            delta < TimeUnit.DAYS.toMillis(1) ->
                context.getString(R.string.time_in_hours, TimeUnit.MILLISECONDS.toHours(delta).toInt())
            else -> "${dayLabel(timestamp)} ${timeOfDay(timestamp)}"
        }
    }

    /** "0:07", "3:41", "1:02:30" — media and call durations. */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(locale, "%d:%02d", minutes, seconds)
        }
    }

    fun durationSeconds(seconds: Long): String = duration(seconds * 1000)

    private fun weekdayFormat() = java.text.SimpleDateFormat("EEE", locale)
    private fun monthDayFormat() = java.text.SimpleDateFormat("d MMM", locale)
    private fun shortDateFormat() = java.text.SimpleDateFormat("dd/MM/yy", locale)

    private fun isToday(timestamp: Long): Boolean = isSameDay(timestamp, clock.now())

    private fun isYesterday(timestamp: Long): Boolean =
        isSameDay(timestamp, clock.now() - TimeUnit.DAYS.toMillis(1))

    private fun isWithinLastWeek(timestamp: Long): Boolean {
        val delta = clock.now() - timestamp
        return delta in 0 until TimeUnit.DAYS.toMillis(7)
    }

    private fun isThisYear(timestamp: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = timestamp }
        val b = Calendar.getInstance().apply { timeInMillis = clock.now() }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** True when two messages should be separated by a date header. */
    fun needsDaySeparator(previous: Long?, current: Long): Boolean =
        previous == null || !isSameDay(previous, current)
}

/** Injected so tests can pin "now" instead of racing the wall clock. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

/** Human-readable byte counts: "1.2 MB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

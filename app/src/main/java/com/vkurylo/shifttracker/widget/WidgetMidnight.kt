package com.vkurylo.shifttracker.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

/**
 * Redraws the widget just after midnight, so "today" (the filled day, the week row, "Today" on the next shift)
 * moves on at once rather than with the next 30 min update. Android 8+ no longer sends DATE_CHANGED to the widget.
 *
 * An inexact, non-waking alarm a few seconds into the new day: it needs no exact-alarm permission, and while the
 * phone sleeps it simply fires when it wakes up, before anyone looks at the home screen. The receiver sets the next
 * one whenever it fires or the widget is drawn; removing the last widget cancels it. Alarms don't survive a reboot,
 * but the widget's update after boot sets it again.
 */
object WidgetMidnight {
    const val ACTION = "com.vkurylo.shifttracker.WIDGET_MIDNIGHT"

    /** A little after the day starts, so the clock has surely moved on when the widget reads it. */
    private const val AFTER_MIDNIGHT_SECONDS = 5L

    /** How late the system may deliver it (it batches inexact alarms). */
    private const val WINDOW_MS = 60_000L

    /** Just after the start of the day after [now]'s (the start of a day is not always 00:00 on a clock change). */
    fun nextRefresh(now: ZonedDateTime): ZonedDateTime =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusSeconds(AFTER_MIDNIGHT_SECONDS)

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextRefresh(now).toInstant().toEpochMilli()
        runCatching { alarms.setWindow(AlarmManager.RTC, at, WINDOW_MS, pendingIntent(context)) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** Always the same PendingIntent, so scheduling again replaces the alarm. */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShiftTrackerWidgetReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

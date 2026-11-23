package com.vkurylo.shifttracker.widget

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The widget redraws itself just after midnight, so "today" moves on at once instead of up to 30 minutes later:
 * an inexact alarm (no exact-alarm permission) a few seconds into the new day, set again each time it fires and
 * whenever the widget is drawn, dropped when the last widget is removed.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class WidgetMidnightTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun theNextRefreshIsJustAfterTheComingMidnight() {
        val evening = ZonedDateTime.of(2026, 11, 21, 23, 59, 30, 0, warsaw)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 0, 0, 5, 0, warsaw), WidgetMidnight.nextRefresh(evening))
        val morning = ZonedDateTime.of(2026, 11, 22, 8, 0, 0, 0, warsaw)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 0, 0, 5, 0, warsaw), WidgetMidnight.nextRefresh(morning))
        // Fired a little early or right on time: still the next day, never the one that is starting.
        val atMidnight = ZonedDateTime.of(2026, 11, 22, 0, 0, 5, 0, warsaw)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 0, 0, 5, 0, warsaw), WidgetMidnight.nextRefresh(atMidnight))
    }

    @Test
    fun aDayWithoutMidnightStartsWhenTheClocksSay() {
        // São Paulo, 4 November 2018: the clocks went from 00:00 straight to 01:00.
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        val evening = ZonedDateTime.of(2018, 11, 3, 22, 0, 0, 0, saoPaulo)
        assertEquals(ZonedDateTime.of(2018, 11, 4, 1, 0, 5, 0, saoPaulo), WidgetMidnight.nextRefresh(evening))
        // The night the clocks go back in Warsaw is 25 h long, midnight is still midnight.
        val back = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, warsaw)
        assertEquals(ZonedDateTime.of(2026, 10, 26, 0, 0, 5, 0, warsaw), WidgetMidnight.nextRefresh(back))
    }

    @Test
    fun drawingSchedulesItFiringRedrawsAndSchedulesTheNextAndRemovingTheWidgetCancelsIt() {
        WidgetMidnight.schedule(context)
        val first = alarms.peekNextScheduledAlarm()
        assertNotNull(first)
        assertEquals("not a wake-up alarm", AlarmManager.RTC, first!!.type)
        assertTrue("within a day", first.triggerAtTime - System.currentTimeMillis() in 1..(25 * 3_600_000L))
        // Scheduling again replaces it rather than adding another.
        WidgetMidnight.schedule(context)
        assertEquals(1, alarms.scheduledAlarms.size)

        val fired = shadowOf(first.operation).savedIntent
        assertEquals(WidgetMidnight.ACTION, fired.action)
        assertEquals(ShiftTrackerWidgetReceiver::class.java.name, fired.component?.className)
        assertTrue(fired.action in ShiftTrackerWidgetReceiver.REFRESH_ACTIONS)

        WidgetMidnight.cancel(context)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun theMidnightBroadcastSetsTheNextOne() {
        assertNull(alarms.peekNextScheduledAlarm())
        ShiftTrackerWidgetReceiver().onReceive(context, Intent(WidgetMidnight.ACTION))
        assertNotNull(alarms.peekNextScheduledAlarm())
    }
}

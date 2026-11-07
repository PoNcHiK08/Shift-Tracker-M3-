package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** The widget's content from domain data: next shift, the month and week calendar, texts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetStateBuilderTest {
    private val nbsp = ' '
    private val en = Locale.US
    private val uk = Locale.forLanguageTag("uk")

    /** Wednesday 4 November 2026, 14:00. */
    private val now = LocalDateTime.of(2026, 11, 4, 14, 0)
    private val today = now.toLocalDate()

    private val cafe = Job(id = 1, name = "Kawiarnia", hourlyRateGrosze = 3000, color = 0xFF43A047.toInt())
    private val shop = Job(id = 2, name = "Shop", hourlyRateGrosze = 2000, color = 0xFF1E88E5.toInt())

    private fun text(locale: Locale): WidgetText {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val res = context.createConfigurationContext(config).resources
        return WidgetText { id, args -> res.getString(id, *args) }
    }

    private var nextId = 1L
    private fun shift(job: Job, date: LocalDate, start: Int, end: Int) = ShiftWithJob(
        Shift(id = nextId++, jobId = job.id, date = date, start = LocalTime.of(start, 0), end = LocalTime.of(end, 0), hourlyRateGrosze = job.hourlyRateGrosze),
        job,
    )

    private fun input(
        shifts: List<ShiftWithJob> = emptyList(),
        jobs: List<Job> = listOf(cafe, shop),
        at: LocalDateTime = now,
        locale: Locale = en,
    ) = WidgetInput(now = at, jobs = jobs, shifts = shifts, locale = locale)

    private fun build(input: WidgetInput) = WidgetStateBuilder.build(input, text(input.locale))

    // --- Next shift ------------------------------------------------------------------------------

    @Test
    fun `a shift in progress comes first and says until when`() {
        val running = shift(cafe, today, 10, 18)
        val later = shift(shop, today, 19, 22)
        val state = build(input(listOf(later, running)))
        assertEquals(running.shift.id, state.nextShift!!.shiftId)
        assertEquals("Until 18:00", state.nextShift!!.whenLabel)
        assertEquals("10:00–18:00", state.nextShift!!.time)
        assertEquals("Kawiarnia", state.nextShift!!.jobName)
        assertEquals(cafe.color, state.nextShift!!.color)
    }

    @Test
    fun `an overnight shift that started yesterday is in progress until it ends`() {
        val night = shift(cafe, today.minusDays(1), 22, 6)
        val tonight = shift(shop, today, 22, 2)
        val early = LocalDateTime.of(2026, 11, 4, 3, 0)
        assertEquals(night.shift.id, WidgetStateBuilder.nextShift(early, listOf(night, tonight))!!.shift.id)
        assertEquals("Until 06:00", build(input(listOf(night, tonight), at = early)).nextShift!!.whenLabel)
        // Once it has ended, the next one is picked.
        val after = LocalDateTime.of(2026, 11, 4, 6, 0)
        assertEquals(tonight.shift.id, WidgetStateBuilder.nextShift(after, listOf(night, tonight))!!.shift.id)
        assertEquals("Today", build(input(listOf(night, tonight), at = after)).nextShift!!.whenLabel)
    }

    @Test
    fun `later today, tomorrow and a date`() {
        assertEquals("Today", build(input(listOf(shift(cafe, today, 18, 22)))).nextShift!!.whenLabel)
        assertEquals("Tomorrow", build(input(listOf(shift(cafe, today.plusDays(1), 8, 16)))).nextShift!!.whenLabel)
        val monday = build(input(listOf(shift(cafe, LocalDate.of(2026, 11, 9), 8, 16), shift(shop, LocalDate.of(2026, 11, 12), 8, 16))))
        assertEquals("Mon 9 Nov", monday.nextShift!!.whenLabel)
        // A shift that already ended today doesn't count.
        assertEquals("Tomorrow", build(input(listOf(shift(cafe, today, 6, 12), shift(shop, today.plusDays(1), 9, 17)))).nextShift!!.whenLabel)
    }

    @Test
    fun `nothing ahead within 60 days`() {
        val state = build(input(listOf(shift(cafe, today.minusDays(3), 8, 16), shift(cafe, today.plusDays(70), 8, 16))))
        assertNull(state.nextShift)
        assertEquals("No upcoming shifts", state.emptyMessage)
        assertTrue(!state.noJobs)
    }

    @Test
    fun `no jobs at all`() {
        val state = build(input(jobs = emptyList()))
        assertTrue(state.noJobs)
        assertEquals("Add your first job", state.emptyMessage)
    }

    // --- Calendar ----------------------------------------------------------------------------------

    @Test
    fun `the month grid starts on Monday and marks today and shift days`() {
        val cafeDay = shift(cafe, LocalDate.of(2026, 11, 2), 8, 16)
        // Two jobs on the 10th: the earlier shift's job gives the color.
        val late = shift(cafe, LocalDate.of(2026, 11, 10), 18, 22)
        val early = shift(shop, LocalDate.of(2026, 11, 10), 6, 10)
        val state = build(input(listOf(cafeDay, late, early, shift(cafe, LocalDate.of(2026, 10, 30), 8, 16))))
        assertEquals("November", state.monthTitle)
        assertEquals(listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"), state.weekdays)
        // 1 November 2026 is a Sunday: 6 weeks, the first with six empty days.
        assertEquals(6, state.monthWeeks.size)
        assertEquals(listOf(null, null, null, null, null, null), state.monthWeeks[0].take(6))
        assertEquals(1, state.monthWeeks[0][6]!!.day)
        assertEquals(30, state.monthWeeks[5][0]!!.day)
        assertTrue(state.monthWeeks[5].drop(1).all { it == null })
        val days = state.monthWeeks.flatten().filterNotNull()
        assertEquals((1..30).toList(), days.map { it.day })
        assertEquals(listOf(4), days.filter { it.today }.map { it.day })
        assertEquals(mapOf(2 to cafe.color, 10 to shop.color), days.filter { it.shiftColor != null }.associate { it.day to it.shiftColor })
    }

    @Test
    fun `the week runs Monday to Sunday and can cross a month`() {
        val sunday = LocalDateTime.of(2026, 11, 1, 9, 0)
        val october = shift(cafe, LocalDate.of(2026, 10, 27), 8, 16)
        val state = build(input(listOf(october), at = sunday))
        assertEquals(listOf(26, 27, 28, 29, 30, 31, 1), state.week.map { it.day })
        assertEquals(listOf(1), state.week.filter { it.today }.map { it.day })
        assertEquals(cafe.color, state.week[1].shiftColor)
        // February 2027 starts on a Monday and has exactly 4 weeks.
        assertEquals(4, WidgetStateBuilder.monthWeeks(YearMonth.of(2027, 2)).size)
    }

    // --- Language ----------------------------------------------------------------------------------

    @Test
    fun `texts and dates follow the app language`() {
        val shifts = listOf(shift(cafe, LocalDate.of(2026, 11, 9), 8, 16), shift(cafe, today, 6, 10))
        val ukState = build(input(shifts, locale = uk))
        assertEquals("Листопад", ukState.monthTitle)
        assertEquals(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"), ukState.weekdays)
        assertEquals("+ Зміна", ukState.addShiftLabel)
        assertEquals(WidgetStateBuilder.dayLabel(LocalDate.of(2026, 11, 9), uk), ukState.nextShift!!.whenLabel)
        assertTrue(ukState.nextShift!!.whenLabel, ukState.nextShift!!.whenLabel.startsWith("Пн"))

        val enState = build(input(shifts, locale = en))
        assertEquals("Mon 9 Nov", enState.nextShift!!.whenLabel)
        assertEquals("До 18:00", build(input(listOf(shift(cafe, today, 10, 18)), locale = uk)).nextShift!!.whenLabel)
    }
}

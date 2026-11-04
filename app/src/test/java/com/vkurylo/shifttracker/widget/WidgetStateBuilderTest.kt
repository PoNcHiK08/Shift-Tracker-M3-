package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.PayType
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.util.Formatters
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

/** The widget's content from domain data: next shift, month totals (the app's own pipeline), texts. */
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
        bonuses: List<BonusWithJob> = emptyList(),
        jobs: List<Job> = listOf(cafe, shop),
        at: LocalDateTime = now,
        mode: EarningsMode = EarningsMode.GROSS,
        hide: Boolean = false,
        locale: Locale = en,
    ) = WidgetInput(
        now = at,
        jobs = jobs,
        monthShifts = shifts.filter { YearMonth.from(it.shift.date) == YearMonth.from(at) },
        monthBonuses = bonuses.filter { YearMonth.from(it.bonus.date) == YearMonth.from(at) },
        upcoming = shifts,
        mode = mode,
        hideAmounts = hide,
        locale = locale,
    )

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
        assertEquals(listOf("0.00${nbsp}zł"), state.amountLines)
        assertEquals("0 h", state.hours)
    }

    // --- Month earnings ----------------------------------------------------------------------------

    @Test
    fun `the month total is the calendar's, bonuses included`() {
        val shifts = listOf(shift(cafe, today.minusDays(2), 8, 16), shift(cafe, today, 8, 12), shift(cafe, LocalDate.of(2026, 10, 30), 8, 16))
        val bonuses = listOf(BonusWithJob(Bonus(jobId = 1, date = today, amountGrosze = 5_000), cafe))
        val input = input(shifts, bonuses)
        val state = build(input)
        // Same call the calendar makes for its month total.
        val calendar = ShiftCalculator.summarize(input.monthShifts, input.monthBonuses, input.jobs, listOf(YearMonth.from(now)), EarningsMode.GROSS)
        assertEquals(41_000L, calendar.totalEarnings["PLN"])
        assertEquals(listOf(Formatters.money(41_000, "PLN", en)), state.amountLines)
        assertEquals("12 h", state.hours)
        assertNull(state.modeTag)
        assertNull(state.moreCurrencies)
    }

    @Test
    fun `currencies are listed apart, two lines then +N`() {
        val euro = Job(id = 3, name = "Berlin", hourlyRateGrosze = 1500, color = 0, currency = "EUR")
        val yen = Job(id = 4, name = "Tokyo", hourlyRateGrosze = 1200, color = 0, currency = "JPY")
        val shifts = listOf(shift(cafe, today, 6, 10), shift(euro, today, 6, 10))
        val two = build(input(shifts, jobs = listOf(cafe, euro)))
        assertEquals(listOf("120.00${nbsp}zł", "€60.00"), two.amountLines)
        assertNull(two.moreCurrencies)

        val three = build(input(shifts + shift(yen, today, 6, 7), jobs = listOf(cafe, euro, yen)))
        assertEquals(2, three.amountLines.size)
        assertEquals("+1", three.moreCurrencies)
    }

    @Test
    fun `a fixed monthly salary counts once in the month`() {
        val office = Job(
            id = 5, name = "Office", hourlyRateGrosze = 0, color = 0, currency = "EUR",
            payHistory = listOf(PaySettings(null, PayType.FIXED_MONTHLY, 300_000)), firstEntry = today, lastEntry = today,
        )
        val state = build(input(listOf(shift(office, today, 9, 17)), jobs = listOf(office)))
        assertEquals(listOf("€3,000.00"), state.amountLines)
        assertEquals("8 h", state.hours)
    }

    @Test
    fun `net mode and the gross or net tag follow the app's rule`() {
        val taxed = cafe.copy(payHistory = listOf(PaySettings(deductionBp = 1_000)))
        val shifts = listOf(shift(taxed, today, 6, 10))
        val net = build(input(shifts, jobs = listOf(taxed, shop), mode = EarningsMode.NET))
        assertEquals(listOf("108.00${nbsp}zł"), net.amountLines)
        assertEquals("Net", net.modeTag)
        assertEquals("Gross", build(input(shifts, jobs = listOf(taxed, shop))).modeTag)
        // No deductions anywhere: gross = net, no tag.
        assertNull(build(input(listOf(shift(cafe, today, 6, 10)), mode = EarningsMode.NET)).modeTag)
    }

    @Test
    fun `hidden amounts show dots, hours stay`() {
        val euro = Job(id = 3, name = "Berlin", hourlyRateGrosze = 1500, color = 0, currency = "EUR")
        val state = build(input(listOf(shift(cafe, today, 6, 10), shift(euro, today, 6, 8)), jobs = listOf(cafe, euro), hide = true))
        assertEquals(listOf("•••"), state.amountLines)
        assertNull(state.moreCurrencies)
        assertEquals("6 h", state.hours)
    }

    // --- Language ----------------------------------------------------------------------------------

    @Test
    fun `texts, dates and amounts follow the app language`() {
        val shifts = listOf(shift(cafe, LocalDate.of(2026, 11, 9), 8, 16), shift(cafe, today, 6, 10))
        val ukState = build(input(shifts, locale = uk))
        assertEquals("За місяць", ukState.monthTitle)
        assertEquals("360,00${nbsp}zł", ukState.amountLines.single()) // 8 h + 4 h at 30 zł
        assertEquals("12 год", ukState.hours)
        assertEquals("+ Зміна", ukState.addShiftLabel)
        assertEquals(WidgetStateBuilder.dayLabel(LocalDate.of(2026, 11, 9), uk), ukState.nextShift!!.whenLabel)
        assertTrue(ukState.nextShift!!.whenLabel, ukState.nextShift!!.whenLabel.startsWith("Пн"))

        val enState = build(input(shifts, locale = en))
        assertEquals("This month", enState.monthTitle)
        assertEquals("360.00${nbsp}zł", enState.amountLines.single())
        assertEquals("Mon 9 Nov", enState.nextShift!!.whenLabel)
        assertEquals("До 18:00", build(input(listOf(shift(cafe, today, 10, 18)), locale = uk)).nextShift!!.whenLabel)
    }
}

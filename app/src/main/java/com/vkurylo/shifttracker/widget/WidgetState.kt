package com.vkurylo.shifttracker.widget

import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.hasDeductions
import com.vkurylo.shifttracker.ui.util.Formatters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the home-screen widget shows, fully formatted; the Glance UI only lays it out. */
data class WidgetState(
    /** "This month" (+ nothing else: the tag is separate). */
    val monthTitle: String,
    /** At most two lines, one per currency, the largest first; "•••" when amounts are hidden. */
    val amountLines: List<String>,
    /** "+1" when more currencies than lines, else null. */
    val moreCurrencies: String?,
    /** "Gross" / "Net", only when some job has deductions (the app's rule). */
    val modeTag: String?,
    /** "42 h" */
    val hours: String,
    val nextShift: NextShift?,
    /** Shown instead of [nextShift]: "No upcoming shifts" or "Add your first job". */
    val emptyMessage: String?,
    /** No jobs at all: taps lead to the Jobs screen. */
    val noJobs: Boolean,
    val addShiftLabel: String,
) {
    data class NextShift(
        val shiftId: Long,
        val jobName: String,
        /** ARGB job color. */
        val color: Int,
        /** "Today" / "Tomorrow" / "Mon 9 Nov", or "Until 06:00" for a shift in progress. */
        val whenLabel: String,
        /** "22:00–06:00" */
        val time: String,
    )
}

/** Localized strings for the builder (backed by a Resources in the app's language). */
fun interface WidgetText {
    fun get(id: Int, vararg args: Any): String
}

/** Everything the widget is built from, read from the app's repositories. */
data class WidgetInput(
    val now: LocalDateTime,
    val jobs: List<Job>,
    /** Shifts and bonuses of the month of [now]. */
    val monthShifts: List<ShiftWithJob>,
    val monthBonuses: List<BonusWithJob>,
    /** Shifts starting from the day before [now] up to [LOOK_AHEAD_DAYS] ahead. */
    val upcoming: List<ShiftWithJob>,
    val mode: EarningsMode,
    val hideAmounts: Boolean,
    val locale: Locale,
) {
    companion object {
        const val LOOK_AHEAD_DAYS = 60L
        const val MAX_AMOUNT_LINES = 2
        const val HIDDEN = "•••"
    }
}

object WidgetStateBuilder {

    /**
     * The month figures come from [ShiftCalculator.summarize] with exactly the arguments the calendar
     * uses for its month total (bonuses, fixed salaries, Gross/Net, per currency): no pay maths here.
     */
    fun build(input: WidgetInput, text: WidgetText): WidgetState {
        val month = YearMonth.from(input.now)
        val summary = ShiftCalculator.summarize(input.monthShifts, input.monthBonuses, input.jobs, listOf(month), input.mode)
        val lines = summary.totalEarnings.sortedByValue().ifEmpty { listOf(Currencies.defaultFor(input.jobs) to 0L) }
        val shown = lines.take(WidgetInput.MAX_AMOUNT_LINES)
        val amountLines = if (input.hideAmounts) listOf(WidgetInput.HIDDEN) else {
            shown.map { (code, value) -> Formatters.money(value, code, input.locale) }
        }
        val more = lines.size - shown.size
        val next = nextShift(input.now, input.upcoming)
        return WidgetState(
            monthTitle = text.get(R.string.widget_this_month),
            amountLines = amountLines,
            moreCurrencies = if (more > 0 && !input.hideAmounts) text.get(R.string.widget_more_currencies, more) else null,
            modeTag = if (input.jobs.any { it.hasDeductions }) {
                text.get(if (input.mode == EarningsMode.NET) R.string.earnings_net else R.string.earnings_gross)
            } else null,
            hours = text.get(R.string.hours_value, Formatters.decimalHours(summary.totalPaidMinutes, input.locale)),
            nextShift = next?.let { nextShiftUi(it, input.now, input.locale, text) },
            emptyMessage = when {
                input.jobs.isEmpty() -> text.get(R.string.widget_add_first_job)
                next == null -> text.get(R.string.widget_no_upcoming)
                else -> null
            },
            noJobs = input.jobs.isEmpty(),
            addShiftLabel = text.get(R.string.widget_add_shift),
        )
    }

    /**
     * The shift in progress at [now], otherwise the nearest one starting after it (within the
     * look-ahead). Shifts are stored by their start date; an overnight one (end not after start) ends
     * the next day, so one that started yesterday evening can still be in progress.
     */
    fun nextShift(now: LocalDateTime, shifts: List<ShiftWithJob>): ShiftWithJob? {
        val limit = now.toLocalDate().plusDays(WidgetInput.LOOK_AHEAD_DAYS + 1).atStartOfDay()
        val running = shifts.filter { start(it) <= now && now < end(it) }.minByOrNull { start(it) }
        return running ?: shifts.filter { start(it) > now && start(it) < limit }.minByOrNull { start(it) }
    }

    private fun start(item: ShiftWithJob): LocalDateTime = item.shift.date.atTime(item.shift.start)

    private fun end(item: ShiftWithJob): LocalDateTime {
        val day = if (ShiftCalculator.isOvernight(item.shift.start, item.shift.end)) item.shift.date.plusDays(1) else item.shift.date
        return day.atTime(item.shift.end)
    }

    private fun nextShiftUi(item: ShiftWithJob, now: LocalDateTime, locale: Locale, text: WidgetText): WidgetState.NextShift {
        val shift = item.shift
        val running = start(item) <= now
        val whenLabel = when {
            // "Until 06:00" rather than "Now": a widget that refreshed a while ago still tells the truth.
            running -> text.get(R.string.widget_until, Formatters.time(shift.end))
            shift.date == now.toLocalDate() -> text.get(R.string.widget_today)
            shift.date == now.toLocalDate().plusDays(1) -> text.get(R.string.widget_tomorrow)
            else -> dayLabel(shift.date, locale)
        }
        return WidgetState.NextShift(
            shiftId = shift.id,
            jobName = item.job.name,
            color = item.job.color,
            whenLabel = whenLabel,
            time = "${Formatters.time(shift.start)}–${Formatters.time(shift.end)}",
        )
    }

    /** "Mon 9 Nov" / "пн 9 лист." with a capital first letter. */
    fun dayLabel(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date).replaceFirstChar { it.titlecase(locale) }
}

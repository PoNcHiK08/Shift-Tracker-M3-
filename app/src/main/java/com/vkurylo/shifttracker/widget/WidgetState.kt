package com.vkurylo.shifttracker.widget

import com.vkurylo.shifttracker.R
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.ui.util.Formatters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What the home-screen widget shows, fully formatted; the Glance UI only lays it out. */
data class WidgetState(
    /** "Листопад" / "November": the month grid's title. */
    val monthTitle: String,
    /** "Пн".."Нд" / "Mo".."Su", Monday first (the app's week). */
    val weekdays: List<String>,
    /** The month as weeks of 7 days, Monday first; null where a day belongs to the next or previous month. */
    val monthWeeks: List<List<CalendarDay?>>,
    /** The current week, Monday to Sunday (its days may fall in a neighbouring month). */
    val week: List<CalendarDay>,
    val nextShift: NextShift?,
    /** Shown instead of [nextShift]: "No upcoming shifts" or "Add your first job". */
    val emptyMessage: String?,
    /** No jobs at all: "+" leads to the Jobs screen. */
    val noJobs: Boolean,
    val addShiftLabel: String,
) {
    data class CalendarDay(
        val day: Int,
        val today: Boolean,
        /** ARGB color of the first job with a shift that day (by start time), null when there is none. */
        val shiftColor: Int?,
    )

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
    /** Shifts from the day before [now] (or the start of the calendar, if earlier) up to [LOOK_AHEAD_DAYS] ahead. */
    val shifts: List<ShiftWithJob>,
    val locale: Locale,
) {
    companion object {
        const val LOOK_AHEAD_DAYS = 60L
    }
}

object WidgetStateBuilder {

    fun build(input: WidgetInput, text: WidgetText): WidgetState {
        val today = input.now.toLocalDate()
        val month = YearMonth.from(today)
        // The first job (by start time) with a shift on each day.
        val colors = input.shifts.sortedWith(compareBy({ it.shift.date }, { it.shift.start }))
            .groupBy { it.shift.date }
            .mapValues { (_, day) -> day.first().job.color }
        fun day(date: LocalDate) = WidgetState.CalendarDay(date.dayOfMonth, date == today, colors[date])
        val next = nextShift(input.now, input.shifts)
        return WidgetState(
            monthTitle = DateTimeFormatter.ofPattern("LLLL", input.locale).format(month).replaceFirstChar { it.titlecase(input.locale) },
            weekdays = DayOfWeek.entries.map { weekdayLabel(it, input.locale) },
            monthWeeks = monthWeeks(month).map { week -> week.map { date -> date?.let(::day) } },
            week = weekOf(today).map(::day),
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

    /** The week (Monday to Sunday) that contains [date]. */
    fun weekOf(date: LocalDate): List<LocalDate> {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L until 7L).map { monday.plusDays(it) }
    }

    /** [month] as Monday-first weeks; days of other months are null. 4 to 6 weeks. */
    fun monthWeeks(month: YearMonth): List<List<LocalDate?>> {
        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = mutableListOf<List<LocalDate?>>()
        var monday = first
        while (!monday.isAfter(month.atEndOfMonth())) {
            weeks += (0L until 7L).map { monday.plusDays(it).takeIf { d -> YearMonth.from(d) == month } }
            monday = monday.plusWeeks(1)
        }
        return weeks
    }

    /** "Пн" / "Mo": two letters at most, capitalized. */
    fun weekdayLabel(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.').take(2).replaceFirstChar { it.titlecase(locale) }

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

package com.vkurylo.shifttracker.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.onDays
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class SummaryPeriod { WEEK, MONTH, YEAR }

/** The concrete date range a period + anchor date resolves to (inclusive). */
data class PeriodRange(val period: SummaryPeriod, val from: LocalDate, val to: LocalDate) {
    companion object {
        fun of(period: SummaryPeriod, anchor: LocalDate): PeriodRange = when (period) {
            SummaryPeriod.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                PeriodRange(period, monday, monday.plusDays(6))
            }
            SummaryPeriod.MONTH -> {
                val month = YearMonth.from(anchor)
                PeriodRange(period, month.atDay(1), month.atEndOfMonth())
            }
            SummaryPeriod.YEAR -> PeriodRange(period, anchor.withDayOfYear(1), anchor.withDayOfYear(anchor.lengthOfYear()))
        }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

/** One row of the week (per day) or year (per month) breakdown. */
data class BreakdownEntry(
    val date: LocalDate,
    val paidMinutes: Long,
    /** Shift pay + bonuses of that day / month, per currency. */
    val earnings: Amounts,
    val shiftCount: Int,
    val bonusCount: Int,
    /** A monthly salary is counted in this month (year view). */
    val hasSalary: Boolean = false,
) {
    val isEmpty: Boolean get() = shiftCount == 0 && bonusCount == 0 && !hasSalary
}

data class SummaryUiState(
    val range: PeriodRange = PeriodRange.of(SummaryPeriod.MONTH, LocalDate.now()),
    val summary: MonthSummary = MonthSummary.EMPTY,
    /** Days of the week (WEEK) or months of the year (YEAR); empty for MONTH. */
    val breakdown: List<BreakdownEntry> = emptyList(),
    /** Shown for zero totals (no entries): the latest job's currency. */
    val defaultCurrency: String = Currencies.DEFAULT,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    shiftRepository: ShiftRepository,
    bonusRepository: BonusRepository,
    jobRepository: JobRepository,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val period = savedStateHandle.getStateFlow(KEY_PERIOD, SummaryPeriod.MONTH.name)
    private val anchor = savedStateHandle.getStateFlow(KEY_ANCHOR, LocalDate.now().toEpochDay())

    val uiState: StateFlow<SummaryUiState> = combine(period, anchor) { p, a ->
        PeriodRange.of(SummaryPeriod.valueOf(p), LocalDate.ofEpochDay(a))
    }
        .flatMapLatest { range ->
            combine(
                shiftRepository.observeShifts(range.from, range.to),
                bonusRepository.observeBonuses(range.from, range.to),
                jobRepository.observeJobs(),
                preferencesRepository.preferences.map { it.earningsMode },
            ) { shifts, allBonuses, jobs, mode ->
                // A week can span two months and a bonus for a whole month belongs to one of them: it is part of
                // the month and the year, never of a week.
                val bonuses = if (range.period == SummaryPeriod.WEEK) allBonuses.onDays() else allBonuses
                SummaryUiState(
                    range = range,
                    summary = ShiftCalculator.summarize(shifts, bonuses, jobs, ShiftCalculator.wholeMonths(range.from, range.to), mode),
                    breakdown = breakdown(range, shifts, bonuses, jobs, mode),
                    defaultCurrency = Currencies.defaultFor(jobs),
                    loading = false,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUiState())

    fun setPeriod(value: SummaryPeriod) {
        savedStateHandle[KEY_PERIOD] = value.name
    }

    fun previous() = shift(-1)
    fun next() = shift(1)

    fun goToToday() {
        savedStateHandle[KEY_ANCHOR] = LocalDate.now().toEpochDay()
    }

    private fun shift(step: Long) {
        val current = LocalDate.ofEpochDay(anchor.value)
        val moved = when (SummaryPeriod.valueOf(period.value)) {
            SummaryPeriod.WEEK -> current.plusWeeks(step)
            SummaryPeriod.MONTH -> current.plusMonths(step)
            SummaryPeriod.YEAR -> current.plusYears(step)
        }
        savedStateHandle[KEY_ANCHOR] = moved.toEpochDay()
    }

    private fun breakdown(
        range: PeriodRange,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        jobs: List<Job>,
        mode: EarningsMode,
    ): List<BreakdownEntry> = when (range.period) {
        SummaryPeriod.WEEK -> (0L..6L).map { offset ->
            val day = range.from.plusDays(offset)
            entry(day, shifts.filter { it.shift.date == day }, bonuses.filter { it.bonus.date == day }, mode)
        }
        SummaryPeriod.YEAR -> (1..12).map { month ->
            val first = range.from.withMonth(month).withDayOfMonth(1)
            entry(
                first,
                shifts.filter { it.shift.date.monthValue == month },
                bonuses.filter { it.bonus.date.monthValue == month },
                mode,
                // Each month of the year carries its fixed salaries.
                salaries = jobs.fold(Amounts.EMPTY) { acc, job ->
                    ShiftCalculator.salary(job, YearMonth.from(first), mode).let { if (it > 0) acc.plus(job.currency, it) else acc }
                },
            )
        }
        SummaryPeriod.MONTH -> emptyList()
    }

    private fun entry(
        date: LocalDate,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        mode: EarningsMode,
        salaries: Amounts = Amounts.EMPTY,
    ) = BreakdownEntry(
        date = date,
        paidMinutes = shifts.sumOf { it.shift.paidMinutes },
        earnings = ShiftCalculator.earnings(shifts, bonuses, mode) + salaries,
        hasSalary = !salaries.isEmpty,
        shiftCount = shifts.size,
        bonusCount = bonuses.size,
    )

    private companion object {
        const val KEY_PERIOD = "period"
        const val KEY_ANCHOR = "anchor"
    }
}

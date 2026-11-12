package com.vkurylo.shifttracker.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.calc.JobYearReconciliation
import com.vkurylo.shifttracker.domain.calc.PayReconciler
import com.vkurylo.shifttracker.domain.calc.PayReconciliation
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Amounts
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.PayoutWithJob
import com.vkurylo.shifttracker.domain.model.ReceivedPart
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.model.forWholeMonths
import com.vkurylo.shifttracker.domain.model.onDays
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.PayoutRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /**
     * Shift pay + bonuses of that day / month, per currency. In the year view a job's month with received pay
     * counts what arrived instead (see [receivedPart]); a day of the week is always calculated.
     */
    val earnings: Amounts,
    val shiftCount: Int,
    val bonusCount: Int,
    /** A monthly salary is counted in this month (year view). */
    val hasSalary: Boolean = false,
    /** How much of [earnings] is received pay (year view only). */
    val receivedPart: ReceivedPart = ReceivedPart.NONE,
) {
    val isEmpty: Boolean get() = shiftCount == 0 && bonusCount == 0 && !hasSalary && receivedPart == ReceivedPart.NONE
}

data class SummaryUiState(
    val range: PeriodRange = PeriodRange.of(SummaryPeriod.MONTH, LocalDate.now()),
    /**
     * Totals of the period. Month and year: a job's month with received pay counts what arrived instead of its
     * calculated pay. Week: always calculated.
     */
    val summary: MonthSummary = MonthSummary.EMPTY,
    /** Days of the week (WEEK) or months of the year (YEAR); empty for MONTH. */
    val breakdown: List<BreakdownEntry> = emptyList(),
    /** The month's bonuses for the whole month (month view only): counted in [summary], listed in their own section. */
    val monthBonuses: List<BonusWithJob> = emptyList(),
    /**
     * Month view: per job, the salary that actually arrived against the calculated NET pay: the comparison of the
     * two figures ([summary] already counts the received one).
     */
    val receivedPay: List<PayReconciliation> = emptyList(),
    /** Year view: per job with received pay, over the months that have it. */
    val receivedPayYear: List<JobYearReconciliation> = emptyList(),
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
    payoutRepository: PayoutRepository,
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
                // Received pay, by the months it was earned for: the month on screen, or the year's.
                when (range.period) {
                    SummaryPeriod.WEEK -> flowOf(emptyList())
                    SummaryPeriod.MONTH -> payoutRepository.observePayouts(YearMonth.from(range.from), YearMonth.from(range.from))
                    SummaryPeriod.YEAR -> payoutRepository.observePayouts(YearMonth.from(range.from), YearMonth.from(range.to))
                },
            ) { shifts, allBonuses, jobs, mode, payouts ->
                // A week can span two months and a bonus for a whole month belongs to one of them: it is part of
                // the month and the year, never of a week.
                val bonuses = if (range.period == SummaryPeriod.WEEK) allBonuses.onDays() else allBonuses
                SummaryUiState(
                    range = range,
                    // A week covers no whole month and observes no payouts: it stays calculated.
                    summary = PayReconciler.actualSummary(shifts, bonuses, jobs, ShiftCalculator.wholeMonths(range.from, range.to), mode, payouts),
                    breakdown = breakdown(range, shifts, bonuses, jobs, mode, payouts),
                    monthBonuses = if (range.period == SummaryPeriod.MONTH) bonuses.forWholeMonths() else emptyList(),
                    receivedPay = receivedPay(range, jobs, shifts, bonuses, payouts),
                    receivedPayYear = receivedPayYear(range, jobs, shifts, bonuses, payouts),
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

    /** Always against the net pay, whatever [EarningsMode] is on: net is what lands in the bank. */
    private fun receivedPay(
        range: PeriodRange,
        jobs: List<Job>,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        payouts: List<PayoutWithJob>,
    ): List<PayReconciliation> =
        if (range.period == SummaryPeriod.MONTH) PayReconciler.forMonth(YearMonth.from(range.from), jobs, shifts, bonuses, payouts) else emptyList()

    private fun receivedPayYear(
        range: PeriodRange,
        jobs: List<Job>,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        payouts: List<PayoutWithJob>,
    ): List<JobYearReconciliation> =
        if (range.period == SummaryPeriod.YEAR) PayReconciler.forYear(range.from.year, jobs, shifts, bonuses, payouts) else emptyList()

    private fun breakdown(
        range: PeriodRange,
        shifts: List<ShiftWithJob>,
        bonuses: List<BonusWithJob>,
        jobs: List<Job>,
        mode: EarningsMode,
        payouts: List<PayoutWithJob>,
    ): List<BreakdownEntry> = when (range.period) {
        SummaryPeriod.WEEK -> (0L..6L).map { offset ->
            val day = range.from.plusDays(offset)
            entry(day, shifts.filter { it.shift.date == day }, bonuses.filter { it.bonus.date == day }, mode)
        }
        SummaryPeriod.YEAR -> (1..12).map { month ->
            val first = range.from.withMonth(month).withDayOfMonth(1)
            val monthShifts = shifts.filter { it.shift.date.monthValue == month }
            val monthBonuses = bonuses.filter { it.bonus.date.monthValue == month }
            val calculated = entry(
                first,
                monthShifts,
                monthBonuses,
                mode,
                // Each month of the year carries its fixed salaries.
                salaries = jobs.fold(Amounts.EMPTY) { acc, job ->
                    ShiftCalculator.salary(job, YearMonth.from(first), mode).let { if (it > 0) acc.plus(job.currency, it) else acc }
                },
            )
            // Jobs of the month with received pay count what arrived instead of their calculated pay.
            val actual = PayReconciler.actualSummary(monthShifts, monthBonuses, jobs, listOf(YearMonth.from(first)), mode, payouts)
            if (actual.receivedPart == ReceivedPart.NONE) calculated
            else calculated.copy(earnings = calculated.earnings - actual.replaced + actual.received, receivedPart = actual.receivedPart)
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

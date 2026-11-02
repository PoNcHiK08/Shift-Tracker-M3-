package com.vkurylo.shifttracker.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.BonusWithJob
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    /** Shifts of the visible month and its neighbours (for the pager), keyed by date. */
    val shiftsByDate: Map<LocalDate, List<ShiftWithJob>> = emptyMap(),
    val bonusesByDate: Map<LocalDate, List<BonusWithJob>> = emptyMap(),
    val monthSummary: MonthSummary = MonthSummary.EMPTY,
    val monthShifts: List<ShiftWithJob> = emptyList(),
    val monthBonuses: List<BonusWithJob> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val selectedDate: LocalDate? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    shiftRepository: ShiftRepository,
    bonusRepository: BonusRepository,
    jobRepository: JobRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    private data class Window(val month: YearMonth, val shifts: List<ShiftWithJob>, val bonuses: List<BonusWithJob>)

    // The visible month plus its neighbours, so the pager can render adjacent months while swiping.
    private val around = month.flatMapLatest { m ->
        val from = m.minusMonths(1).atDay(1)
        val to = m.plusMonths(1).atEndOfMonth()
        combine(shiftRepository.observeShifts(from, to), bonusRepository.observeBonuses(from, to)) { shifts, bonuses ->
            Window(m, shifts, bonuses)
        }
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        around,
        jobRepository.observeJobs(),
        selectedDate,
    ) { window, jobs, selected ->
        val m = window.month
        val monthShifts = window.shifts.filter { YearMonth.from(it.shift.date) == m }
        val monthBonuses = window.bonuses.filter { YearMonth.from(it.bonus.date) == m }
        CalendarUiState(
            month = m,
            shiftsByDate = window.shifts.groupBy { it.shift.date },
            bonusesByDate = window.bonuses.groupBy { it.bonus.date },
            monthSummary = ShiftCalculator.summarize(monthShifts, monthBonuses),
            monthShifts = monthShifts,
            monthBonuses = monthBonuses,
            jobs = jobs,
            selectedDate = selected,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun showMonth(value: YearMonth) = month.update { value }
    fun selectDate(date: LocalDate) = selectedDate.update { date }
    fun dismissDay() = selectedDate.update { null }
}

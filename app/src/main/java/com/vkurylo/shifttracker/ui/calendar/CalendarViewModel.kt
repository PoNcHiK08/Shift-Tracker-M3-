package com.vkurylo.shifttracker.ui.calendar

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    /** Shifts of the visible month and its neighbours (for the pager), keyed by date. */
    val shiftsByDate: Map<LocalDate, List<ShiftWithJob>> = emptyMap(),
    val bonusesByDate: Map<LocalDate, List<BonusWithJob>> = emptyMap(),
    val monthSummary: MonthSummary = MonthSummary.EMPTY,
    val jobs: List<Job> = emptyList(),
    /** The day shown in the panel under the grid; always inside [month]. */
    val selectedDate: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    shiftRepository: ShiftRepository,
    bonusRepository: BonusRepository,
    jobRepository: JobRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = savedStateHandle.getStateFlow(KEY_SELECTED, LocalDate.now().toEpochDay())

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
        selectedDate.map(LocalDate::ofEpochDay),
    ) { window, jobs, selected ->
        val m = window.month
        val monthShifts = window.shifts.filter { YearMonth.from(it.shift.date) == m }
        val monthBonuses = window.bonuses.filter { YearMonth.from(it.bonus.date) == m }
        CalendarUiState(
            month = m,
            shiftsByDate = window.shifts.groupBy { it.shift.date },
            bonusesByDate = window.bonuses.groupBy { it.bonus.date },
            monthSummary = ShiftCalculator.summarize(monthShifts, monthBonuses),
            jobs = jobs,
            selectedDate = selected,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    /** Called when the pager settles on a month; keeps the selected day inside it. */
    fun showMonth(value: YearMonth) {
        month.value = value
        val selected = LocalDate.ofEpochDay(selectedDate.value)
        if (YearMonth.from(selected) != value) {
            val today = LocalDate.now()
            val newSelection = if (YearMonth.from(today) == value) {
                today
            } else {
                // Same day of the month, like most calendar apps (31 Jan -> 28 Feb).
                value.atDay(selected.dayOfMonth.coerceAtMost(value.lengthOfMonth()))
            }
            savedStateHandle[KEY_SELECTED] = newSelection.toEpochDay()
        }
    }

    fun selectDate(date: LocalDate) {
        savedStateHandle[KEY_SELECTED] = date.toEpochDay()
    }

    private companion object {
        const val KEY_SELECTED = "selectedDate"
    }
}

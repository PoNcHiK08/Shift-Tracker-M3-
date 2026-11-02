package com.vkurylo.shifttracker.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.MonthSummary
import com.vkurylo.shifttracker.domain.model.ShiftWithJob
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
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    /** Shifts of the visible month and its neighbours (for the pager), keyed by date. */
    val shiftsByDate: Map<LocalDate, List<ShiftWithJob>> = emptyMap(),
    val monthSummary: MonthSummary = MonthSummary.EMPTY,
    val monthShifts: List<ShiftWithJob> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val selectedDate: LocalDate? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    shiftRepository: ShiftRepository,
    jobRepository: JobRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    private val shiftsAround = month.flatMapLatest { m ->
        shiftRepository.observeShifts(m.minusMonths(1).atDay(1), m.plusMonths(1).atEndOfMonth())
            .map { list -> m to list }
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        shiftsAround,
        jobRepository.observeJobs(),
        selectedDate,
    ) { (m, shifts), jobs, selected ->
        val monthShifts = shifts.filter { YearMonth.from(it.shift.date) == m }
        CalendarUiState(
            month = m,
            shiftsByDate = shifts.groupBy { it.shift.date },
            monthSummary = ShiftCalculator.summarize(monthShifts),
            monthShifts = monthShifts,
            jobs = jobs,
            selectedDate = selected,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun showMonth(value: YearMonth) = month.update { value }
    fun selectDate(date: LocalDate) = selectedDate.update { date }
    fun dismissDay() = selectedDate.update { null }
}

package com.vkurylo.shifttracker.ui.shift

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vkurylo.shifttracker.di.ApplicationScope
import com.vkurylo.shifttracker.domain.calc.ShiftCalculator
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.model.ShiftPay
import com.vkurylo.shifttracker.domain.model.TimedRate
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.navigation.EditShiftRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class EditShiftUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val jobs: List<Job> = emptyList(),
    val jobId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val start: LocalTime = LocalTime.of(8, 0),
    val end: LocalTime = LocalTime.of(16, 0),
    val breakMinutes: String = "0",
    val note: String = "",
    /** Rates saved with the shift (snapshot). */
    val rateGrosze: Long = 0,
    val secondRate: TimedRate? = null,
    val showErrors: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val selectedJob: Job? get() = jobs.firstOrNull { it.id == jobId }
    val breakValue: Int? get() = breakMinutes.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..1440 }
    val durationMinutes: Long get() = ShiftCalculator.durationMinutes(start, end)
    val pay: ShiftPay get() = ShiftCalculator.pay(start, end, breakValue ?: 0, rateGrosze, secondRate)
    val paidMinutes: Long get() = pay.paidMinutes
    val earningsGrosze: Long get() = pay.earningsGrosze
    val isOvernight: Boolean get() = ShiftCalculator.isOvernight(start, end)
    val breakTooLong: Boolean get() = (breakValue ?: 0) >= durationMinutes
    val jobMissing: Boolean get() = selectedJob == null
    /** The job's current rate differs from the snapshot stored on an existing shift. */
    val rateOutdated: Boolean
        get() = !isNew && selectedJob != null &&
            (selectedJob!!.hourlyRateGrosze != rateGrosze || selectedJob!!.secondRate != secondRate)
    val canSave: Boolean get() = !jobMissing && breakValue != null && !breakTooLong
}

sealed interface EditShiftEvent {
    data object Saved : EditShiftEvent
    data class Deleted(val shift: Shift) : EditShiftEvent
}

@HiltViewModel
class EditShiftViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val shiftRepository: ShiftRepository,
    private val jobRepository: JobRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EditShiftRoute>()
    private var original: Shift? = null

    private val _uiState = MutableStateFlow(EditShiftUiState(isNew = route.shiftId == 0L))
    val uiState: StateFlow<EditShiftUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditShiftEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val jobs = jobRepository.observeJobs().first()
            val existing = if (route.shiftId != 0L) shiftRepository.getShift(route.shiftId) else null
            original = existing
            _uiState.value = if (existing != null) {
                EditShiftUiState(
                    loading = false,
                    isNew = false,
                    jobs = jobs,
                    jobId = existing.jobId,
                    date = existing.date,
                    start = existing.start,
                    end = existing.end,
                    breakMinutes = existing.unpaidBreakMinutes.toString(),
                    note = existing.note.orEmpty(),
                    rateGrosze = existing.hourlyRateGrosze,
                    secondRate = existing.secondRate,
                )
            } else {
                val job = jobs.singleOrNull() ?: jobs.firstOrNull()
                EditShiftUiState(
                    loading = false,
                    isNew = true,
                    jobs = jobs,
                    jobId = job?.id,
                    date = route.dateEpochDay.takeIf { it != EditShiftRoute.NO_DATE }?.let(LocalDate::ofEpochDay) ?: LocalDate.now(),
                    rateGrosze = job?.hourlyRateGrosze ?: 0,
                    secondRate = job?.secondRate,
                )
            }
        }
        // Keep the job list fresh (e.g. after creating a job from this screen).
        viewModelScope.launch {
            jobRepository.observeJobs().collect { jobs ->
                _uiState.update { state ->
                    val keepJob = state.jobId != null && jobs.any { it.id == state.jobId }
                    val jobId = if (keepJob) state.jobId else jobs.firstOrNull()?.id
                    val keepRates = keepJob || !state.isNew
                    state.copy(
                        jobs = jobs,
                        jobId = jobId,
                        rateGrosze = if (keepRates) state.rateGrosze else jobs.firstOrNull()?.hourlyRateGrosze ?: 0,
                        secondRate = if (keepRates) state.secondRate else jobs.firstOrNull()?.secondRate,
                    )
                }
            }
        }
    }

    fun selectJob(job: Job) = _uiState.update {
        // Picking a different job always uses that job's current rate.
        if (it.jobId == job.id) it else it.copy(jobId = job.id, rateGrosze = job.hourlyRateGrosze, secondRate = job.secondRate)
    }

    fun setDate(date: LocalDate) = _uiState.update { it.copy(date = date) }
    fun setStart(time: LocalTime) = _uiState.update { it.copy(start = time) }
    fun setEnd(time: LocalTime) = _uiState.update { it.copy(end = time) }
    fun setBreak(text: String) = _uiState.update { it.copy(breakMinutes = text.filter(Char::isDigit).take(4)) }
    fun setNote(text: String) = _uiState.update { it.copy(note = text) }
    fun useCurrentRate() = _uiState.update { s ->
        s.selectedJob?.let { s.copy(rateGrosze = it.hourlyRateGrosze, secondRate = it.secondRate) } ?: s
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) {
            _uiState.update { it.copy(showErrors = true) }
            return
        }
        val job = state.selectedJob ?: return
        val shift = Shift(
            id = original?.id ?: 0,
            jobId = job.id,
            date = state.date,
            start = state.start,
            end = state.end,
            unpaidBreakMinutes = state.breakValue ?: 0,
            hourlyRateGrosze = state.rateGrosze,
            note = state.note.trim().ifEmpty { null },
            externalUid = original?.externalUid,
            secondRate = state.secondRate,
        )
        viewModelScope.launch {
            shiftRepository.upsert(shift)
            _events.send(EditShiftEvent.Saved)
        }
    }

    fun requestDelete() = _uiState.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _uiState.update { it.copy(confirmDelete = false) }

    fun delete() {
        val shift = original ?: return
        _uiState.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            shiftRepository.delete(shift)
            _events.send(EditShiftEvent.Deleted(shift))
        }
    }

    /** Called from the snackbar after this screen is gone, hence the app scope. */
    fun undoDelete(shift: Shift) {
        appScope.launch { shiftRepository.upsert(shift) }
    }
}

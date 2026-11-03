package com.vkurylo.shifttracker.ui.bonus

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vkurylo.shifttracker.di.ApplicationScope
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.model.Bonus
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.ui.navigation.EditBonusRoute
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
import javax.inject.Inject

data class EditBonusUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val jobs: List<Job> = emptyList(),
    val jobId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val amount: String = "",
    val note: String = "",
    val showErrors: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val amountGrosze: Long? get() = MoneyParser.parseGrosze(amount)?.takeIf { it > 0 }
    val jobMissing: Boolean get() = jobs.none { it.id == jobId }
    val canSave: Boolean get() = !jobMissing && amountGrosze != null
}

sealed interface EditBonusEvent {
    data object Saved : EditBonusEvent
    data class Deleted(val bonus: Bonus) : EditBonusEvent
}

@HiltViewModel
class EditBonusViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bonusRepository: BonusRepository,
    private val jobRepository: JobRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EditBonusRoute>()
    private var original: Bonus? = null

    private val _uiState = MutableStateFlow(EditBonusUiState(isNew = route.bonusId == 0L))
    val uiState: StateFlow<EditBonusUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditBonusEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val jobs = jobRepository.observeJobs().first()
            val existing = if (route.bonusId != 0L) bonusRepository.getBonus(route.bonusId) else null
            original = existing
            _uiState.value = if (existing != null) {
                EditBonusUiState(
                    loading = false,
                    isNew = false,
                    jobs = jobs,
                    jobId = existing.jobId,
                    date = existing.date,
                    amount = MoneyParser.toInput(existing.amountGrosze),
                    note = existing.note.orEmpty(),
                )
            } else {
                EditBonusUiState(
                    loading = false,
                    jobs = jobs,
                    jobId = jobs.firstOrNull()?.id,
                    date = route.dateEpochDay.takeIf { it != EditShiftRoute.NO_DATE }?.let(LocalDate::ofEpochDay)
                        ?: LocalDate.now(),
                )
            }
        }
        viewModelScope.launch {
            jobRepository.observeJobs().collect { jobs ->
                _uiState.update { state ->
                    val jobId = state.jobId?.takeIf { id -> jobs.any { it.id == id } } ?: jobs.firstOrNull()?.id
                    state.copy(jobs = jobs, jobId = jobId)
                }
            }
        }
    }

    fun selectJob(job: Job) = _uiState.update { it.copy(jobId = job.id) }
    fun setDate(date: LocalDate) = _uiState.update { it.copy(date = date) }
    fun setAmount(text: String) = _uiState.update { it.copy(amount = text) }
    fun setNote(text: String) = _uiState.update { it.copy(note = text) }

    fun save() {
        val state = _uiState.value
        val amount = state.amountGrosze
        val jobId = state.jobId
        if (!state.canSave || amount == null || jobId == null) {
            _uiState.update { it.copy(showErrors = true) }
            return
        }
        val bonus = Bonus(
            id = original?.id ?: 0,
            jobId = jobId,
            date = state.date,
            amountGrosze = amount,
            note = state.note.trim().ifEmpty { null },
        )
        viewModelScope.launch {
            bonusRepository.upsert(bonus)
            _events.send(EditBonusEvent.Saved)
        }
    }

    fun requestDelete() = _uiState.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _uiState.update { it.copy(confirmDelete = false) }

    fun delete() {
        val bonus = original ?: return
        _uiState.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            bonusRepository.delete(bonus)
            _events.send(EditBonusEvent.Deleted(bonus))
        }
    }

    /** Called from the snackbar after this screen is gone, hence the app scope. */
    fun undoDelete(bonus: Bonus) {
        appScope.launch { bonusRepository.upsert(bonus) }
    }
}

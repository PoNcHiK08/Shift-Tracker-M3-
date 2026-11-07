package com.vkurylo.shifttracker.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.repository.JobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingJobDelete(val job: Job, val shiftCount: Int)

data class JobsUiState(
    val jobs: List<Job> = emptyList(),
    val loading: Boolean = true,
    /** Job being edited in the sheet; id = 0 means a new job. */
    val editing: Job? = null,
    /**
     * The edited job has shifts or bonuses: its currency can't change any more, and pay changes apply
     * from a chosen month on instead of to the whole history.
     */
    val currencyLocked: Boolean = false,
    val pendingDelete: PendingJobDelete? = null,
)

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val jobRepository: JobRepository,
) : ViewModel() {

    private val editing = MutableStateFlow<Job?>(null)
    private val currencyLocked = MutableStateFlow(false)
    private val pendingDelete = MutableStateFlow<PendingJobDelete?>(null)

    val uiState: StateFlow<JobsUiState> = combine(
        jobRepository.observeJobs(),
        editing,
        currencyLocked,
        pendingDelete,
    ) { jobs, edit, locked, delete ->
        JobsUiState(jobs = jobs, loading = false, editing = edit, currencyLocked = locked, pendingDelete = delete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobsUiState())

    /** A new job is paid in the currency of the most recently created one (PLN for the first). */
    fun startCreate(defaultColor: Int) {
        viewModelScope.launch {
            val currency = jobRepository.latestCurrency() ?: Currencies.DEFAULT
            currencyLocked.value = false
            editing.update { Job(name = "", hourlyRateGrosze = 0, color = defaultColor, currency = currency) }
        }
    }

    fun startEdit(job: Job) {
        viewModelScope.launch {
            // Changing it would re-denominate the job's past amounts (100 zł becoming 100 €).
            currencyLocked.value = jobRepository.entryCount(job.id) > 0
            editing.update { job }
        }
    }
    fun dismissEditor() = editing.update { null }

    /** Saves the job with its [pay] settings (see [JobRepository.save] for how they enter its history). */
    fun save(job: Job, pay: PaySettings) {
        viewModelScope.launch {
            jobRepository.save(job.copy(name = job.name.trim()), pay)
            editing.update { null }
        }
    }

    fun requestDelete(job: Job) {
        viewModelScope.launch {
            editing.update { null }
            pendingDelete.update { PendingJobDelete(job, jobRepository.shiftCount(job.id)) }
        }
    }

    fun cancelDelete() = pendingDelete.update { null }

    fun confirmDelete() {
        val pending = pendingDelete.value ?: return
        viewModelScope.launch {
            jobRepository.delete(pending.job)
            pendingDelete.update { null }
        }
    }
}

package com.vkurylo.shifttracker.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.model.Job
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
    val pendingDelete: PendingJobDelete? = null,
)

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val jobRepository: JobRepository,
) : ViewModel() {

    private val editing = MutableStateFlow<Job?>(null)
    private val pendingDelete = MutableStateFlow<PendingJobDelete?>(null)

    val uiState: StateFlow<JobsUiState> = combine(
        jobRepository.observeJobs(),
        editing,
        pendingDelete,
    ) { jobs, edit, delete ->
        JobsUiState(jobs = jobs, loading = false, editing = edit, pendingDelete = delete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobsUiState())

    fun startCreate(defaultColor: Int) = editing.update { Job(name = "", hourlyRateGrosze = 0, color = defaultColor) }
    fun startEdit(job: Job) = editing.update { job }
    fun dismissEditor() = editing.update { null }

    fun save(job: Job) {
        viewModelScope.launch {
            jobRepository.upsert(job.copy(name = job.name.trim()))
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

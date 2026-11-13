package com.vkurylo.shifttracker.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.PaySettings
import com.vkurylo.shifttracker.domain.model.payChangesWith
import com.vkurylo.shifttracker.domain.repository.JobRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class PendingJobDelete(val job: Job, val shiftCount: Int)

/**
 * New pay settings for [job] from [month] would replace those the job has for the [laterMonths] after it: saved only
 * once the user confirms.
 */
data class PendingPayReplace(val job: Job, val pay: PaySettings, val month: YearMonth, val laterMonths: List<YearMonth>)

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
    /** Save was pressed and is being written: a second press must not store the job again. */
    val saving: Boolean = false,
    val pendingPayReplace: PendingPayReplace? = null,
)

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val jobRepository: JobRepository,
) : ViewModel() {

    private val editing = MutableStateFlow<Job?>(null)
    private val currencyLocked = MutableStateFlow(false)
    private val pendingDelete = MutableStateFlow<PendingJobDelete?>(null)
    private val saving = MutableStateFlow(false)
    private val pendingPayReplace = MutableStateFlow<PendingPayReplace?>(null)

    val uiState: StateFlow<JobsUiState> = combine(
        jobRepository.observeJobs(),
        editing,
        currencyLocked,
        pendingDelete,
        combine(saving, pendingPayReplace, ::Pair),
    ) { jobs, edit, locked, delete, (isSaving, payReplace) ->
        JobsUiState(
            jobs = jobs,
            loading = false,
            editing = edit,
            currencyLocked = locked,
            pendingDelete = delete,
            saving = isSaving,
            pendingPayReplace = payReplace,
        )
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
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                val later = laterMonthsReplaced(job, pay)
                if (later.isNotEmpty()) {
                    // Months after this one have settings of their own: ask before replacing them.
                    pendingPayReplace.value = PendingPayReplace(job, pay, pay.from!!, later)
                } else {
                    jobRepository.save(job.copy(name = job.name.trim()), pay)
                    editing.update { null }
                }
            } finally {
                // Saved (the editor is closed), waiting for the answer, or not saved (Save can be pressed again).
                saving.value = false
            }
        }
    }

    /** The months after [pay]'s month with settings of their own, which saving [pay] replaces; none if it changes nothing. */
    private suspend fun laterMonthsReplaced(job: Job, pay: PaySettings): List<YearMonth> {
        val from = pay.from ?: return emptyList()
        val stored = jobRepository.getJob(job.id) ?: return emptyList()
        if (!stored.payChangesWith(pay)) return emptyList()
        return stored.payHistory.mapNotNull { it.from }.filter { it.isAfter(from) }
    }

    /** "Replace" in the question about later months' pay settings: saves as asked. */
    fun confirmPayReplace() {
        val pending = pendingPayReplace.value ?: return
        if (saving.value) return
        pendingPayReplace.value = null
        saving.value = true
        viewModelScope.launch {
            try {
                jobRepository.save(pending.job.copy(name = pending.job.name.trim()), pending.pay)
                editing.update { null }
            } finally {
                saving.value = false
            }
        }
    }

    /** "Cancel" in that question: nothing is saved; the editor stays open with what was typed. */
    fun cancelPayReplace() = pendingPayReplace.update { null }

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

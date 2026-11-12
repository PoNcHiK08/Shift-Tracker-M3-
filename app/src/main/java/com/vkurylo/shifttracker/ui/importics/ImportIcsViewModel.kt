package com.vkurylo.shifttracker.ui.importics

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vkurylo.shifttracker.data.DocumentIo
import com.vkurylo.shifttracker.domain.ics.ImportCandidate
import com.vkurylo.shifttracker.domain.ics.ImportPlanner
import com.vkurylo.shifttracker.domain.ics.IcsParser
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import com.vkurylo.shifttracker.ui.navigation.ImportIcsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

data class ImportUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val candidates: List<ImportCandidate> = emptyList(),
    /** Indexes of [candidates] the user wants to import. */
    val selected: Set<Int> = emptySet(),
    val skippedCount: Int = 0,
    /** Time zones the file uses that couldn't be resolved; their events were read as local time. */
    val unknownZones: List<String> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val jobId: Long? = null,
    val importing: Boolean = false,
) {
    val duplicateCount: Int get() = candidates.count { it.isDuplicate }
    val canImport: Boolean get() = selected.isNotEmpty() && jobId != null && !importing
}

@HiltViewModel
class ImportIcsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentIo: DocumentIo,
    private val shiftRepository: ShiftRepository,
    private val jobRepository: JobRepository,
) : ViewModel() {

    private val uri = Uri.parse(savedStateHandle.toRoute<ImportIcsRoute>().uri)

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val _done = Channel<Int>(Channel.BUFFERED)
    /** Emits the number of imported shifts. */
    val done = _done.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val text = documentIo.readText(uri)
                val result = withContext(Dispatchers.Default) { IcsParser(ZoneId.systemDefault()).parse(text) }
                val candidates = ImportPlanner.plan(result.events, shiftRepository.getAllShifts())
                _uiState.update {
                    it.copy(
                        loading = false,
                        candidates = candidates,
                        selected = candidates.indices.filterNot { i -> candidates[i].isDuplicate }.toSet(),
                        skippedCount = result.skippedCount,
                        unknownZones = result.unknownZones.sorted(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = true) }
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

    fun toggle(index: Int) = _uiState.update {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    fun selectAllNew() = _uiState.update { state ->
        state.copy(selected = state.candidates.indices.filterNot { state.candidates[it].isDuplicate }.toSet())
    }

    fun selectNone() = _uiState.update { it.copy(selected = emptySet()) }

    fun import() {
        val state = _uiState.value
        val job = state.jobs.firstOrNull { it.id == state.jobId } ?: return
        if (!state.canImport) return
        _uiState.update { it.copy(importing = true) }
        viewModelScope.launch {
            val shifts = state.selected.sorted().map { ImportPlanner.toShift(state.candidates[it].event, job) }
            shiftRepository.importFromCalendar(shifts)
            _done.send(shifts.size)
        }
    }
}

package com.vkurylo.shifttracker.ui.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.data.sync.SafetyBackup
import com.vkurylo.shifttracker.di.ApplicationScope
import com.vkurylo.shifttracker.domain.calc.CurrentRateChange
import com.vkurylo.shifttracker.domain.calc.RatePlan
import com.vkurylo.shifttracker.domain.calc.RateScope
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.ShiftRates
import com.vkurylo.shifttracker.domain.repository.ShiftRatesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The "Apply to all shifts of this job" dialog: both scopes worked out up front, so switching is instant. */
data class ApplyRateDialog(
    val job: Job,
    val scope: RateScope = RateScope.ALL,
    val all: RatePlan,
    val fromDate: RatePlan,
    /** The date "This shift's date and later" starts on. */
    val date: LocalDate,
    val applying: Boolean = false,
) {
    /** What Apply would write: the count and money shown are this plan's. */
    val plan: RatePlan get() = if (scope == RateScope.ALL) all else fromDate
}

sealed interface ApplyRateEvent {
    /** [count] shifts got the current rates; [undo] puts their old rates back. */
    data class Applied(val jobId: Long, val count: Int, val undo: Map<Long, ShiftRates>) : ApplyRateEvent

    /** Nothing was changed (the safety backup or the write failed). */
    data object Failed : ApplyRateEvent
}

/**
 * Puts a job's current rates on its other shifts at once, from the Edit shift screen. The shift open in the editor
 * is never written here: it takes the rates on screen (EditShiftViewModel.useCurrentRate) and keeps them on Save, so
 * an unsaved editor can't undo this and this can't overwrite the editor.
 *
 * Before anything changes a safety backup of all data is written; if that fails, nothing changes. The writes are
 * one transaction, each shift stamped like any edit (so it syncs). The widget, calendar and Summary follow the
 * database by themselves.
 */
@HiltViewModel
class ApplyRateViewModel @Inject constructor(
    private val rates: ShiftRatesRepository,
    private val safetyBackup: SafetyBackup,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _dialog = MutableStateFlow<ApplyRateDialog?>(null)
    val dialog: StateFlow<ApplyRateDialog?> = _dialog.asStateFlow()

    private val _events = Channel<ApplyRateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Opens the dialog for [job], leaving out [openShiftId] (the shift in the editor), from [date] for the second scope. */
    fun open(job: Job, openShiftId: Long?, date: LocalDate) {
        viewModelScope.launch {
            val shifts = rates.liveShiftsOf(job.id)
            _dialog.value = ApplyRateDialog(
                job = job,
                all = CurrentRateChange.plan(job, shifts, RateScope.ALL, date, openShiftId),
                fromDate = CurrentRateChange.plan(job, shifts, RateScope.FROM_DATE, date, openShiftId),
                date = date,
            )
        }
    }

    fun setScope(scope: RateScope) = _dialog.update { it?.copy(scope = scope) }

    fun dismiss() {
        _dialog.value = null
    }

    /**
     * Applies the plan shown. Runs in the app scope, so leaving the screen doesn't cut it short. The result arrives
     * as an [ApplyRateEvent].
     */
    fun confirm() {
        val dialog = _dialog.value ?: return
        if (dialog.applying) return
        val plan = dialog.plan
        _dialog.value = dialog.copy(applying = true)
        appScope.launch {
            val result = runCatching {
                safetyBackup.save(SafetyBackup.Reason.BEFORE_RATE_CHANGE)
                rates.setRates(plan.job.id, plan.newRates)
            }
            _dialog.value = null
            _events.send(
                result.fold(
                    onSuccess = { ApplyRateEvent.Applied(plan.job.id, it, plan.oldRates) },
                    onFailure = { ApplyRateEvent.Failed },
                ),
            )
        }
    }

    /** Puts the old rates back (from the snackbar, maybe after the screen is gone). [onFailed] runs if it can't. */
    fun undo(applied: ApplyRateEvent.Applied, onFailed: () -> Unit) {
        appScope.launch {
            runCatching { rates.setRates(applied.jobId, applied.undo) }.onFailure { onFailed() }
        }
    }
}

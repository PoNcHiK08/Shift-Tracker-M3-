package com.vkurylo.shifttracker.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.di.ApplicationScope
import com.vkurylo.shifttracker.domain.calc.MoneyParser
import com.vkurylo.shifttracker.domain.model.Currencies
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Payout
import com.vkurylo.shifttracker.domain.model.TextLimits
import com.vkurylo.shifttracker.domain.repository.PayoutRepository
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
import java.time.YearMonth
import javax.inject.Inject

/** The form for one received payment: a new one for [job] and [month], or an existing one being edited. */
data class PayoutDraft(
    /** 0: a new payment. */
    val id: Long = 0,
    val job: Job,
    /** The month the pay was earned for (not when it arrived). */
    val month: YearMonth,
    val amount: String = "",
    /** Optional: the day it arrived. */
    val receivedOn: LocalDate? = null,
    val note: String = "",
    val showErrors: Boolean = false,
    /** Save was pressed and is being written: a second press must not store the payment again. */
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = id == 0L

    /** In the job's currency; the same limits as a bonus. */
    val currency: String get() = job.currency
    val amountMinor: Long? get() =
        MoneyParser.parseMinor(amount, Currencies.fractionDigits(currency), MoneyParser.AMOUNT_WHOLE_DIGITS)?.takeIf { it > 0 }
    val noteTooLong: Boolean get() = note.length > TextLimits.MAX_LENGTH
    val canSave: Boolean get() = amountMinor != null && !noteTooLong
}

sealed interface PayoutEvent {
    data object Saved : PayoutEvent
    data class Deleted(val payout: Payout) : PayoutEvent
}

/**
 * Adds, edits and deletes the payments of Summary → Month → Received pay. A delete is a soft delete with Undo,
 * like every other record.
 */
@HiltViewModel
class PayoutEditorViewModel @Inject constructor(
    private val payoutRepository: PayoutRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _draft = MutableStateFlow<PayoutDraft?>(null)
    val draft: StateFlow<PayoutDraft?> = _draft.asStateFlow()

    private val _events = Channel<PayoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The payment as stored when it was opened: what Delete removes and Undo brings back (not unsaved edits). */
    private var original: Payout? = null

    fun startNew(job: Job, month: YearMonth) {
        original = null
        _draft.value = PayoutDraft(job = job, month = month)
    }

    fun startEdit(payout: Payout, job: Job) {
        original = payout
        _draft.value = PayoutDraft(
            id = payout.id,
            job = job,
            month = payout.month,
            amount = MoneyParser.toInput(payout.amountMinor, Currencies.fractionDigits(job.currency)),
            receivedOn = payout.receivedOn,
            note = payout.note.orEmpty(),
        )
    }

    /** Closes the form without saving. */
    fun cancel() {
        original = null
        _draft.value = null
    }

    fun setAmount(text: String) = _draft.update { it?.copy(amount = text) }
    fun setReceivedOn(date: LocalDate?) = _draft.update { it?.copy(receivedOn = date) }
    fun setNote(text: String) = _draft.update { it?.copy(note = TextLimits.limitInput(it.note, text)) }

    /** The month it was earned for; years stay within what the cloud rules accept. */
    fun setMonth(month: YearMonth) {
        if (month.year !in Payout.MIN_YEAR..Payout.MAX_YEAR) return
        _draft.update { it?.copy(month = month) }
    }

    fun save() {
        val draft = _draft.value ?: return
        if (draft.saving) return
        val amount = draft.amountMinor
        if (!draft.canSave || amount == null) {
            _draft.update { it?.copy(showErrors = true) }
            return
        }
        val payout = Payout(
            id = draft.id,
            jobId = draft.job.id,
            month = draft.month,
            amountMinor = amount,
            note = draft.note.trim().ifEmpty { null },
            receivedOn = draft.receivedOn,
        )
        _draft.update { it?.copy(saving = true) }
        viewModelScope.launch {
            try {
                payoutRepository.upsert(payout)
            } catch (e: Throwable) {
                // Not saved: Save can be pressed again.
                _draft.update { it?.copy(saving = false) }
                throw e
            }
            original = null
            _draft.value = null
            _events.send(PayoutEvent.Saved)
        }
    }

    /** Deletes the payment being edited. */
    fun delete() {
        val payout = original ?: return
        viewModelScope.launch {
            payoutRepository.delete(payout)
            original = null
            _draft.value = null
            _events.send(PayoutEvent.Deleted(payout))
        }
    }

    /** Called from the snackbar, possibly after this sheet is gone, hence the app scope. */
    fun undoDelete(payout: Payout) {
        appScope.launch { payoutRepository.upsert(payout) }
    }
}
